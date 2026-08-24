"""Android entry point for gemini-web2api.

Kotlin (MainService) calls into this module:
    start_server(data_dir) -> JSON str
    stop_server()           -> JSON str
    server_status()         -> JSON str

The app's writable data directory is used as the working directory, so
config.json / cookie.txt / the SQLite cache all live next to each other and
can be edited from the UI or via adb.
"""
import json
import os
import threading
import traceback

from gemini_web2api.config import DEFAULT_CONFIG
from gemini_web2api import __version__

# Defaults tailored for running on a phone: loopback-only by default, and the
# cookie file is created up front so the in-app editor has something to write.
ANDROID_DEFAULT_CONFIG = dict(DEFAULT_CONFIG)
ANDROID_DEFAULT_CONFIG.update({"host": "127.0.0.1", "cookie_file": "cookie.txt"})

_lock = threading.Lock()
_httpd = None
_thread = None
_last_error = ""


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
    return {
        "running": _httpd is not None,
        "host": CONFIG.get("host"),
        "port": port,
        "base_url": "http://127.0.0.1:{}/v1".format(port) if port else "",
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
