"""Fixed fixture-only broker acceptance harness. No arbitrary adb input, shell or app targets."""
import argparse, base64, json, subprocess, time, uuid
from pathlib import Path
from cowork_auth import request as authenticated_request
ADB=r"C:\Users\rangt\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
SERIAL="5b9a17cd"
ROOT=Path(__file__).resolve().parent
OUT=ROOT/"test-evidence/cowork"
SESSION="cowork-fixture-acceptance"
URL="http://127.0.0.1:13091"

def adb(*args):return subprocess.run([ADB,"-s",SERIAL,*args],check=True,stdout=subprocess.PIPE).stdout

def secret(which):
    token=adb("shell","su","-c",f"/system/bin/sh /data/local/dsh-native/cowork-service.sh {which}-token").decode().strip()
    if not (40<=len(token)<=256 and all(c.isalnum() or c in "_-" for c in token)):raise RuntimeError("Credential unavailable")
    return token

def request(path,token,value=None):
    role="controller" if path in {"/v1/state","/v1/control","/v1/apps","/v1/preview"} else "agent"
    return authenticated_request(path,token,value,role=role,port=13091,timeout=12)

def stop_owned(controller):
    state=request("/v1/state",controller)
    if state.get("displayId",-1)<=0:return state
    if state.get("taskId")!=SESSION:raise RuntimeError("Refusing to stop another session's workspace")
    return request("/v1/control",controller,{"action":"stop","sessionId":SESSION,
        "instanceId":state["instanceId"],"executionId":state["executionId"],"revision":state["revision"]})

def tool(token,name,args):
    return request("/v1/tool",token,{"protocolVersion":1,"requestId":str(uuid.uuid4()),"sessionId":SESSION,"tool":name,"arguments":args})

def main():
    p=argparse.ArgumentParser();p.add_argument("command",choices=["state","watch","start","stop","screenshot","tree","call"]);p.add_argument("--tool");p.add_argument("--args",default="{}");p.add_argument("--name",default="current")
    args=p.parse_args();OUT.mkdir(parents=True,exist_ok=True)
    adb("forward","tcp:13091","tcp:3091")
    controller=secret("controller")
    if args.command=="watch":
        stop=OUT/(args.name+"-watch.stop")
        if stop.exists():raise RuntimeError("Remove prior marker only before explicitly starting a NEW test run")
        try:
            while not stop.exists():request("/v1/state",controller);time.sleep(1)
        finally:
            stop_owned(controller)
        print("Acceptance monitor stopped; owned fixture display released.");return
    if args.command=="state":print(json.dumps(request("/v1/state",controller),ensure_ascii=False));return
    if args.command=="stop":
        print(json.dumps(stop_owned(controller)));(OUT/(args.name+"-watch.stop")).write_text("stop",encoding="utf-8");return
    agent=secret("agent")
    if args.command=="start":result=tool(agent,"phone.start",{"packageName":"dev.dsh.pocket.fixture"})
    elif args.command=="screenshot":result=tool(agent,"phone.screenshot",{})
    elif args.command=="tree":result=tool(agent,"phone.ui_tree",{})
    else:
        if args.tool not in {"phone.status","phone.apps","phone.tap","phone.long_press","phone.swipe","phone.scroll","phone.type_text","phone.key","phone.wait","phone.launch_app","phone.pause","phone.stop","phone.request_intervention"}:raise RuntimeError("Only fixed phone capabilities")
        value=json.loads(args.args)
        if args.tool=="phone.launch_app" and value.get("packageName")!="dev.dsh.pocket.fixture":raise RuntimeError("Fixture only")
        result=tool(agent,args.tool,value)
    if not args.name.replace("-","").replace("_","").isalnum():raise RuntimeError("Invalid evidence basename")
    if args.command=="screenshot" and result.get("ok"):
        raw=result["result"].pop("base64");image=OUT/(args.name+".png");image.write_bytes(base64.b64decode(raw));result["result"]["imagePath"]=str(image)
    (OUT/(args.name+".json")).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(result,ensure_ascii=False))

if __name__=="__main__":main()
