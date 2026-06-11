# 性能测试报告

本文档记录微头条发布器的内存性能测试与分析。测试数据来源于 `mem_monitor.py` 脚本采集的 ADB 实时内存监控，以及 Android Studio Profiler 的时间线截图。

---

## 测试环境

| 项目 | 值 |
|------|-----|
| 设备 | Android 模拟器 (API 36) |
| 测试工具 1 | 自研 `tools/mem_monitor.py`（每秒 `adb dumpsys meminfo` + `top`） |
| 测试工具 2 | **Android Studio Profiler**（Memory 时间线 + Heap Dump） |
| 数据文件 | [`mem_profile_20260609_201734.csv`](mem_profile_20260609_201734.csv)（46 个采样点，~77 秒） |
| 测试场景 | 9 图全选 → 发布器编辑 → 返回相册 → 退出 |

---

## 内存数据来源说明

两个测试工具统计口径不同，以下全文均标注数字来源以避免混淆：

- **mem_monitor** = `adb dumpsys meminfo` 的 **PSS**（含系统共享页分摊），通常偏高
- **AS Profiler** = Android Studio 自身进程跟踪的 **RSS**（不含共享页），更接近实际进程占用

| 统计项 | AS Profiler 基线 | mem_monitor 基线 | mem_monitor 峰值 |
|--------|:---:|:---:|:---:|
| Total | ~64 MB | ~88 MB | 191.1 MB |
| Java Heap | ~6 MB | 14.9 MB | 14.9 MB |
| Native Heap | ~18 MB | — | **36.8 MB** |
| Graphics | ~13 MB | — | **91.9 MB** |

---

## 内存趋势分析（mem_monitor PSS 口径）

### 总内存趋势

| 指标 | 值 |
|------|-----|
| 峰值 TotalPSS | **191.1 MB** |
| 稳定态 TotalPSS | ~155-157 MB |
| 初始基线 TotalPSS | ~88 MB |
| 最大增量 | ~103 MB（约 +117%） |

**趋势描述**（基于 CSV 中 46 个采样点的观察）：

1. **加载阶段**（采样点 1-8）：TotalPSS 从 ~88 MB 快速上升至 ~160 MB，主要增长来自 Graphics（缩略图加载和解码）和 Native Heap（BitmapFactory 分配）
2. **稳定期**（采样点 9-40）：总内存在 155-157 MB 上下波动，振幅 < 5 MB。说明图片解码完成后，L1+L2 缓存有效避免了重复解码——没有反复申请释放导致的抖动
3. **退出相册后**（采样点 41-46）：TotalPSS 从 ~157 MB 降至 ~150 MB，Graphics 回落但幅度不大（47 MB → 44 MB），属于正常 UI 组件残留

### 分堆回落对比（mem_monitor 口径，峰值 vs 退出后）

| 堆类型 | 峰值 | 退出后 | 回落幅度 | 泄漏判断 |
|--------|:----:|:------:|:--------:|:--------:|
| Java Heap | 14.9 MB | 10.4 MB | -30% | ✅ GC 正常回收 |
| Native Heap | **36.8 MB** | **12.0 MB** | **-67%** | ✅ 大量释放，无泄漏 |
| Graphics | **91.9 MB** | 44.1 MB | -52% | ✅ 纹理/图层释放，残留为 View 正常占用 |
| Code | 5.4 MB | 5.4 MB | 0% | ➖ 静态代码段，不释放是正常的 |
| Stack | 1.7 MB | 1.3 MB | -24% | ✅ 线程栈回收 |

**结论**：四类可回收内存均显著回落，**未发现内存泄漏信号**。

---

## Heap Dump 分析（AS Profiler）

在 9 图加载后峰值处执行 "Dump Java heap"（AS Profiler 功能），过滤 Bitmap 类后的关键数据：

| 指标 | 数值 |
|------|:----:|
| Bitmap 实例数 | **3**（Java Heap 中仅存 wrapper 对象） |
| Native Size | **23 KB**（Java Heap Dump 可读取的 Native 关联大小） |
| Retained Size | **~2.3 KB**（Java 对象本身，不含像素数据） |

**关键解读**：

1. **Bitmap 像素数据不在 Java Heap**：Android 8.0+ 将 Bitmap 像素分配在 Native Heap 中，Java Heap 里只保留极小的 wrapper 对象。3 个实例 / 2.3 KB 是正常水平。
2. **实际像素内存 = Profiler 时间线中的 Native + Graphics**：时间线显示 Native 41.7 MB + Graphics 45.2 MB，这部分才是 9 张图的像素真正占用——全部在 Native 层，Java Heap 零负担。
3. **若存在泄露，会看到大量 Bitmap 实例堆积**。当前仅 3 个实例，排除泄露可能。

**Heap Dump 截图**：[`screenshots/performance/profiler_heapdump_9images.png`](screenshots/performance/profiler_heapdump_9images.png)

---

## AS Profiler 时间线特征（`profiler_timeline_full.png`）

截图 `profiler_timeline_full.png` 包含完整的 CPU + Memory 时间线，可观察到：

1. **Activity 跳转链**：顶部显示 `GalleryPickerActivity → stopped → destroyed → MainActivity`
2. **内存阶梯**：约 05:00 处从 ~64 MB 陡升至 ~128 MB → 对应 9 张图加载
3. **GC 行为**：底部可见 2 个垃圾桶图标，GC 触发后内存有明显回落 → 对象可正常回收
4. **线程数**：65 个（含系统线程），应用层协程通过 `Semaphore(3)` 控制并发解码线程数，未无限制创建
5. **CPU 曲线**：Profiler 时间线中 CPU 绿色区域极小，主线程接近空闲 → 协程 `Dispatchers.IO` 解码不阻塞 UI

---

## 缓存效率分析

### ThumbnailCache 命中率预估

| 场景 | L1 内存 | L2 磁盘 | 实际解码次数 |
|------|:-------:|:-------:|:-----------:|
| 首次加载 9 图 | 0%（冷启动） | 0% | 9 次（不可避免） |
| 同一相册反复进出 | ~100%（热数据） | 0% | 0 次（完全命中） |
| 预览大图 | 0%（尺寸不同） | 0% | 1 次/图 |

**分析**：
- L1（`LruCache` 16MB）：9 张 400px 缩略图约 4.5-9 MB，完全在 L1 容量内，同一生命周期内无需回退 L2
- L2（`DiskThumbnailCache` 50MB）：主要跨进程存活，冷启动时免除解码
- 缓存 key = `uri.toString().hashCode().toString(16)`，内容不变时 hash 不变

---

## 性能对比（参考值）

| 指标 | 本应用 | 参考值（典型发布器 App） |
|------|:------:|:-----------------------:|
| 9 图峰值内存 | 191 MB（PSS） | 200-350 MB ① |
| 主线程卡顿 | 无 | — |
| 基线内存 | ~64-88 MB | — |
| GC 行为 | 正常，无频繁触发 | — |

> ① 参考值为开发者经验区间，非严格 benchmark。本应用自研缩略图和缓存对内存占用有明显控制。

---

## Profiler 截图清单

| # | 截图 | 文件 | 状态 |
|---|------|------|------|
| 1 | Memory Profiler 时间线 | `profiler_timeline_full.png` | ✅ 已提交 |
| 2 | 基线内存（AS Profiler） | `profiler_baseline.png` | ✅ 已提交 |
| 3 | 9 图加载后内存 | `profiler_9images.png` | ✅ 已提交 |
| 4 | **Heap Dump（Bitmap 过滤）** | `profiler_heapdump_9images.png` | ✅ 已提交 |
| 5 | **CPU Profiler 轨迹** | — | ⏳ 待补充（需在 AS Profiler 录制解码流程后截图） |

截图均存放于 `screenshots/performance/` 目录。

---

## 测试复现

```bash
# 启动内存监控
cd tools/
python mem_monitor.py

# 在设备上操作：选 9 图 → 进入发布器 → 预览 → 返回
# Ctrl+C 停止监控，CSV 自动保存在项目根目录

# AS Profiler 手动操作：Profiler → attach 进程 → Memory → Dump Java heap
```

---

## 结论

1. **未 OOM**：9 图场景下 Peak 191 MB（PSS），稳定态 155-157 MB，未达到 Android 典型堆上限
2. **无泄漏**：Native Heap 回落 67%，Graphics 回落 52%，GC 行为正常，Heap Dump 仅 3 个 Bitmap 实例
3. **UI 流畅**：Profiler CPU 曲线显示主线程接近空闲，`Dispatchers.IO` 协程池 + `Semaphore(3)` 有效隔离解码负载
4. **缓存有效**：L1 内存在同一生命周期内完全覆盖 9 图缩略图，无需回退磁盘
