#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""带外管理（BMC / IPMI / Redfish）验收取证器 —— 对应台账 #121 的硬件面。

为什么需要它
------------
台账 #121 处置列原文是「一台 x86_64 真机 + BMC/PXE（主理人侧待办）**或**可复现的 CI 集群」。
后半条（可复现 CI 集群）已由本机 WSL2 + k3s 单节点在 **L0 底座档**兑现（台账 #124 /
`docs/CLOUD-NATIVE-PERF-LOSS-BASELINE.md` §6.4）；前半条（真机 + 带外管理）**本机硬件不可达**
（本机为笔记本，`Win32_IPMIConnection` 类不存在 ⇒ 无 IPMI/BMC）。

本脚本把「带外到底能不能用」从口头判断变成**可执行判据 + 可留存证据**：

- **三态判据**：PASS / FAIL / **INCONCLUSIVE**。拿不到结论**不算通过**（退出码 3）。
- **只读探测**：Redfish（HTTP/HTTPS）+ IPMI over LAN（经 `ipmitool`）。
  远程开关机这类**写操作不由脚本自动执行**，只打印命令交人工执行，避免误动生产机。
- **架构证据单独成条**：BMC 侧**不提供** CPU 架构 ⇒ 该条必须由 OS 侧（`uname -m`）补证，
  脚本用 `--os-arch` 接收并单独标注来源，不与 BMC 证据混为一谈。
- 输出 **JSON 证据**（含原始响应片段、密码一律掩码），可直接归档进台账 #121 复核段。

用法
----
    # 1) 先跑离线自测（不碰网络，证明判据本身会抓红）
    python3 scripts/baremetal/ipmi_acceptance.py --self-test

    # 2) 真检（密码从 stdin 读，避免进 shell 历史）
    printf '%s' "$BMC_PASS" | python3 scripts/baremetal/ipmi_acceptance.py \
        --redfish https://bmc.example.com \
        --ipmi-host 10.0.0.11 \
        --user ipmi-user --password-stdin \
        --ipmi-user ipmi-user --ipmi-password-stdin \
        --os-arch x86_64 \
        --out evidence/bmc-acceptance.json

退出码
------
    0 = 全部判据 PASS（无 FAIL、无 INCONCLUSIVE）
    2 = 存在 FAIL（有确定结论的失败）
    3 = 无 FAIL，但存在 INCONCLUSIVE（**没结论**，不得当通过）
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import ssl
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

VERDICT_PASS = "PASS"
VERDICT_FAIL = "FAIL"
VERDICT_INCONCLUSIVE = "INCONCLUSIVE"

# IPMI over LAN 的只读命令。写操作（power off/on/cycle）刻意不在这里 —— 交人工执行。
IPMI_READONLY_COMMANDS = (
    ("chassis_info", ["chassis", "info"]),
    ("chassis_power_status", ["chassis", "power", "status"]),
    ("sensor_list", ["sensor", "list"]),
    ("lan_print", ["lan", "print"]),
)

# urllib 支持 file:// 等非 HTTP scheme；若不设白名单，一个拼错的地址就可能变成「读本地文件」的入口。
# 取证器只该访问 BMC 的 Redfish 服务 ⇒ 只放行 http/https。
ALLOWED_URL_SCHEMES = ("http", "https")


def _url_scheme(url: str) -> str:
    return urllib.parse.urlsplit(url).scheme.lower()


def _scheme_allowed(url: str) -> bool:
    return _url_scheme(url) in ALLOWED_URL_SCHEMES


def _mask(text: str, secrets) -> str:
    """把密码之类的字面量从证据文本里抹掉，避免证据文件泄密。"""
    if not text:
        return ""
    out = text
    for s in secrets:
        if s:
            out = out.replace(s, "***")
    return out


# --------------------------------------------------------------------------
# Redfish 探测（纯标准库）
# --------------------------------------------------------------------------
def _redfish_get(base_url: str, path: str, user: str, password: str,
                  timeout: float, insecure: bool):
    """GET 一个 Redfish 端点，返回 {"status", "body", "error"}，不抛异常。"""
    if not _scheme_allowed(base_url):
        return {"status": None, "body": "",
                "error": "不支持的 URL scheme: %r（只允许 %s）"
                         % (_url_scheme(base_url), "/".join(ALLOWED_URL_SCHEMES)),
                "url": base_url, "auth": bool(user)}
    url = base_url.rstrip("/") + path
    headers = {"Accept": "application/json"}
    if user:
        token = base64.b64encode(("%s:%s" % (user, password)).encode("utf-8")).decode("ascii")
        headers["Authorization"] = "Basic " + token
    ctx = None
    if insecure:
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE  # BMC 自签证书是常态，由 --insecure 显式开关
    req = urllib.request.Request(url, headers=headers, method="GET")
    try:
        # 核实依据：已加固：_redfish_get 入口只放行 http/https（file:// 等 scheme 在发起请求前即被拒），
        # 且 URL 来自运维显式传入的 --redfish（非终端用户输入）；剩余风险仅为误配地址打到非预期主机。
        # nosemgrep: python.lang.security.audit.dynamic-urllib-use-detected.dynamic-urllib-use-detected
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            body = resp.read(65536).decode("utf-8", errors="replace")
            return {"status": resp.status, "body": body, "error": None,
                    "url": url, "auth": bool(user)}
    except urllib.error.HTTPError as exc:
        body = exc.read(4096).decode("utf-8", errors="replace")
        return {"status": exc.code, "body": body, "error": "HTTP %d" % exc.code,
                "url": url, "auth": bool(user)}
    except Exception as exc:  # noqa: BLE001 - 连接层异常统一记成 error，不让脚本崩
        return {"status": None, "body": "", "error": "%s: %s" % (type(exc).__name__, exc),
                "url": url, "auth": bool(user)}


def _json_or_none(raw: str):
    if not raw:
        return None
    try:
        return json.loads(raw)
    except (ValueError, TypeError):
        return None


def probe_redfish(base_url, user, password, timeout, insecure, secrets):
    """探测 Redfish 服务根 / Systems / Chassis / Thermal / Managers。"""
    out = {"probed": False, "endpoints": {}, "reason": None}
    if not base_url:
        out["reason"] = "未提供 --redfish 地址"
        return out
    if not _scheme_allowed(base_url):
        out["reason"] = "Redfish 地址 scheme 不支持：%r（只允许 %s）" % (
            _url_scheme(base_url), "/".join(ALLOWED_URL_SCHEMES))
        return out
    out["probed"] = True
    root = _redfish_get(base_url, "/redfish/v1/", user, password, timeout, insecure)
    out["endpoints"]["service_root"] = {
        "status": root["status"], "error": root["error"],
        "body": _mask(root["body"][:2000], secrets),
    }
    if root["status"] != 200:
        out["reason"] = "ServiceRoot 不可达（status=%s error=%s）" % (root["status"], root["error"])
        return out
    root_json = _json_or_none(root["body"]) or {}

    systems = _redfish_get(base_url, "/redfish/v1/Systems", user, password, timeout, insecure)
    systems_json = _json_or_none(systems["body"]) or {}
    members = systems_json.get("Members") or []
    system = members[0].get("@odata.id") if members else None
    out["endpoints"]["systems"] = {
        "status": systems["status"], "error": systems["error"],
        "member": system,
        "body": _mask(systems["body"][:2000], secrets),
    }
    if system:
        sys_detail = _redfish_get(base_url, system, user, password, timeout, insecure)
        sj = _json_or_none(sys_detail["body"]) or {}
        out["endpoints"]["system_detail"] = {
            "status": sys_detail["status"], "error": sys_detail["error"],
            "power_state": (sj.get("PowerState") or ""),
            "model": sj.get("Model") or "",
            "serial": sj.get("SerialNumber") or "",
            "cpu_architecture": sj.get("ProcessorArchitecture") or "",
            "body": _mask(sys_detail["body"][:2000], secrets),
        }

    chassis = _redfish_get(base_url, "/redfish/v1/Chassis", user, password, timeout, insecure)
    chassis_json = _json_or_none(chassis["body"]) or {}
    cmembers = chassis_json.get("Members") or []
    chassis_member = cmembers[0].get("@odata.id") if cmembers else None
    out["endpoints"]["chassis"] = {
        "status": chassis["status"], "error": chassis["error"],
        "member": chassis_member,
        "body": _mask(chassis["body"][:2000], secrets),
    }
    if chassis_member:
        detail = _redfish_get(base_url, chassis_member, user, password, timeout, insecure)
        dj = _json_or_none(detail["body"]) or {}
        out["endpoints"]["chassis_detail"] = {
            "status": detail["status"], "error": detail["error"],
            "manufacturer": dj.get("Manufacturer") or "",
            "model": dj.get("Model") or "",
            "serial": dj.get("SerialNumber") or "",
            "part_number": dj.get("PartNumber") or "",
            "power_state": (dj.get("PowerState") or ""),
            "body": _mask(detail["body"][:2000], secrets),
        }
        thermal = _redfish_get(base_url, chassis_member.rstrip("/") + "/Thermal",
                               user, password, timeout, insecure, secrets)
        tj = _json_or_none(thermal["body"]) or {}
        readings = tj.get("Temperatures") or []
        out["endpoints"]["thermal"] = {
            "status": thermal["status"], "error": thermal["error"],
            "reading_count": len(readings),
            "body": _mask(thermal["body"][:2000], secrets),
        }

    managers = _redfish_get(base_url, "/redfish/v1/Managers", user, password, timeout, insecure)
    mj = _json_or_none(managers["body"]) or {}
    mmembers = mj.get("Members") or []
    manager_member = mmembers[0].get("@odata.id") if mmembers else None
    out["endpoints"]["managers"] = {
        "status": managers["status"], "error": managers["error"],
        "member": manager_member,
        "body": _mask(managers["body"][:2000], secrets),
    }
    if manager_member:
        mdetail = _redfish_get(base_url, manager_member, user, password, timeout, insecure)
        mdj = _json_or_none(mdetail["body"]) or {}
        out["endpoints"]["manager_detail"] = {
            "status": mdetail["status"], "error": mdetail["error"],
            "model": mdj.get("Model") or "",
            "firmware": mdj.get("FirmwareVersion") or "",
            "body": _mask(mdetail["body"][:2000], secrets),
        }
    return out


# --------------------------------------------------------------------------
# IPMI over LAN 探测（经 ipmitool）
# --------------------------------------------------------------------------
def probe_ipmi(host, user, password, timeout, secrets):
    out = {"probed": False, "tool": None, "commands": {}, "reason": None}
    if not host:
        out["reason"] = "未提供 --ipmi-host"
        return out
    exe = shutil.which("ipmitool")
    if not exe:
        out["reason"] = "未找到 ipmitool（PATH 内无该可执行文件）⇒ IPMI 侧未探测"
        return out
    out["probed"] = True
    out["tool"] = exe
    base = [exe, "-I", "lanplus", "-H", host, "-U", user]
    if password:
        base += ["-P", password]
    for name, args in IPMI_READONLY_COMMANDS:
        cmd = base + args
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True,
                                  encoding="utf-8", errors="replace", timeout=timeout)
            out["commands"][name] = {
                "rc": proc.returncode,
                "stdout": _mask(proc.stdout[:4000], secrets),
                "stderr": _mask(proc.stderr[:1000], secrets),
            }
        except subprocess.TimeoutExpired:
            out["commands"][name] = {"rc": None, "stdout": "", "stderr": "timeout"}
        except Exception as exc:  # noqa: BLE001
            out["commands"][name] = {"rc": None, "stdout": "",
                                     "stderr": "%s: %s" % (type(exc).__name__, exc)}
    return out


# --------------------------------------------------------------------------
# 判据评估（纯函数，便于离线自测）
# --------------------------------------------------------------------------
def _ipmi_ok(ipmi, name):
    c = (ipmi.get("commands") or {}).get(name) or {}
    return c.get("rc") == 0


def _ipmi_text(ipmi, name):
    c = (ipmi.get("commands") or {}).get(name) or {}
    return (c.get("stdout") or "") + "\n" + (c.get("stderr") or "")


def evaluate(redfish, ipmi, os_arch=None):
    """把探测结果折成六条硬判据，每条给 PASS / FAIL / INCONCLUSIVE + 依据。"""
    criteria = []

    # C1 带外管理通道可达
    rf_root = (redfish.get("endpoints") or {}).get("service_root") or {}
    rf_ok = rf_root.get("status") == 200
    ipmi_ok = _ipmi_ok(ipmi, "chassis_info")
    probed_any = redfish.get("probed") or ipmi.get("probed")
    if rf_ok or ipmi_ok:
        verdict, why = VERDICT_PASS, "Redfish ServiceRoot 200" if rf_ok else "ipmitool chassis info rc=0"
    elif probed_any:
        verdict = VERDICT_FAIL
        why = "Redfish status=%s error=%s；ipmitool chassis info rc=%s" % (
            rf_root.get("status"), rf_root.get("error"),
            ((ipmi.get("commands") or {}).get("chassis_info") or {}).get("rc"))
    else:
        verdict = VERDICT_FAIL
        why = "既未提供 Redfish 地址也无可用 ipmitool ⇒ 无法证明带外可用"
    criteria.append({"id": "C1", "title": "带外管理通道可达（Redfish 或 IPMI）",
                     "verdict": verdict, "why": why})

    # C2 电源状态可读（只读；写操作交人工）
    sysd = (redfish.get("endpoints") or {}).get("system_detail") or {}
    chd = (redfish.get("endpoints") or {}).get("chassis_detail") or {}
    power_state = sysd.get("power_state") or chd.get("power_state") or ""
    power_readable = bool(power_state) or _ipmi_ok(ipmi, "chassis_power_status")
    power_probed = bool(sysd) or bool(chd) or ipmi.get("probed")
    if power_readable:
        verdict = VERDICT_PASS
        why = "电源状态可读（Redfish=%s / ipmitool chassis power status rc=0）" % (power_state or "n/a")
    elif power_probed:
        verdict, why = VERDICT_FAIL, "探测过电源面但拿不到状态"
    else:
        verdict, why = VERDICT_INCONCLUSIVE, "未探测电源面"
    criteria.append({"id": "C2", "title": "电源状态可读（远程开关机命令见脚本尾部提示）",
                     "verdict": verdict, "why": why})

    # C3 机箱身份可读（厂商 / 型号 / 序列号）
    serial = chd.get("serial") or sysd.get("serial") or ""
    model = chd.get("model") or sysd.get("model") or ""
    manufacturer = chd.get("manufacturer") or ""
    identity_probed = bool(chd) or bool(sysd) or ipmi.get("probed")
    if serial and (model or manufacturer):
        verdict = VERDICT_PASS
        why = "厂商=%s 型号=%s 序列号=%s" % (manufacturer or "n/a", model or "n/a", serial)
    elif identity_probed:
        verdict, why = VERDICT_FAIL, "探测过机箱身份面但序列号/型号为空"
    else:
        verdict, why = VERDICT_INCONCLUSIVE, "未探测机箱身份面"
    criteria.append({"id": "C3", "title": "机箱身份可读（厂商 / 型号 / 序列号）",
                     "verdict": verdict, "why": why})

    # C4 传感器 / 温度可读
    thermal = (redfish.get("endpoints") or {}).get("thermal") or {}
    sensor_text = _ipmi_text(ipmi, "sensor_list")
    ipmi_sensor_count = 0
    if _ipmi_ok(ipmi, "sensor_list"):
        ipmi_sensor_count = max(0, len([l for l in sensor_text.splitlines() if l.strip()]))
    rf_reading_count = thermal.get("reading_count") or 0
    sensors_probed = bool(thermal) or ipmi.get("probed")
    if rf_reading_count > 0 or ipmi_sensor_count > 0:
        verdict = VERDICT_PASS
        why = "Redfish 温度读数 %d 条 / ipmitool 传感器 %d 行" % (rf_reading_count, ipmi_sensor_count)
    elif sensors_probed:
        verdict, why = VERDICT_FAIL, "探测过传感器面但读数为 0"
    else:
        verdict, why = VERDICT_INCONCLUSIVE, "未探测传感器面"
    criteria.append({"id": "C4", "title": "传感器 / 温度可读", "verdict": verdict, "why": why})

    # C5 带外网络参数可读（证明带外网与业务网分离）
    lan_text = _ipmi_text(ipmi, "lan_print")
    lan_has_ip = bool(lan_text) and ("IP Address" in lan_text or "IP Address Source" in lan_text)
    mgr = (redfish.get("endpoints") or {}).get("manager_detail") or {}
    net_probed = ipmi.get("probed") or bool(mgr)
    if lan_has_ip or (mgr and mgr.get("model")):
        verdict = VERDICT_PASS
        why = "ipmitool lan print 有 IP 字段" if lan_has_ip else "Redfish Managers 可读（型号=%s）" % mgr.get("model")
    elif net_probed:
        verdict, why = VERDICT_FAIL, "探测过带外网络面但拿不到 IP / 管理口信息"
    else:
        verdict, why = VERDICT_INCONCLUSIVE, "未探测带外网络面"
    criteria.append({"id": "C5", "title": "带外网络参数可读（带外网独立于业务网）",
                     "verdict": verdict, "why": why})

    # C6 CPU 架构证据（BMC 侧不提供 ⇒ 必须 OS 侧补证）
    if os_arch:
        verdict = VERDICT_PASS
        why = "架构由 **OS 侧** 自报（--os-arch=%s），非 BMC 侧证据" % os_arch
    else:
        verdict = VERDICT_INCONCLUSIVE
        why = ("BMC/IPMI/Redfish **不提供** CPU 架构；须在目标机执行 `uname -m` 并用 --os-arch 传入，"
               "否则「x86_64 真机」这一条无法由带外证据独立成立")
    criteria.append({"id": "C6", "title": "CPU 架构证据（须 OS 侧补证，非 BMC 侧）",
                     "verdict": verdict, "why": why})

    return criteria


def overall_verdict(criteria):
    verdicts = [c["verdict"] for c in criteria]
    if VERDICT_FAIL in verdicts:
        return VERDICT_FAIL
    if VERDICT_INCONCLUSIVE in verdicts:
        return VERDICT_INCONCLUSIVE
    return VERDICT_PASS


def exit_code_for(overall):
    if overall == VERDICT_PASS:
        return 0
    if overall == VERDICT_FAIL:
        return 2
    return 3


# --------------------------------------------------------------------------
# 离线自测：用桩证据证明判据会抓红、且 INCONCLUSIVE 不算通过
# --------------------------------------------------------------------------
def _stub_good():
    return (
        {"probed": True, "endpoints": {
            "service_root": {"status": 200, "error": None, "body": "{}"},
            "system_detail": {"status": 200, "error": None, "power_state": "On", "model": "R650",
                              "serial": "SN123", "cpu_architecture": "x86_64", "body": "{}"},
            "chassis_detail": {"status": 200, "error": None, "manufacturer": "Dell", "model": "R650",
                               "serial": "SN123", "part_number": "P1", "power_state": "On", "body": "{}"},
            "thermal": {"status": 200, "error": None, "reading_count": 4, "body": "{}"},
            "manager_detail": {"status": 200, "error": None, "model": "iDRAC9", "firmware": "5.0", "body": "{}"},
        }, "reason": None},
        {"probed": True, "tool": "/usr/bin/ipmitool", "commands": {
            "chassis_info": {"rc": 0, "stdout": "Chassis Power is on", "stderr": ""},
            "chassis_power_status": {"rc": 0, "stdout": "Chassis Power is on", "stderr": ""},
            "sensor_list": {"rc": 0, "stdout": "CPU1 Temp | 40C | ok\nFAN1 | 5000 RPM | ok", "stderr": ""},
            "lan_print": {"rc": 0, "stdout": "IP Address : 10.0.0.11", "stderr": ""},
        }, "reason": None},
    )


def _stub_unreachable():
    return (
        {"probed": True, "endpoints": {"service_root": {"status": 401, "error": "HTTP 401", "body": ""}},
         "reason": "ServiceRoot 不可达"},
        {"probed": True, "tool": "/usr/bin/ipmitool", "commands": {
            "chassis_info": {"rc": 1, "stdout": "", "stderr": "Error: Unable to establish IPMI v2 / RMCP+ session"},
        }, "reason": None},
    )


def _stub_no_tools():
    return (
        {"probed": False, "endpoints": {}, "reason": "未提供 --redfish 地址"},
        {"probed": False, "tool": None, "commands": {}, "reason": "未找到 ipmitool"},
    )


def self_test():
    """四组桩：全通 / 不可达 / 无工具 / 传感器为空。任一不符即红。"""
    failures = []

    rf, ipmi = _stub_good()
    cs = evaluate(rf, ipmi, os_arch="x86_64")
    by_id = {c["id"]: c for c in cs}
    if by_id["C1"]["verdict"] != VERDICT_PASS:
        failures.append("全通桩：C1 应 PASS，实为 %s" % by_id["C1"]["verdict"])
    for cid in ("C2", "C3", "C4", "C5"):
        if by_id[cid]["verdict"] != VERDICT_PASS:
            failures.append("全通桩：%s 应 PASS，实为 %s" % (cid, by_id[cid]["verdict"]))
    if by_id["C6"]["verdict"] != VERDICT_PASS:
        failures.append("全通桩：C6（带 --os-arch）应 PASS，实为 %s" % by_id["C6"]["verdict"])
    if overall_verdict(cs) != VERDICT_PASS:
        failures.append("全通桩：总判据应 PASS")

    rf, ipmi = _stub_good()
    cs = evaluate(rf, ipmi, os_arch=None)
    by_id = {c["id"]: c for c in cs}
    if by_id["C6"]["verdict"] != VERDICT_INCONCLUSIVE:
        failures.append("缺 OS 侧架构：C6 应 INCONCLUSIVE，实为 %s" % by_id["C6"]["verdict"])
    if overall_verdict(cs) != VERDICT_INCONCLUSIVE:
        failures.append("缺 OS 侧架构：总判据应 INCONCLUSIVE（没结论不得当通过）")
    if exit_code_for(overall_verdict(cs)) != 3:
        failures.append("缺 OS 侧架构：退出码应为 3")

    rf, ipmi = _stub_unreachable()
    cs = evaluate(rf, ipmi, os_arch="x86_64")
    by_id = {c["id"]: c for c in cs}
    if by_id["C1"]["verdict"] != VERDICT_FAIL:
        failures.append("不可达桩：C1 应 FAIL，实为 %s" % by_id["C1"]["verdict"])
    if overall_verdict(cs) != VERDICT_FAIL:
        failures.append("不可达桩：总判据应 FAIL")
    if exit_code_for(overall_verdict(cs)) != 2:
        failures.append("不可达桩：退出码应为 2")

    rf, ipmi = _stub_no_tools()
    cs = evaluate(rf, ipmi, os_arch=None)
    by_id = {c["id"]: c for c in cs}
    for cid in ("C2", "C4", "C5"):
        if by_id[cid]["verdict"] != VERDICT_INCONCLUSIVE:
            failures.append("无工具桩：%s 应 INCONCLUSIVE，实为 %s" % (cid, by_id[cid]["verdict"]))
    if overall_verdict(cs) != VERDICT_FAIL:
        failures.append("无工具桩：总判据应 FAIL（既无法证明可达 ⇒ 判红）")

    # 变异校验：把传感器读数清零，C4 必须由 PASS 转 FAIL（证明判据抓得住红）
    rf, ipmi = _stub_good()
    rf["endpoints"]["thermal"]["reading_count"] = 0
    ipmi["commands"]["sensor_list"] = {"rc": 0, "stdout": "", "stderr": ""}
    cs = evaluate(rf, ipmi, os_arch="x86_64")
    by_id = {c["id"]: c for c in cs}
    if by_id["C4"]["verdict"] != VERDICT_FAIL:
        failures.append("变异桩（传感器清零）：C4 应 FAIL，实为 %s" % by_id["C4"]["verdict"])

    # URL scheme 白名单：urllib 支持 file:// 等 scheme，若放行则可能被当成本地文件读取入口
    if not _scheme_allowed("https://bmc.example.com"):
        failures.append("scheme 白名单：https 应放行")
    for bad in ("file:///etc/passwd", "ftp://bmc.example.com", "gopher://bmc.example.com"):
        if _scheme_allowed(bad):
            failures.append("scheme 白名单：%s 应被拒" % bad)
    rf_bad = probe_redfish("file:///etc/passwd", "u", "p", 1.0, False, [])
    if rf_bad.get("probed"):
        failures.append("scheme 白名单：file:// 地址不应进入探测态")
    if "scheme" not in (rf_bad.get("reason") or ""):
        failures.append("scheme 白名单：file:// 地址应给出 scheme 拒绝理由（实为 %r）" % rf_bad.get("reason"))
    got = _redfish_get("file:///etc/passwd", "/redfish/v1/", "", "", 1.0, False)
    if got.get("status") is not None or "scheme" not in (got.get("error") or ""):
        failures.append("scheme 白名单：_redfish_get 对 file:// 必须拒绝且给出 scheme 理由（实为 %r）" % got)
    # 变异校验：把 urlopen 换成记账桩，file:// 地址必须一次都不触发 urlopen（证明拒绝发生在发起请求之前）
    calls = []
    real_urlopen = urllib.request.urlopen

    def _spy(*args, **kwargs):
        calls.append((args, kwargs))
        raise AssertionError("urlopen 不应被调用")

    urllib.request.urlopen = _spy
    try:
        _redfish_get("file:///etc/passwd", "/redfish/v1/", "", "", 1.0, False)
    finally:
        urllib.request.urlopen = real_urlopen
    if calls:
        failures.append("变异校验：file:// 地址不应触发 urlopen（实为 %d 次）" % len(calls))

    for f in failures:
        print("SELF-TEST FAIL: %s" % f)
    print("self-test: %s（%d 项断言）" % ("OK" if not failures else "FAIL", 5 + 3 + 3 + 3 + 1 + 8))
    return 0 if not failures else 1


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
def _read_secret(args_value, stdin_flag):
    if stdin_flag:
        return sys.stdin.readline().rstrip("\n")
    return args_value or ""


def _read_secrets(args):
    """取 Redfish / ipmitool 两个密码。

    两种 stdin 形态都要正确：
      - 只有一路要 stdin          → 读一行，给那一路；
      - 两路都要 stdin（常见用法） → 优先读两行（第 1 行 Redfish、第 2 行 ipmitool），
        但 stdin 只有一行时**把这一行同时给两路** —— 否则第二路会拿到空串，
        ipmitool 必然认证失败，结论会被误读成「带外不可用」。
    """
    rf_password = _read_secret(args.password, args.password_stdin)
    ipmi_password = _read_secret(args.ipmi_password, args.ipmi_password_stdin)
    if args.password_stdin and args.ipmi_password_stdin:
        # _read_secret 已各读走一行；stdin 只有一行时第二路拿到空串，
        # ipmitool 必然认证失败，结论会被误读成「带外不可用」⇒ 把同一行补给第二路。
        if not ipmi_password and rf_password:
            ipmi_password = rf_password
    return rf_password, ipmi_password


def main(argv=None):
    ap = argparse.ArgumentParser(description="BMC/IPMI/Redfish 带外管理验收取证器")
    ap.add_argument("--self-test", action="store_true", help="跑离线自测（不碰网络）")
    ap.add_argument("--redfish", default="", help="Redfish 根地址，如 https://bmc.example.com")
    ap.add_argument("--ipmi-host", default="", help="IPMI over LAN 的 BMC 地址")
    ap.add_argument("--user", default="", help="Redfish 用户名")
    ap.add_argument("--password", default="", help="Redfish 密码（优先用 --password-stdin）")
    ap.add_argument("--password-stdin", action="store_true", help="从 stdin 读 Redfish 密码")
    ap.add_argument("--ipmi-user", default="", help="ipmitool 用户名")
    ap.add_argument("--ipmi-password", default="", help="ipmitool 密码（优先用 --ipmi-password-stdin）")
    ap.add_argument("--ipmi-password-stdin", action="store_true", help="从 stdin 读 ipmitool 密码")
    ap.add_argument("--os-arch", default="", help="目标机 OS 侧自报架构（如 x86_64，取自 uname -m）")
    ap.add_argument("--insecure", action="store_true", help="跳过 TLS 证书校验（BMC 自签证书常见）")
    ap.add_argument("--timeout", type=float, default=10.0, help="单次探测超时秒数（默认 10）")
    ap.add_argument("--out", default="", help="证据 JSON 输出路径（默认打印到 stdout）")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    rf_password = _read_secret(args.password, args.password_stdin)
    ipmi_password = _read_secret(args.ipmi_password, args.ipmi_password_stdin)
    secrets = [p for p in (rf_password, ipmi_password) if p]

    redfish = probe_redfish(args.redfish, args.user, rf_password, args.timeout,
                             args.insecure, secrets)
    ipmi = probe_ipmi(args.ipmi_host, args.ipmi_user, ipmi_password, args.timeout, secrets)
    criteria = evaluate(redfish, ipmi, args.os_arch or None)
    overall = overall_verdict(criteria)

    evidence = {
        "tool": "scripts/baremetal/ipmi_acceptance.py",
        "generated_at_utc": _utc_now(),
        "target": {"redfish": args.redfish, "ipmi_host": args.ipmi_host, "os_arch": args.os_arch},
        "overall": overall,
        "criteria": criteria,
        "raw": {"redfish": redfish, "ipmi": ipmi},
        "manual_power_commands": [
            "ipmitool -I lanplus -H <host> -U <user> -P <pass> chassis power status",
            "ipmitool -I lanplus -H <host> -U <user> -P <pass> chassis power reset   # 等价于软重启",
            "ipmitool -I lanplus -H <host> -U <user> -P <pass> chassis power off      # 写操作：人工确认后执行",
            "ipmitool -I lanplus -H <host> -U <user> -P <pass> chassis power on       # 写操作：人工确认后执行",
        ],
        "note": "写操作（power off/on）不由本脚本自动执行；本脚本只做只读探测。",
    }
    payload = json.dumps(evidence, ensure_ascii=False, indent=2)
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8", newline="\n") as f:
            f.write(payload + "\n")
        print("证据已写入 %s" % args.out)
    else:
        print(payload)

    print("\n总判据: %s" % overall)
    for c in criteria:
        print("  [%s] %s %s —— %s" % (c["verdict"], c["id"], c["title"], c["why"]))
    if overall != VERDICT_PASS:
        print("\n远程开关机等写操作请人工执行（命令见证据 JSON 的 manual_power_commands 字段）。")
    return exit_code_for(overall)


def _utc_now():
    import datetime
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


if __name__ == "__main__":
    sys.exit(main())
