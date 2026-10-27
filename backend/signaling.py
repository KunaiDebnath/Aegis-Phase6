import json
import logging
from typing import Dict, Set
from fastapi import WebSocket

logger = logging.getLogger("aegis.signaling")


class SignalingConnectionManager:
    """
    Manages real-time WebSocket connections for AEGIS devices.
    Enables low-latency, bi-directional signaling for emergency call invitations,
    call acceptances, dismissals, and cancellations between Phone A and Phone B.
    """

    def __init__(self):
        # Maps device_id -> set of active WebSocket instances
        self._active_connections: Dict[str, Set[WebSocket]] = {}

    async def connect(self, device_id: str, websocket: WebSocket):
        await websocket.accept()
        clean_id = device_id.strip()
        if clean_id not in self._active_connections:
            self._active_connections[clean_id] = set()
        self._active_connections[clean_id].add(websocket)
        logger.info(f"Signaling client connected: device_id={clean_id} (active: {len(self._active_connections[clean_id])})")

    def disconnect(self, device_id: str, websocket: WebSocket):
        clean_id = device_id.strip()
        if clean_id in self._active_connections:
            self._active_connections[clean_id].discard(websocket)
            if not self._active_connections[clean_id]:
                del self._active_connections[clean_id]
        logger.info(f"Signaling client disconnected: device_id={clean_id}")

    def is_device_connected(self, device_id: str) -> bool:
        clean_id = device_id.strip()
        return bool(self._active_connections.get(clean_id))

    async def send_to_device(self, device_id: str, message: dict) -> bool:
        """
        Sends a JSON signaling message to all active sockets for a given device_id.
        Returns True if delivered to at least one active socket.
        """
        clean_id = device_id.strip()
        sockets = self._active_connections.get(clean_id, set()).copy()
        if not sockets:
            logger.debug(f"No active WebSocket connection for device_id={clean_id}")
            return False

        message_str = json.dumps(message)
        delivered = False
        dead_sockets = set()

        for ws in sockets:
            try:
                await ws.send_text(message_str)
                delivered = True
            except Exception as e:
                logger.warning(f"Error sending message to device_id={clean_id}: {e}")
                dead_sockets.add(ws)

        for ws in dead_sockets:
            self.disconnect(clean_id, ws)

        return delivered


signaling_manager = SignalingConnectionManager()
