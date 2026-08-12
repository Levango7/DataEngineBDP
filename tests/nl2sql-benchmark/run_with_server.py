"""启动 NL2SQL 服务 → 运行 HTTP 评测 → 关闭服务.

确保 HTTP 模式评测可在单次脚本执行内完成。
"""
from __future__ import annotations

import os
import signal
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
NL2SQL_DIR = HERE.parents[1] / "platform" / "nl2sql"

env = os.environ.copy()
env["NL2SQL_LLM_MODE"] = "mock"
env["NL2SQL_HOST"] = "127.0.0.1"
env["NL2SQL_PORT"] = "8093"
env["PYTHONPATH"] = str(NL2SQL_DIR) + os.pathsep + env.get("PYTHONPATH", "")

print("[runner] 启动 NL2SQL 服务 ...")
proc = subprocess.Popen(
    [sys.executable, "app.py"],
    cwd=str(NL2SQL_DIR),
    env=env,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
)

try:
    # 等待服务就绪
    import urllib.request
    ready = False
    for _ in range(30):
        time.sleep(0.5)
        try:
            r = urllib.request.urlopen("http://127.0.0.1:8093/api/v1/health", timeout=2)
            if r.status == 200:
                ready = True
                print("[runner] 服务就绪 (health 200)")
                break
        except Exception:
            continue
    if not ready:
        print("[runner] 服务未就绪，读取日志:")
        out = proc.stdout.read(4000) if proc.stdout else ""
        print(out)
        sys.exit(3)

    # 运行评测
    print("[runner] 运行 HTTP 模式评测 ...")
    ret = subprocess.call(
        [sys.executable, str(HERE / "run_benchmark.py"),
         "--host", "127.0.0.1", "--port", "8093",
         "--report", "accuracy_report.md"],
        cwd=str(HERE),
        env=env,
    )
    print(f"[runner] 评测退出码 {ret}")
    sys.exit(ret)
finally:
    print("[runner] 关闭 NL2SQL 服务 ...")
    try:
        proc.send_signal(signal.SIGTERM)
        proc.wait(timeout=5)
    except Exception:
        proc.kill()