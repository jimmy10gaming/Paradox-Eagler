/*
 * Copyright (c) 2022-2024 lax1dude, ayunami2000. All Rights Reserved.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 */

package net.lax1dude.eaglercraft.v1_8.sp.lan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformWebRTC;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.sp.SingleplayerServerController;
import net.lax1dude.eaglercraft.v1_8.sp.relay.RelayManager;
import net.lax1dude.eaglercraft.v1_8.sp.relay.RelayServerSocket;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.*;

public class LANServerController {

	public static final Logger logger = LogManager.getLogger("LANServerController");

	public static final List<String> currentICEServers = new ArrayList<>();

	static RelayServerSocket lanRelaySocket = null;

	private static String currentCode = null;

	public static String shareToLAN(Consumer<String> progressCallback, String worldName, boolean worldHidden) {
		currentCode = null;
		RelayServerSocket sock = RelayManager.relayManager.getWorkingRelay((str) -> progressCallback.accept("Connecting: " + str),
				RelayManager.preferredRelayVersion, worldName + (worldHidden ? ";1" : ";0"));
		if(sock == null) {
			lanRelaySocket = null;
			return null;
		}else {
			progressCallback.accept("Opening: " + sock.getURI());
			RelayPacket00Handshake hs = (RelayPacket00Handshake)sock.readPacket();
			lanRelaySocket = sock;
			String code = hs.connectionCode;
			logger.info("Relay [{}] connected as 'server', code: {}", sock.getURI(), code);
			progressCallback.accept("Opened '" + code + "' on " + sock.getURI());
			long millis = EagRuntime.steadyTimeMillis();
			do {
				sock.update();
				if(sock.isClosed()) {
					logger.info("Relay [{}] connection lost", sock.getURI());
					lanRelaySocket = null;
					return null;
				}
				RelayPacket pkt = sock.readPacket();
				if(pkt != null) {
					if(pkt instanceof RelayPacket01ICEServers) {
						RelayPacket01ICEServers ipkt = (RelayPacket01ICEServers)pkt;
						logger.info("Relay [{}] provided ICE servers:", sock.getURI());
						currentICEServers.clear();
						for(net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket01ICEServers.RelayServer srv : ipkt.servers) {
							logger.info("Relay [{}]     {}: {}", sock.getURI(), srv.type.name(), srv.address);
							currentICEServers.add(srv.getICEString());
						}
						PlatformWebRTC.serverLANInitializeServer(currentICEServers.toArray(new String[currentICEServers.size()]));
						return currentCode = code;
					}else {
						logger.error("Relay [{}] unexpected packet: {}", sock.getURI(), pkt.getClass().getSimpleName());
						closeLAN();
						return null;
					}
				}
				EagUtils.sleep(50);
			}while(EagRuntime.steadyTimeMillis() - millis < 2500l);
			logger.info("Relay [{}] relay provide ICE servers timeout", sock.getURI());
			closeLAN();
			return null;
		}
	}

	public static String getCurrentURI() {
		return lanRelaySocket == null ? "<disconnected>" : lanRelaySocket.getURI();
	}

	public static String getCurrentCode() {
		return currentCode == null ? "<undefined>" : currentCode;
	}

	public static void closeLAN() {
		closeLANNoKick();
		cleanupLAN();
		if (isLANOpen()) {
			PlatformWebRTC.serverLANCloseServer();
		}
	}

	public static void closeLANNoKick() {
		if(lanRelaySocket != null) {
			lanRelaySocket.close();
			lanRelaySocket = null;
			currentCode = null;
		}
	}

	public static void cleanupLAN() {
		Iterator<LANClientPeer> itr = clients.values().iterator();
		while(itr.hasNext()) {
			itr.next().disconnect();
		}
		clients.clear();
	}

	public static boolean hasPeers() {
		return PlatformWebRTC.countPeers() > 0;
	}

	public static boolean isHostingLAN() {
		return lanRelaySocket != null || PlatformWebRTC.countPeers() > 0;
	}

	public static boolean isLANOpen() {
		return lanRelaySocket != null;
	}

	private static final Map<String, LANClientPeer> clients = new HashMap<>();

	public static void updateLANServer() {
		if(lanRelaySocket != null) {
			lanRelaySocket.update();
			RelayPacket pkt;
			while((pkt = lanRelaySocket.readPacket()) != null) {
				if(pkt instanceof RelayPacket02NewClient) {
					RelayPacket02NewClient ipkt = (RelayPacket02NewClient) pkt;
					if(!SingleplayerServerController.isChannelNameAllowed(ipkt.clientId)) {
						logger.error("Relay [{}] relay tried to open disallowed channel name: '{}'", lanRelaySocket.getURI(), ipkt.clientId);
					}else if(clients.containsKey(ipkt.clientId)) {
						logger.error("Relay [{}] relay provided duplicate client '{}'", lanRelaySocket.getURI(), ipkt.clientId);
					}else {
						clients.put(ipkt.clientId, new LANClientPeer(ipkt.clientId));
					}
				}else if(pkt instanceof RelayPacket03ICECandidate) {
					RelayPacket03ICECandidate ipkt = (RelayPacket03ICECandidate) pkt;
					LANClientPeer c = clients.get(ipkt.peerId);
					if(c != null) {
						c.handleICECandidates(ipkt.getCandidateString());
					}else {
						logger.error("Relay [{}] relay sent IPacket03ICECandidate for unknown client '{}'", lanRelaySocket.getURI(), ipkt.peerId);
					}
				}else if(pkt instanceof RelayPacket04Description) {
					RelayPacket04Description ipkt = (RelayPacket04Description) pkt;
					LANClientPeer c = clients.get(ipkt.peerId);
					if(c != null) {
						c.handleDescription(ipkt.getDescriptionString());
					}else {
						logger.error("Relay [{}] relay sent IPacket04Description for unknown client '{}'", lanRelaySocket.getURI(), ipkt.peerId);
					}
				}else if(pkt instanceof RelayPacket05ClientSuccess) {
					RelayPacket05ClientSuccess ipkt = (RelayPacket05ClientSuccess) pkt;
					LANClientPeer c = clients.get(ipkt.clientId);
					if(c != null) {
						c.handleSuccess();
					}else {
						logger.error("Relay [{}] relay sent IPacket05ClientSuccess for unknown client '{}'", lanRelaySocket.getURI(), ipkt.clientId);
					}
				}else if(pkt instanceof RelayPacket06ClientFailure) {
					RelayPacket06ClientFailure ipkt = (RelayPacket06ClientFailure) pkt;
					LANClientPeer c = clients.get(ipkt.clientId);
					if(c != null) {
						c.handleFailure();
					}else {
						logger.error("Relay [{}] relay sent IPacket06ClientFailure for unknown client '{}'", lanRelaySocket.getURI(), ipkt.clientId);
					}
				}else if(pkt instanceof RelayPacketFFErrorCode) {
					RelayPacketFFErrorCode ipkt = (RelayPacketFFErrorCode) pkt;
					logger.error("Relay [{}] error code thrown: {}({}): {}", lanRelaySocket.getURI(), RelayPacketFFErrorCode.code2string(ipkt.code), ipkt.code, ipkt.desc);
					Throwable t;
					while((t = lanRelaySocket.getException()) != null) {
						logger.error(t);
					}
				}else {
					logger.error("Relay [{}] unexpected packet: {}", lanRelaySocket.getURI(), pkt.getClass().getSimpleName());
				}
				lanRelaySocket.update();
			}
			if(lanRelaySocket.isClosed()) {
				lanRelaySocket = null;
			}
		}
		Iterator<LANClientPeer> itr = clients.values().iterator();
		while(itr.hasNext()) {
			LANClientPeer cl = itr.next();
			cl.update();
			if(cl.dead) {
				itr.remove();
			}
		}
	}

	public static boolean supported() {
		return PlatformWebRTC.supported();
	}
}