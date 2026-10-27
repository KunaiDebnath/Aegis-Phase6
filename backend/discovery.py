import logging
import socket
import json
import threading
from typing import Dict, Any, Tuple
from zeroconf import Zeroconf, ServiceInfo

logger = logging.getLogger("aegis_discovery")

DISCOVERY_SERVICE_TYPE = "_aegis._tcp.local."
DISCOVERY_SERVICE_NAME = "AEGIS-Safety-Server._aegis._tcp.local."
DEFAULT_API_PORT = 8000
DEFAULT_LIVEKIT_PORT = 7880
UDP_DISCOVERY_PORT = 8001
UDP_PING_PAYLOAD = "AEGIS_DISCOVERY_PING"


def detect_lan_ip() -> str:
    """
    Detects the laptop's usable LAN IPv4 address on the active outbound network interface.
    Avoids loopback (127.0.0.1) and APIPA (169.254.x.x) addresses.
    """
    # Strategy 1: UDP connect probe to external address (does not send packets, queries kernel route)
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            if ip and not ip.startswith("127.") and not ip.startswith("169.254."):
                return ip
    except Exception as e:
        logger.debug(f"Outbound route detection failed: {e}")

    # Strategy 2: Hostname resolution fallback
    try:
        hostname = socket.gethostname()
        for ip in socket.gethostbyname_ex(hostname)[2]:
            if not ip.startswith("127.") and not ip.startswith("169.254."):
                return ip
    except Exception as e:
        logger.debug(f"Hostname lookup detection failed: {e}")

    return "127.0.0.1"


class AegisServiceAdvertiser:
    """
    Manages both mDNS/DNS-SD advertising and UDP broadcast discovery fallback.
    Ensures seamless discovery across routers with or without multicast filtering.
    """

    def __init__(
        self,
        api_port: int = DEFAULT_API_PORT,
        livekit_port: int = DEFAULT_LIVEKIT_PORT,
        udp_port: int = UDP_DISCOVERY_PORT
    ):
        self.api_port = api_port
        self.livekit_port = livekit_port
        self.udp_port = udp_port
        self.lan_ip = detect_lan_ip()
        self.zeroconf: Zeroconf | None = None
        self.service_info: ServiceInfo | None = None
        self._udp_running = False
        self._udp_thread: threading.Thread | None = None
        self._udp_socket: socket.socket | None = None

    def get_discovery_payload(self) -> Dict[str, Any]:
        return {
            "service": "aegis",
            "status": "healthy",
            "server_name": "AEGIS Safety Server",
            "lan_ip": self.lan_ip,
            "api_port": self.api_port,
            "api_base_url": f"http://{self.lan_ip}:{self.api_port}/",
            "livekit_url": f"ws://{self.lan_ip}:{self.livekit_port}",
            "livekit_port": self.livekit_port,
            "version": "1.0",
        }

    def start(self):
        self.lan_ip = detect_lan_ip()
        logger.info(f"Starting AEGIS service advertiser on active LAN IP: {self.lan_ip}")

        # 1. Start mDNS advertising via zeroconf
        try:
            desc = {
                "api_port": str(self.api_port),
                "livekit_port": str(self.livekit_port),
                "app": "aegis",
                "version": "1.0",
                "lan_ip": self.lan_ip,
            }
            self.service_info = ServiceInfo(
                DISCOVERY_SERVICE_TYPE,
                DISCOVERY_SERVICE_NAME,
                addresses=[socket.inet_aton(self.lan_ip)],
                port=self.api_port,
                properties=desc,
                server=f"aegis-{self.lan_ip.replace('.', '-')}.local.",
            )
            self.zeroconf = Zeroconf()
            self.zeroconf.register_service(self.service_info)
            logger.info(f"mDNS service registered: {DISCOVERY_SERVICE_NAME} on port {self.api_port}")
        except Exception as e:
            logger.warning(f"Failed to start mDNS service advertiser: {e}")

        # 2. Start UDP discovery responder (fallback for routers that drop multicast)
        self._start_udp_responder()

    def _start_udp_responder(self):
        try:
            self._udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self._udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self._udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            # Bind to all interfaces on UDP port
            self._udp_socket.bind(("0.0.0.0", self.udp_port))
            self._udp_socket.settimeout(1.0)
            self._udp_running = True

            self._udp_thread = threading.Thread(target=self._udp_worker, daemon=True)
            self._udp_thread.start()
            logger.info(f"UDP discovery responder listening on port {self.udp_port}")
        except Exception as e:
            logger.warning(f"Failed to start UDP discovery responder on port {self.udp_port}: {e}")

    def _udp_worker(self):
        while self._udp_running and self._udp_socket:
            try:
                data, addr = self._udp_socket.recvfrom(2048)
                msg = data.decode("utf-8", errors="ignore").strip()
                if msg == UDP_PING_PAYLOAD or "AEGIS_DISCOVERY" in msg:
                    # Refresh LAN IP in case it changed
                    current_ip = detect_lan_ip()
                    if current_ip != "127.0.0.1":
                        self.lan_ip = current_ip

                    response = json.dumps(self.get_discovery_payload()).encode("utf-8")
                    self._udp_socket.sendto(response, addr)
                    logger.debug(f"Responded to discovery ping from {addr[0]}:{addr[1]}")
            except socket.timeout:
                continue
            except Exception as e:
                if self._udp_running:
                    logger.debug(f"UDP discovery worker error: {e}")

    def stop(self):
        logger.info("Stopping AEGIS service advertiser...")
        self._udp_running = False
        if self._udp_socket:
            try:
                self._udp_socket.close()
            except Exception:
                pass
            self._udp_socket = None

        if self.zeroconf and self.service_info:
            try:
                self.zeroconf.unregister_service(self.service_info)
                self.zeroconf.close()
                logger.info("mDNS service unregistered.")
            except Exception as e:
                logger.debug(f"Error closing zeroconf: {e}")
            self.zeroconf = None
            self.service_info = None


# Global singleton instance
advertiser = AegisServiceAdvertiser()
