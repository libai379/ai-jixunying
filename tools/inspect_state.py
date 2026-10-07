"""看一份 state.json 里的服务商、成员、会话，Key 只显示指纹（不打印 Key 本身）。

用法：
  python -I -X utf8 tools/inspect_state.py %APPDATA%/ai-jixunying/state.json
  python -I -X utf8 tools/inspect_state.py 备份.tar        （手机数据：adb exec-out run-as com.guixing.jixunying tar cf - files/data > 备份.tar）

两个服务商指纹一样就是同一个 Key。
"""
import hashlib
import json
import sys
import tarfile


def load(path):
    if path.endswith(".tar"):
        with tarfile.open(path) as t:
            return json.load(t.extractfile("files/data/state.json"))
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def fp(key):
    key = (key or "").strip()
    if not key:
        return "（空）"
    return "指纹 " + hashlib.sha256(key.encode()).hexdigest()[:6] + f"，{len(key)} 位"


s = load(sys.argv[1])
print("== 服务商 ==")
for p in s.get("providers", []):
    models = [m.get("id") for m in p.get("models", [])]
    print(f"  {p.get('id')} | {p.get('name')} | {p.get('baseUrl')} | {fp(p.get('apiKey'))} | {models}")
print("== 成员 ==")
for m in s.get("members", []):
    print(f"  {m.get('id')} | {m.get('name')} | {m.get('modelId')} | 服务商 {m.get('providerId')} | 定位：{m.get('bio', '')}")
print("== 会话 ==")
for c in s.get("conversations", []):
    print(f"  {c.get('id')} | {c.get('title')} | 成员 {c.get('memberIds')}")
