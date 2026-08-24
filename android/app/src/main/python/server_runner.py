"""Android entry point for gemini-web2api.

Kotlin (MainService) calls into this module:
    start_server(data_dir) -> JSON str
    stop_server()           -> JSON str
    server_status()         -> JSON str
    get_logs(n)             -> str  (tail of the captured log)
    clear_logs()            -> str

The app's writable data directory is used as the working directory, so
config.json / cookie.txt / the SQLite cache all live next to each other and
can be edited from the UI or via adb.
"""
import collections
import json
import os
import socket
import sys
import threading
import traceback

from gemini_web2api.config import DEFAULT_CONFIG
from gemini_web2api import __version__

# LAN-accessible by default so other devices on the same Wi-Fi (e.g. a PC)
# can call the API; the in-app settings UI can switch back to loopback-only.
ANDROID_DEFAULT_CONFIG = dict(DEFAULT_CONFIG)
ANDROID_DEFAULT_CONFIG.update({"host": "0.0.0.0", "cookie_file": "cookie.txt"})

_lock = threading.Lock()
_httpd = None
_thread = None
_last_error = ""

# ─── In-app log viewer ───────────────────────────────────────────────────────
# All stdout/stderr writes (request logs, errors, startup prints) are kept in
# a bounded ring buffer that the Kotlin UI polls once per second.
_LOG_MAX_LINES = 500
_log_lines = collections.deque(maxlen=_LOG_MAX_LINES)
_log_lock = threading.Lock()
_log_tee_installed = False


class _TeeStream:
    """Append writes to the log buffer while forwarding to the original stream."""

    def __init__(self, original):
        self._original = original

    def write(self, text):
        try:
            _append_log(text)
        except Exception:
            pass
        try:
            self._original.write(text)
        except Exception:
            pass

    def flush(self):
        try:
            self._original.flush()
        except Exception:
            pass

    def isatty(self):
        return False


def _append_log(text):
    with _log_lock:
        for line in text.splitlines():
            if line.strip():
                _log_lines.append(line)


def _install_log_tee():
    global _log_tee_installed
    if _log_tee_installed:
        return
    sys.stdout = _TeeStream(sys.stdout)
    sys.stderr = _TeeStream(sys.stderr)
    _log_tee_installed = True


def get_logs(n: int = 200) -> str:
    with _log_lock:
        return "\n".join(list(_log_lines)[-n:])


def clear_logs() -> str:
    with _log_lock:
        _log_lines.clear()
    return get_logs()


def _lan_ip() -> str:
    """Best-effort local IP; the UDP connect sends no traffic."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def _prepare_data_dir(data_dir):
    """Create default config/cookie files and chdir so relative paths resolve."""
    os.makedirs(data_dir, exist_ok=True)
    os.chdir(data_dir)

    config_path = os.path.join(data_dir, "config.json")
    if not os.path.exists(config_path):
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(ANDROID_DEFAULT_CONFIG, f, ensure_ascii=False, indent=2)

    cookie_path = os.path.join(data_dir, "cookie.txt")
    if not os.path.exists(cookie_path):
        with open(cookie_path, "w", encoding="utf-8") as f:
            f.write("")
    return config_path


def _status():
    from gemini_web2api.config import CONFIG

    port = CONFIG.get("port")
    host = CONFIG.get("host")
    # 0.0.0.0 is not connectable, so show the address other devices should use.
    shown_host = _lan_ip() if host in ("0.0.0.0", "::", "") else host
    return {
        "running": _httpd is not None,
        "host": host,
        "port": port,
        "base_url": "http://{}:{}/v1".format(shown_host, port) if port else "",
        "error": _last_error,
        "version": __version__,
    }


def _status_json():
    return json.dumps(_status(), ensure_ascii=False)


def start_server(data_dir):
    """Start the HTTP server on a background thread. Returns status JSON."""
    global _httpd, _thread, _last_error
    with _lock:
        if _httpd is not None:
            return _status_json()
        try:
            _install_log_tee()
            config_path = _prepare_data_dir(data_dir)
            from gemini_web2api.config import CONFIG, load_config
            from gemini_web2api.server import GeminiHandler, ThreadedServer

            load_config(config_path)
            _httpd = ThreadedServer((CONFIG["host"], int(CONFIG["port"])), GeminiHandler)
            _last_error = ""
        except Exception:
            _httpd = None
            _last_error = traceback.format_exc(limit=3).strip()
            print("[server_runner] failed to start:\n" + _last_error)
            return _status_json()

        _thread = threading.Thread(
            target=_httpd.serve_forever, name="gemini-web2api-http", daemon=True
        )
        _thread.start()
        print(
            "[server_runner] listening on {}:{} (base url {})".format(
                _status()["host"], _status()["port"], _status()["base_url"]
            )
        )
        return _status_json()


def stop_server():
    """Stop the HTTP server. Active in-flight requests finish on their own."""
    global _httpd, _thread, _last_error
    with _lock:
        if _httpd is None:
            return _status_json()
        httpd, _httpd, _thread = _httpd, None, None
        try:
            httpd.shutdown()
            httpd.server_close()
        except Exception:
            _last_error = traceback.format_exc(limit=3).strip()
            print("[server_runner] error while stopping:\n" + _last_error)
        return _status_json()


def server_status():
    return _status_json()
