"""Native task/consent protocol acceptance only; no display, model, or arbitrary app.
Uses fixed device and a test-owned task. Never acknowledges a real DSH execution.
"""
import importlib.util
import json
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cowork_auth import request

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("fixture_harness", Path(__file__).resolve().parent / "cowork-device-test.py")
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
TASK = "cowork-lifecycle-acceptance"


def main():
    h.adb("forward", "--no-rebind", "tcp:13091", "tcp:3091") if not any(
        line.split() == [h.SERIAL, "tcp:13091", "tcp:3091"]
        for line in h.adb("forward", "--list").decode().splitlines()) else None
    agent, controller = h.secret("agent"), h.secret("controller")
    evidence, owned = [], None
    def api(path, body=None, role="agent"):
        return request(path, controller if role == "controller" else agent, body, role=role, port=13091)
    def check(name, condition):
        if not condition: raise AssertionError(name)
        evidence.append({"case": name, "result": "PASS"})
    def state(): return api("/v1/state", role="controller")
    def ids(s): return {k: s[k] for k in ("taskId", "instanceId", "executionId")}
    def task(s, operation, **fields): return api("/v1/task", {**ids(s), "operation": operation, **fields})
    def begin():
        current = api("/v1/task-state")
        return api("/v1/task", {"operation": "begin", "taskId": TASK, "instanceId": current["instanceId"], "revision": current["revision"]})
    def control(s, action, **fields):
        return api("/v1/control", {"sessionId": TASK, "instanceId": s["instanceId"], "executionId": s["executionId"], "revision": s["revision"], "action": action, **fields}, "controller")
    def denied(name, call):
        try: call()
        except RuntimeError: check(name, True)
        else: raise AssertionError(name + " was accepted")
    initial = state()
    check("precondition no active native task or display", initial["displayId"] == -1 and initial["state"] in ("idle", "stopped", "completed", "error"))
    check("consent defaults off", initial.get("summaryEnabled") is False)
    try:
        owned = begin()
        check("begin without phone.start admits whole task", owned["state"] == "running" and state()["displayId"] == -1)
        check("island status binds task execution", state()["executionId"] == owned["executionId"] and state()["taskId"] == TASK)
        denied("duplicate begin rejected", begin)
        check("poll leaves revision stable", task(owned, "poll")["revision"] == owned["revision"])
        disabled = api("/v1/summary-context?taskId=" + TASK)
        check("summary disabled refuses generation context", disabled.get("ok") is False and disabled["error"]["code"] == "STALE_SUMMARY")
        # Synthetic display-only text: no provider is called or given data by this test.
        api("/v1/summary-preference", {"instanceId": owned["instanceId"], "enabled": True}, "controller")
        context = api("/v1/summary-context?taskId=" + TASK)
        check("opt-in context exact identity", set(context) == {"taskId", "revision", "instanceId", "executionId", "consentId"} and context["executionId"] == owned["executionId"])
        value = {**context, "sequence": 1, "text": "原生协议验收：未调用摘要模型"}
        check("synthetic summary accepted", api("/v1/summary", value) == {"ok": True})
        check("duplicate summary sequence rejected", api("/v1/summary", value)["error"]["code"] == "STALE_SUMMARY")
        api("/v1/summary-preference", {"instanceId": owned["instanceId"], "enabled": False}, "controller")
        check("disable clears stored summary", state()["summary"] == {})
        check("revoked consent rejects late output", api("/v1/summary", {**value, "sequence": 2})["error"]["code"] == "STALE_SUMMARY")
        paused = control(owned, "pause")
        check("pause awaits host settlement", paused["state"] == "pausing")
        pause_cmd = task(owned, "poll")
        stopped = control(paused, "stop")
        check("stop upgrades pause", stopped["state"] == "stopping")
        stop_cmd = task(owned, "poll")
        check("superseded pause ACK is unchanged stop snapshot", task(owned, "ack", commandId=pause_cmd["commandId"]) == stop_cmd)
        denied("arbitrary command ACK rejected", lambda: task(owned, "ack", commandId="unknown"))
        settled = task(owned, "ack", commandId=stop_cmd["commandId"])
        check("exact stop ACK reaches stopped", settled["state"] == "stopped" and settled["command"] == "")
        denied("settled command cannot be replayed", lambda: task(owned, "ack", commandId=stop_cmd["commandId"]))
        previous = owned
        owned = begin()
        check("new user turn has fresh execution", owned["executionId"] != previous["executionId"])
        denied("old execution cannot stop replacement", lambda: control(previous, "stop"))
        completed = task(owned, "end", outcome="completed")
        check("explicit completion preserves identity", completed["state"] == "completed" and state()["taskId"] == TASK)
        owned = begin()
        command = control(owned, "pause")
        race = task(owned, "end", outcome="completed")
        check("command wins final completion race", race["state"] == "pausing" and race["command"] == "pause")
        settled = task(owned, "ack", commandId=race["commandId"])
        check("pause settles separately from completion", settled["state"] == "paused")
        control(settled, "stop")
        owned = None
    finally:
        current = state()
        if current.get("taskId") == TASK:
            api("/v1/summary-preference", {"instanceId": current["instanceId"], "enabled": False}, "controller")
            if current["state"] not in ("stopped", "completed", "error"):
                control(current, "stop")
                pending = api("/v1/task-state")
                if pending["command"] == "stop": task(pending, "ack", commandId=pending["commandId"])
        final = state()
        check("teardown no display and consent off", final["displayId"] == -1 and final["summaryEnabled"] is False and final["state"] in ("stopped", "completed", "error"))
        report = {"kind": "real-device-native-protocol", "modelCalled": False, "displayStarted": False, "cases": evidence, "final": {k: final[k] for k in ("state", "displayId", "summaryEnabled")}}
        (ROOT / "test-evidence/cowork/native-task-lifecycle.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"passed": len(evidence), "modelCalled": False, "displayStarted": False, "finalState": final["state"]}))

if __name__ == "__main__": main()
