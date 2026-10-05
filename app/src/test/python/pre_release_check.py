#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
推包前核对脚本: md5 + 修复特征 (防旧包覆盖退化, 2026-10-06)
背景坑: /root/nyral-debug.apk 曾为 13:29 旧包, 误覆盖 WG 设备致 scan 退化
  (旧包缺 clickWithEventDriven 等修复特征)。装机前必须核对 md5 与修复特征。

用法:
  python3 pre_release_check.py <apk路径> [期望md5] [--mapping <mapping.txt>]
示例:
  python3 pre_release_check.py app/build/outputs/apk/debug/app-debug.apk 93aa9570978957fbd737c6c4d01b077c
  python3 pre_release_check.py app/build/outputs/apk/debug/app-debug.apk --mapping app/build/outputs/mapping/debug/mapping.txt
退出码: 0=全部通过, 1=存在失败, 2=用法错误
"""
import hashlib
import os
import re
import subprocess
import sys
import zipfile

# 修复特征清单: (名称, 字符串, 编码 ascii|utf16)
# 注意: debug 构建开 R8 混淆(isMinifyEnabled=true), 类名/方法名会被改写,
#       注释里的字符串不保留, 只能用"代码中真实使用且进入 dex 的字符串字面量"。
#       clickWithEventDriven 为历史旧包(v41前)缺失的强区分特征。
# 新增核心修复/功能后在此追加独特字符串, 防止旧包/半成品误装。
FEATURES = [
    ("事件驱动点击闭环 clickWithEventDriven", "clickWithEventDriven", "ascii"),
    ("scan 容器剔除 JS dropContainers", "dropContainers", "ascii"),
    ("scan 索引标记 data-scan", "data-scan", "ascii"),
]

# 新增模块核对: 用 R8 mapping 文件确认新类已被打进包(混淆后类名在 mapping 中可查)。
# 传入 --mapping <mapping.txt> 时启用; mapping 位于 app/build/outputs/mapping/debug/mapping.txt
NEW_CLASSES = ["ScanDeduper", "BrowserScanRules", "DebugRoutes", "ToolCallPairer"]


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def dex_strings(apk):
    """解压 APK 中所有 classes*.dex 并提取可打印字符串集合(去重)。

    R8 混淆不改写字符串字面量, 因此按 ASCII 与 UTF-16LE 两种编码提取:
      - ascii: 常规错误消息/日志/标识
      - utf16: 中文字符串在 dex 中按 UTF-16LE 存储
    返回 {"ascii": set, "utf16": set}
    """
    ascii_set = set()
    utf16_set = set()
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if re.match(r"classes\d*\.dex$", name):
                data = z.read(name)
                for m in re.finditer(rb"[ -~]{8,}", data):
                    ascii_set.add(m.group().decode("latin1"))
                # UTF-16LE: 中文范围 \u4e00-\u9fff + ASCII 混排
                txt = data.decode("utf-16-le", errors="ignore")
                for m in re.finditer(r"[\u4e00-\u9fff][\u4e00-\u9fff\u0020-\u007e]{3,}", txt):
                    utf16_set.add(m.group())
    return {"ascii": ascii_set, "utf16": utf16_set}


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    apk = sys.argv[1]
    expect_md5 = None
    mapping = None
    rest = sys.argv[2:]
    i = 0
    while i < len(rest):
        if rest[i] == "--mapping" and i + 1 < len(rest):
            mapping = rest[i + 1]
            i += 2
        elif expect_md5 is None and not rest[i].startswith("--"):
            expect_md5 = rest[i]
            i += 1
        else:
            print(f"[FATAL] 无法识别的参数: {rest[i]}")
            return 2
    if not os.path.isfile(apk):
        print(f"[FATAL] 文件不存在: {apk}")
        return 2

    print("=== 推包前核对 ===")
    print(f"APK   : {apk}")
    print(f"大小  : {os.path.getsize(apk):,} bytes")
    print(f"修改  : {__import__('datetime').datetime.fromtimestamp(os.path.getmtime(apk)):%Y-%m-%d %H:%M:%S}")
    m = md5(apk)
    print(f"MD5   : {m}")

    ok = True
    if expect_md5:
        hit = (m == expect_md5)
        ok = ok and hit
        print(f"期望MD5: {expect_md5}  {'[PASS] 一致' if hit else '[FAIL] 不一致!'}")

    print("--- 修复特征 ---")
    strings = dex_strings(apk)
    for label, feature, enc in FEATURES:
        # dex 字符串常嵌在更长串中(如 " data-scan " 或整段 JS), 用子串包含判断
        found = any(feature in s for s in strings[enc])
        ok = ok and found
        print(f"  {'[PASS]' if found else '[FAIL]'} {label}: {feature} ({enc})")

    print("--- 新增模块核对 ---")
    strings = dex_strings(apk)
    dex_all = strings["ascii"] | strings["utf16"]
    dex_hits = {}
    for cls in NEW_CLASSES:
        dex_hits[cls] = any(cls in s for s in dex_all)
    if all(dex_hits.values()):
        # debug 包不混淆, 类名直接可见
        for cls in NEW_CLASSES:
            print(f"  [PASS] 类已打进包: {cls} (dex 直查)")
    elif mapping:
        if not os.path.isfile(mapping):
            print(f"  [FAIL] mapping 文件不存在: {mapping}")
            ok = False
        else:
            with open(mapping, "r", encoding="utf-8", errors="ignore") as f:
                mapping_text = f.read()
            for cls in NEW_CLASSES:
                found = cls in mapping_text
                ok = ok and found
                print(f"  {'[PASS]' if found else '[FAIL]'} 类已打进包: {cls} (mapping)")
    else:
        for cls in NEW_CLASSES:
            found = dex_hits[cls]
            ok = ok and found
            print(f"  {'[PASS]' if found else '[FAIL]'} 类已打进包: {cls} (dex 直查; 若为混淆包请加 --mapping)")

    print("==========")
    if ok:
        print("[RESULT] PASS 可推包装机")
        return 0
    print("[RESULT] FAIL 疑似旧包/缺修复特征, 禁止装机")
    return 1


if __name__ == "__main__":
    sys.exit(main())
