import os
import logging
from typing import Dict, Any, Optional, Tuple

logger = logging.getLogger("aegis.fcm")

_firebase_initialized = False
_firebase_project_id: Optional[str] = None
_init_error: Optional[str] = None


def resolve_credentials_path() -> Optional[str]:
    raw_path = os.getenv("FIREBASE_CREDENTIALS_PATH") or os.getenv("GOOGLE_APPLICATION_CREDENTIALS")
    if not raw_path:
        return None

    if os.path.isabs(raw_path) and os.path.exists(raw_path):
        return raw_path

    # Try relative to backend directory
    backend_dir = os.path.dirname(os.path.abspath(__file__))
    candidate = os.path.join(backend_dir, raw_path)
    if os.path.exists(candidate):
        return candidate

    # Try relative to CWD
    if os.path.exists(raw_path):
        return os.path.abspath(raw_path)

    return None


def init_firebase_admin() -> bool:
    success, _ = init_firebase_admin_with_detail()
    return success


def init_firebase_admin_with_detail() -> Tuple[bool, Optional[str]]:
    global _firebase_initialized, _firebase_project_id, _init_error
    if _firebase_initialized:
        return True, None

    raw_path = os.getenv("FIREBASE_CREDENTIALS_PATH") or os.getenv("GOOGLE_APPLICATION_CREDENTIALS")
    if not raw_path:
        _init_error = "FIREBASE_CREDENTIALS_PATH environment variable is not set"
        logger.warning(f"FCM unconfigured: {_init_error}")
        return False, _init_error

    creds_path = resolve_credentials_path()
    if not creds_path:
        _init_error = f"Service account key file not found at path '{raw_path}'"
        logger.error(f"FCM error: {_init_error}")
        return False, _init_error

    try:
        import firebase_admin
        from firebase_admin import credentials

        if not firebase_admin._apps:
            cred = credentials.Certificate(creds_path)
            app = firebase_admin.initialize_app(cred)
            _firebase_project_id = app.project_id
        else:
            default_app = firebase_admin.get_app()
            _firebase_project_id = default_app.project_id

        _firebase_initialized = True
        _init_error = None
        logger.info(f"Firebase Admin SDK initialized successfully for project: {_firebase_project_id}")
        return True, None
    except ImportError:
        _init_error = "firebase-admin package is not installed in the active environment"
        logger.error(_init_error)
        return False, _init_error
    except Exception as e:
        _init_error = f"Failed to initialize Firebase Admin SDK: {e}"
        logger.error(_init_error)
        return False, _init_error


def get_fcm_status() -> Dict[str, Any]:
    raw_path = os.getenv("FIREBASE_CREDENTIALS_PATH") or os.getenv("GOOGLE_APPLICATION_CREDENTIALS")
    creds_path = resolve_credentials_path()
    initialized, err = init_firebase_admin_with_detail()
    return {
        "configured": raw_path is not None,
        "credentials_configured_path": raw_path,
        "credentials_path_resolved": creds_path is not None,
        "initialized": initialized,
        "error": err,
        "project_id": _firebase_project_id if initialized else None,
    }


def send_fcm_call_invitation(
    fcm_token: str,
    invitation_id: str,
    emergency_event_id: str,
    caller_device_id: str,
    caller_display_name: Optional[str],
    expires_at_iso: str,
    dry_run: bool = False,
) -> Tuple[bool, Optional[str]]:
    """
    Dispatches a high-priority FCM data message for an incoming emergency call invitation.
    Contains no secrets or long-lived tokens.
    Returns (success: bool, detail_or_message_id: str).
    """
    if not init_firebase_admin():
        return False, "Firebase Admin SDK is not initialized"

    clean_token = fcm_token.strip()
    if not clean_token:
        return False, "FCM token is empty"

    try:
        from firebase_admin import messaging
        from firebase_admin.exceptions import FirebaseError

        message = messaging.Message(
            token=clean_token,
            data={
                "type": "incoming_call_invitation",
                "invitation_id": invitation_id,
                "emergency_event_id": emergency_event_id,
                "caller_device_id": caller_device_id,
                "caller_display_name": caller_display_name or caller_device_id,
                "expires_at": expires_at_iso,
            },
            android=messaging.AndroidConfig(
                priority="high",
                ttl=60,  # 60s time-to-live matching invitation validity
            ),
        )
        response_id = messaging.send(message, dry_run=dry_run)
        logger.info(f"FCM invitation message sent successfully (dry_run={dry_run}): {response_id}")
        return True, response_id

    except Exception as e:
        # Avoid logging the full token in logs to maintain privacy
        masked_token = f"{clean_token[:8]}...{clean_token[-4:]}" if len(clean_token) > 12 else "token"
        err_msg = str(e)
        logger.warning(f"Failed to dispatch FCM message to {masked_token}: {err_msg}")
        return False, err_msg
