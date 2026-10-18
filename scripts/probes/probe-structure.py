"""Real-path probe: structure-first, takeover, live stream. Uses a test-owned native task and the work profile apps."""
import importlib.util, json, sys, time, uuid, socket, hashlib, secrets
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cowork_auth as ca

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("fixture_harness", ROOT / "scripts/acceptance/cowork-device-test.py")
h = importlib.util.module_from_spec(spec); spec.loader.exec_module(h)
TASK = "probe-structure-" + uuid.uuid4().hex[:6]
PKG = sys.argv[1] if len(sys.argv) > 1 else "com.kiwibrowser.browser"
OUT = ROOT / "test-evidence/cowork/structure"; OUT.mkdir(parents=True, exist_ok=True)
h.adb("forward", "tcp:13091", "tcp:3091")
agent, controller = h.secret("agent"), h.secret("controller")

def api(path, body=None, role="agent"):
    return ca.request(path, controller if role == "controller" else agent, body, role=role, port=13091)
def state(): return api("/v1/state", role="controller")
def tool(name, args):
    state()
    return api("/v1/tool", {"protocolVersion": 1, "requestId": str(uuid.uuid4()), "sessionId": TASK, "tool": name, "arguments": args})
def ok(r):
    if not r.get("ok"): raise RuntimeError(json.dumps(r.get("error")))
    return r["result"]

def stream_probe(seconds=4, writer=None):
    """Read the live view for N seconds; return frames, fps and first/last sizes."""
    role = "controller"; method, path = "GET", "/v1/stream"
    digest = hashlib.sha256((method + "\n" + path + "\n").encode()).hexdigest()
    cn = secrets.token_hex(32)
    body = json.dumps({"role": role, "clientNonce": cn, "digest": digest}, separators=(",", ":")).encode()
    deadline = time.monotonic() + 10
    st, hd, raw = ca._exchange("POST", "/v1/auth", body, None, 13091, deadline, 1024)
    ch = json.loads(raw)
    fields = "\n".join((role, cn, ch["serverNonce"], digest))
    auth = "Cowork-HMAC " + ":".join((role, cn, ch["serverNonce"], ca._mac(controller, "client\n" + fields)))
    s = socket.create_connection(("127.0.0.1", 13091), timeout=10)
    s.sendall((f"GET {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: {auth}\r\nAccept: */*\r\n\r\n").encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = s.recv(65536)
        if not chunk: raise RuntimeError("stream closed early: " + buf.decode("latin1"))
        buf += chunk
    head, buf = buf.split(b"\r\n\r\n", 1)
    headtext = head.decode("latin1")
    proof = [l.split(": ", 1)[1] for l in headtext.split("\r\n") if l.lower().startswith("x-cowork-proof")]
    expect = ca._mac(controller, "response\n" + fields + "\n200\n" + hashlib.sha256(b"").hexdigest())
    frames = []; start = time.monotonic(); first_at = None
    if writer:
        import threading; threading.Thread(target=writer, args=(s,), daemon=True).start()
    s.settimeout(3)
    while time.monotonic() - start < seconds:
        while len(buf) < 4: buf += s.recv(65536)
        n = int.from_bytes(buf[:4], "big"); buf = buf[4:]
        if n == 0: continue
        while len(buf) < n: buf += s.recv(65536)
        data, buf = buf[:n], buf[n:]
        frames.append((time.monotonic() - start, len(data), data[:4]))
        if len(frames) in (1, 10): (OUT / f"stream-{len(frames)}.bin").write_bytes(data)
    s.close()
    return {"head": headtext.split("\r\n")[0], "proof_ok": proof == [expect], "frames": len(frames),
            "fps": round(len(frames) / seconds, 1), "first_at": round(frames[0][0], 2) if frames else None,
            "sizes": [f[1] for f in frames[:3]], "times": [round(f[0],2) for f in frames], "magic": [f[2].hex() for f in frames[:3]]}

def main():
    out = {}
    cur = api("/v1/task-state"); state()
    api("/v1/task", {"operation": "begin", "taskId": TASK, "instanceId": cur["instanceId"], "revision": cur["revision"]})
    ids = None
    try:
        s = state(); ids = {"taskId": s["taskId"], "instanceId": s["instanceId"], "executionId": s["executionId"]}
        out["start"] = ok(tool("phone.start", {"packageName": PKG}))
        time.sleep(2)
        t = ok(tool("phone.ui_tree", {}))
        out["tree_summary"] = {"nodes": len(t["nodes"]), "complete": t["complete"], "advice": t["advice"]}
        (OUT / "tree.json").write_text(json.dumps(t, ensure_ascii=False, indent=1), encoding="utf-8")
        out["tree_sample"] = [{k: n[k] for k in ("id", "text", "description", "className", "clickable", "editable", "bounds")} for n in t["nodes"][:12]]
        cands = [n for n in t["nodes"] if n["clickable"]]
        if cands:
            out["tap_element"] = ok(tool("phone.tap", {"observationId": t["observationId"], "elementId": cands[0]["id"]})); out["tapped"] = cands[0]
        # takeover
        time.sleep(1)
        out["tree2_nodes"] = len(ok(tool("phone.ui_tree", {}))["nodes"])
        out["intervention"] = ok(tool("phone.request_intervention", {"reason": "probe"}))
        out["status_during"] = ok(tool("phone.intervention_status", {}))
        r = tool("phone.screenshot", {}); out["screenshot_during_takeover"] = r.get("error")
        out["state_during"] = {k: state().get(k) for k in ("state", "userControl", "interventionBy", "interventionReason")}
        out["stream"] = stream_probe(4)
        out["user_touch"] = api("/v1/user-input", {"instanceId": ids["instanceId"], "kind": "touch", "action": "down", "x": 360, "y": 640}, "controller")
        out["user_touch_up"] = api("/v1/user-input", {"instanceId": ids["instanceId"], "kind": "touch", "action": "up", "x": 360, "y": 640}, "controller")
        out["user_text"] = api("/v1/user-input", {"instanceId": ids["instanceId"], "kind": "text", "text": "ab"}, "controller")
        out["handback"] = {k: api("/v1/takeover", {"action": "end", "instanceId": ids["instanceId"]}, "controller").get(k) for k in ("state", "userControl")}
        out["status_after"] = ok(tool("phone.intervention_status", {}))
        shot = ok(tool("phone.screenshot", {})); out["screenshot_after"] = {"w": shot["width"], "h": shot["height"]}
        import base64; (OUT / "after.png").write_bytes(base64.b64decode(shot["base64"]))
    finally:
        try:
            s = state()
            api("/v1/task", {**ids, "operation": "end", "outcome": "completed"}) if ids else None
        except Exception as e:
            out["end_error"] = str(e)
    print(json.dumps(out, ensure_ascii=False, indent=1))
main()

