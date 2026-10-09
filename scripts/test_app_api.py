import contextlib
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("app_api", Path(__file__).with_name("app-api.py"))
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)
TOKEN = "0123456789abcdef" * 4


class ClientTests(unittest.TestCase):
    def test_config_is_strict_and_cannot_select_a_lan_bind(self):
        valid = {"enabled": True, "port": 9091, "token": TOKEN}
        self.assertEqual(valid, api.validate_config(valid.copy()))
        for patch_value in ({"enabled": "true"}, {"port": True}, {"port": 65536}, {"port": "9091"}, {"token": "short"}, {"host": "0.0.0.0"}):
            with self.subTest(value=patch_value), self.assertRaises(ValueError):
                api.validate_config(valid | patch_value)

    def test_credentials_are_saved_privately_and_replaced_atomically(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            api.save_private(path, {"token": TOKEN})
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            api.save_private(path, {"token": "replacement"})
            self.assertEqual("replacement", json.loads(path.read_text())["token"])
            self.assertEqual([path], list(Path(directory).iterdir()))

    @contextlib.contextmanager
    def server(self, code=200, payload=None, redirect=False):
        received = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                received.append((self.path, self.headers.get("Authorization"), self.rfile.read(int(self.headers["Content-Length"]))))
                self.send_response(code)
                if redirect:
                    self.send_header("Location", "https://example.invalid/collect")
                self.end_headers()
                self.wfile.write(json.dumps(payload or {"result": {"ok": True}}).encode())

            def log_message(self, *_):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        worker = threading.Thread(target=server.serve_forever)
        worker.start()
        try:
            yield {"port": server.server_port, "token": TOKEN}, received
        finally:
            server.shutdown()
            worker.join()
            server.server_close()

    def test_call_uses_loopback_bearer_and_ignores_environment_proxies(self):
        with self.server() as (state, received), patch.dict("os.environ", {"http_proxy": "http://example.invalid:8080", "no_proxy": ""}):
            self.assertEqual({"ok": True}, api.call(state, "profiles.list", {"limit": 3}))
            self.assertEqual("/v1/profiles.list", received[0][0])
            self.assertEqual("Bearer " + TOKEN, received[0][1])
            self.assertEqual({"limit": 3}, json.loads(received[0][2]))

    def test_call_does_not_follow_redirects_or_retry_mutations(self):
        with self.server(code=302, redirect=True) as (state, received):
            with self.assertRaisesRegex(RuntimeError, "redirect"):
                api.call(state, "profiles.create", {})
            self.assertEqual(1, len(received))
        with self.server(code=401) as (state, received):
            with self.assertRaisesRegex(RuntimeError, "401"):
                api.call(state, "profiles.create", {})
            self.assertEqual(1, len(received))

    def test_application_errors_are_not_success(self):
        with self.server(payload={"error": {"code": "confirmation_required"}}) as (state, _):
            with self.assertRaisesRegex(RuntimeError, "confirmation_required"):
                api.call(state, "profiles.delete", {})

    def test_invalid_calls_fail_before_network_io(self):
        state = {"port": 9091, "token": TOKEN}
        for operation, params in (("../settings", {}), ("profiles.list", []), ("profiles.list", {"x": float("nan")})):
            with self.subTest(operation=operation), self.assertRaises(ValueError):
                api.call(state, operation, params)
        with self.assertRaises(ValueError):
            api.call(state, "profiles.list", {"text": "x" * (2 * 1024 * 1024)})

    def test_smoke_does_not_mutate_or_stop_a_running_vpn(self):
        invoked = []

        def call(_, operation, __):
            invoked.append(operation)
            if operation == "api.describe":
                return {"commands": {"app.status": {}}}
            if operation == "app.status":
                return {"serviceState": "Connected"}
            return {}

        with patch.object(api, "call", side_effect=call):
            self.assertTrue(api.smoke({}, mutate=False)["readOnly"])
            with self.assertRaisesRegex(RuntimeError, "stopped VPN"):
                api.smoke({}, mutate=True)
        self.assertNotIn("groups.create", invoked)
        self.assertNotIn("service.stop", invoked)


if __name__ == "__main__":
    unittest.main()
