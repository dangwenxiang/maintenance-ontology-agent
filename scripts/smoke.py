"""Run the packaged application in an isolated temporary directory; verify restart persistence."""
import json
import os
import pathlib
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
jar = root / "target/maintenance-ontology-agent-0.2.0.jar"
java = str(pathlib.Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
token = secrets.token_hex(24)
env = {**os.environ, "PORT": str(port), "BIND_ADDRESS": "127.0.0.1", "ADMIN_TOKEN": token,
       "DSH_ENABLED": "false", "MODEL_BASE_URL": "", "MODEL_NAME": "", "MODEL_API_KEY": ""}
env.pop("DB_URL", None)
env.pop("DB_PASSWORD", None)

def request(path, body=None, admin=False):
    headers = {"Content-Type": "application/json"}
    if admin:
        headers["X-Admin-Token"] = token
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/api{path}", data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)

with tempfile.TemporaryDirectory(prefix="maintenance-smoke-") as work:
    log = open(pathlib.Path(work) / "server.log", "w+")
    process = None

    def start():
        global process
        process = subprocess.Popen([java, "-jar", str(jar)], cwd=work, env=env, stdout=log, stderr=log)
        for _ in range(100):
            if process.poll() is not None:
                raise RuntimeError("Application exited; check build and runtime")
            try:
                if request("/health")["status"] == "ok":
                    return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(0.2)
        raise RuntimeError("Application startup timed out")

    def stop():
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()

    try:
        start()
        answer = request("/ask", {"question": "同型号扭矩不合格的历史原因", "deviceId": "D-DEMO-01"})
        assert {c["id"] for c in answer["cases"]} == {"C-DEMO-001", "C-DEMO-002"}
        assert [d["id"] for d in answer["documents"]] == ["DOC-A-1"]
        case = request("/feedback", {"traceId": answer["id"], "cause": "烟测合成原因", "action": "烟测处理", "result": "烟测结果"})["caseId"]
        assert not any(c["id"] == case for c in request("/cases"))
        try:
            request("/review")
            raise AssertionError("Missing token accepted")
        except urllib.error.HTTPError as error:
            assert error.code == 401
        request("/review/" + case, {"approve": True, "reviewer": "烟测审核者"}, True)
        assert any(c["id"] == case for c in request("/cases"))
        stop()
        start()
        assert request("/traces/" + answer["id"])["question"] == answer["question"]
        assert any(c["id"] == case for c in request("/cases"))
        print("PASS: packaged HTTP, scope isolation, feedback review, authorization, restart persistence")
    finally:
        if process and process.poll() is None:
            stop()
        log.close()
