"""Measure live-view fps while the user drags on the owned display (takeover)."""
import importlib.util, json, sys, time, uuid, threading
from pathlib import Path
spec = importlib.util.spec_from_file_location("p", Path(__file__).resolve().parent / "probe-structure.py")
src = open(Path(__file__).resolve().parent / "probe-structure.py", encoding="utf-8").read().replace("\nmain()\n", "\n")
g = {"__name__": "probe", "__file__": str(Path(__file__).resolve().parent / "probe-structure.py"), "__builtins__": __builtins__}
sys.argv = [sys.argv[0], sys.argv[1] if len(sys.argv) > 1 else "com.kiwibrowser.browser"]
exec(compile(src, "probe-structure.py", "exec"), g)
api, tool, ok, state, stream_probe, TASK = g["api"], g["tool"], g["ok"], g["state"], g["stream_probe"], g["TASK"]
cur = api("/v1/task-state"); state()
api("/v1/task", {"operation": "begin", "taskId": TASK, "instanceId": cur["instanceId"], "revision": cur["revision"]})
s = state(); ids = {"taskId": s["taskId"], "instanceId": s["instanceId"], "executionId": s["executionId"]}
try:
    ok(tool("phone.start", {"packageName": sys.argv[1]})); time.sleep(2)
    api("/v1/takeover", {"action": "start", "instanceId": ids["instanceId"]}, "controller")
    result = {}
    def drag(sock):
        time.sleep(1.5)
        inst = ids["instanceId"]
        def u(a, y): sock.sendall((json.dumps({"kind": "touch", "action": a, "x": 360, "y": y}) + "\n").encode())
        u("down", 1000); t0 = time.monotonic(); n = 0
        for i in range(100):
            u("move", 1000 - i * 8); n += 1; time.sleep(0.016)
        u("up", 200); result["moves"] = n; result["drag_s"] = round(time.monotonic() - t0, 2)
    result["stream"] = stream_probe(5, drag)
    print(json.dumps(result))
finally:
    try: api("/v1/takeover", {"action": "end", "instanceId": ids["instanceId"]}, "controller")
    except Exception as e: print("handback", e)
    api("/v1/task", {**ids, "operation": "end", "outcome": "completed"})
