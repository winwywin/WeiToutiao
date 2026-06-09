#!/usr/bin/env python3
"""
ADB 实时内存 + CPU 监控工具，替代 AS Profiler 的时间序列分析。

用法：
    python tools/mem_monitor.py                    # 采样 120 秒，间隔 1 秒
    python tools/mem_monitor.py -d 300 -i 2        # 采样 300 秒，间隔 2 秒
    python tools/mem_monitor.py -o my_test.csv     # 指定输出文件

输出：
    - 控制台实时表格（每秒刷新）
    - CSV 文件（含完整时间序列，可用 Excel/WPS 画图）
"""

import subprocess
import time
import re
import csv
import sys
import os
import argparse
from datetime import datetime

# ============================================================
# 配置
# ============================================================

ADB = os.path.expandvars(
    r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
)
PACKAGE = "com.example.test_micrott"

# 各内存类别的 dumpsys App Summary 字段 → CSV 列名
FIELDS = [
    ("Java Heap",    "Java_MB"),
    ("Native Heap",  "Native_MB"),
    ("Code",         "Code_MB"),
    ("Stack",        "Stack_MB"),
    ("Graphics",     "Graphics_MB"),
    ("Private Other","PrivateOther_MB"),
    ("System",       "System_MB"),
    ("TOTAL PSS",    "TotalPSS_MB"),
]


# ============================================================
# 采样函数
# ============================================================

def sample_mem() -> dict | None:
    """跑一次 dumpsys meminfo，解析 App Summary 区域。"""
    try:
        out = subprocess.check_output(
            [ADB, "shell", "dumpsys", "meminfo", PACKAGE],
            timeout=10, stderr=subprocess.DEVNULL
        ).decode(errors="replace")
    except Exception:
        return None

    # 只解析 "App Summary" 区域（PSS KB）
    summary_section = out.split("App Summary")[-1]
    row = {}
    for label, col in FIELDS:
        m = re.search(rf"{label}:\s+(\d+)", summary_section)
        row[col] = int(m.group(1)) / 1024.0 if m else 0.0
    return row


def sample_cpu() -> float | None:
    """用 top 采样一次 App CPU 占用。"""
    try:
        out = subprocess.check_output(
            [ADB, "shell",
             f"top -b -n 1 -d 0.3 2>/dev/null | grep '{PACKAGE}'"],
            timeout=10, stderr=subprocess.DEVNULL
        ).decode(errors="replace")
        # top 输出格式: ...  PID ...  CPU%  ...
        # 多进程兼容：取第一个匹配行的 CPU%
        for line in out.strip().split("\n"):
            if PACKAGE in line:
                parts = line.split()
                # CPU% 通常是倒数第 4 列
                for i, p in enumerate(parts):
                    if "%" in p and i >= 2:
                        return float(p.replace("%", ""))
    except Exception:
        return None
    return None


# ============================================================
# 主循环
# ============================================================

def main():
    parser = argparse.ArgumentParser(description="ADB 实时内存监控")
    parser.add_argument("-d", "--duration", type=int, default=120,
                        help="采样时长（秒），默认 120")
    parser.add_argument("-i", "--interval", type=float, default=1.0,
                        help="采样间隔（秒），默认 1.0")
    parser.add_argument("-o", "--output", type=str, default=None,
                        help="输出 CSV 文件路径，默认自动生成时间戳文件名")
    args = parser.parse_args()

    # 输出文件
    if args.output is None:
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        args.output = f"mem_profile_{ts}.csv"

    # 检查设备连接
    try:
        devices = subprocess.check_output(
            [ADB, "devices"], timeout=5
        ).decode()
        if "\tdevice" not in devices:
            print("❌ 没有找到连接的设备，请确认 USB 已连且开启调试模式")
            sys.exit(1)
    except Exception:
        print("❌ ADB 不可用，请检查 Android SDK 路径")
        sys.exit(1)

    # 确保 App 在运行
    try:
        out = subprocess.check_output(
            [ADB, "shell", f"pidof {PACKAGE}"],
            timeout=5, stderr=subprocess.DEVNULL
        ).decode().strip()
        if not out:
            print(f"⚠️  {PACKAGE} 未运行，请先启动 App")
            print("   监控将在 App 启动后自动开始采集...")
    except Exception:
        print(f"⚠️  无法检测进程状态，直接开始采样")

    # 表头
    csv_headers = ["Timestamp", "Elapsed_s", "CPU%"] + [c for _, c in FIELDS]
    csv_file = open(args.output, "w", newline="", encoding="utf-8-sig")
    writer = csv.writer(csv_file)
    writer.writerow(csv_headers)

    # 控制台表头
    print()
    print("=" * 90)
    print("🔥 ADB 实时内存监控  每 {:.0f}秒 采样一次   Ctrl+C 退出".format(args.interval))
    print("=" * 90)
    header = f"{'时间':>8s} {'已运行':>6s} {'CPU%':>6s}"
    for label, _ in FIELDS:
        header += f" {label:>9s}"
    print(header)
    print("-" * 90)

    start = time.time()
    last_printed_line = False

    for i in range(int(args.duration / args.interval)):
        elapsed = time.time() - start
        now = datetime.now().strftime("%H:%M:%S")

        mem = sample_mem()
        cpu = sample_cpu()

        if mem is None:
            time.sleep(args.interval)
            continue

        # 写入 CSV
        csv_row = [now, f"{elapsed:.1f}", f"{cpu or 0:.1f}"]
        for _, col in FIELDS:
            csv_row.append(f"{mem.get(col, 0):.1f}")
        writer.writerow(csv_row)
        csv_file.flush()

        # 控制台实时输出（只更新最后一行）
        if last_printed_line:
            sys.stdout.write("\033[F")  # 光标上移一行

        line = f"{now:>8s} {elapsed:>5.0f}s {cpu or 0:>5.1f}%"
        # 用颜色标记异常值
        for label, col in FIELDS:
            val = mem.get(col, 0)
            if label == "TOTAL PSS" and val > 200:
                line += f" \033[91m{val:>8.1f}\033[0m"   # 红
            elif label == "Native Heap" and val > 80:
                line += f" \033[93m{val:>8.1f}\033[0m"   # 黄
            else:
                line += f" {val:>9.1f}"
        sys.stdout.write(line + "\n")
        sys.stdout.flush()
        last_printed_line = True

        time.sleep(args.interval)

    csv_file.close()
    print()
    print("=" * 90)
    print(f"✅ 采样完成  数据已保存到 {args.output}")
    print(f"   用 Excel/WPS 打开 CSV，选择数据列 → 插入折线图即可可视化")
    print(f"   关键列：Native_MB（图片内存）+ CPU%（卡顿指标）")
    print("=" * 90)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n⚠️  用户中断采样")
