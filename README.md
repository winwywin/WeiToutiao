# 微头条发布器 (Micro Toutiao Publisher)

仿写今日头条「微头条」发布页面，完整实现富文本编辑、图片选择、草稿保存等核心功能。  
采用 **MVI 架构 + 零第三方库**（禁用 Glide/Room/Hilt），全部组件自研。

---

## 功能清单（A1–A9 对照）

| 编号 | 功能模块 | 状态 | 说明 |
|:---:|---|:---:|---|
| A1 | 基础发布页 | ✅ | 标题栏 + 文本编辑器 + 九宫格 + 发布按钮 |
| A2 | 图文选择 | ✅ | 系统 PhotoPicker + 自定义相册 + 微信式预览勾选 |
| A3 | 富文本编辑 | ✅ | 粗体 / 斜体 / 颜色 + 话题蓝色 Span + @提及 |
| A4 | 字数统计 | ✅ | 2000 字上限，实时计数 |
| A5 | 草稿自动保存 | ✅ | SQLite 单表，主动保存 + onPause 防抖 + onStop 永久 |
| A6 | 发布成功反馈 | ✅ | 分步上传模拟（压缩→上传→完成），水平进度条 |
| A7 | 包结构重构 | ✅ | 四层强隔离：`models` / `data` / `views` / `viewmodels` |
| A8 | 性能优化 | ✅ | ThumbnailCache 三级缓存 + 协程线程池 + 下采样 |
| A9 | 自定义控件 | ✅ | `NineGridLayout`（拖拽排序）+ `RichEditText`（富文本自定义 View，683 行）+ `SpanWatcher`（token 守卫） |

---

## 架构图

```mermaid
flowchart TD
    User["用户操作\n(点击/输入/拖拽)"]
    View["View 层\nMainActivity\nRichEditText"]
    Intent["PublishIntent\n(sealed class)"]
    VM["ViewModel\nPublishViewModel"]
    State["PublishState\n(StateFlow)"]
    Render["render(state)\n驱动 UI 更新"]
    Data["Data 层\nDraftManager\nImageCompressor"]

    User -->|事件| View
    View -->|sendIntent()| Intent
    Intent --> VM
    VM -->|reduce: 计算新 State| State
    State -->|collect| Render
    Render -->|setText/setImages/showToolbar| View

    VM -.->|仅发布时| Data
    VM -.->|仅草稿保存时| Data
```

**MVI 单向数据流**：
1. View 接收用户操作，转换为 `PublishIntent` 发送给 ViewModel
2. ViewModel 的 `handleIntent()` 直接计算新 `PublishState`（纯 Kotlin 函数，无副作用）
3. 新 State 通过 `StateFlow` 发射，View 通过 `render(state)` 驱动 UI 更新
4. Data 层（`DraftManager` / `ImageCompressor`）仅在用户明确触发持久化操作时被调用（发布、草稿保存），不作为 Intent 分发链路的必经环节

---

## 项目结构

```
app/src/main/java/com/example/test_micrott/
├── models/                  # 数据模型（PublishState, PublishIntent, SpanDescriptor, TopicItem）
├── data/                    # 数据层（ImageCompressor, ThumbnailCache, DiskThumbnailCache, DraftManager, DraftDatabaseHelper）
├── views/                   # View 层（MainActivity, NineGridLayout, RichEditText, SpanWatcher, ...）
├── viewmodels/              # ViewModel 层（PublishViewModel）
├── repository/              # 仓库接口（DraftRepository）
├── di/                      # 依赖注入（AppContainer, App）
└── domain/                  # 领域规则（TopicMentionRules, AtomicSpanRules）
```

---

## 技术栈

| 类别 | 技术 |
|---|---|
| 语言 | Kotlin |
| 架构 | MVI（Model-View-Intent） |
| UI | XML Layouts + Custom View（`NineGridLayout`, `ColorPickerPopup`） |
| 异步 | Kotlin Coroutines + `lifecycleScope` |
| 图片 | 自研 `ImageCompressor`（四步下采样）+ `ThumbnailCache`（L1 LruCache 16MB + L2 自研 DiskThumbnailCache 50MB） |
| 存储 | SQLite（`DraftDatabaseHelper`）+ 自研 `DraftManager`（事务包裹） |
| 富文本 | `SpannableString` + 自研 `SpanWatcher`（token 守卫） |
| 依赖注入 | 手动 DI（`AppContainer` 单例） |

**核心组件自研**：图片压缩、缓存、数据库、DI 容器自研，不依赖 Glide/Room/Hilt 等框架。
（注：`androidx.*`、`material`、`kotlinx-coroutines`、`leakcanary` 为 Android 官方/调试依赖，不视为业务层第三方库。）

---

## 运行指南

### 环境要求

- Android Studio 2025.3.4+
- JDK 21（AGP 9.2.1 要求）
- Android SDK 36（API Level 36）
- 测试设备：Android 7.0+（API Level 24+）

### 编译运行

```bash
# 克隆项目
git clone <repo-url>
cd test_microTT

# 编译 Debug 包
./gradlew assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk

# 或直接运行
./gradlew installDebug
```

### 权限说明

应用需要以下权限：
- `READ_MEDIA_IMAGES`（Android 13+）：读取相册图片
- `READ_EXTERNAL_STORAGE`（Android 12-）：读取相册图片

---

## 核心功能说明

### 富文本编辑

- **粗体/斜体**：选中文字后点击工具栏按钮，支持 Type-Ahead 模式（无选区时点击按钮，后续输入自动带格式）
- **文字颜色**：10 色选择器，支持选中上色和 Type-Ahead
- **话题插入**：点击 `#` 按钮或输入 `#` 触发 BottomSheet 话题选择器，插入后自动蓝色渲染
- **@提及**：输入 `@` 触发用户选择弹窗，插入后蓝色渲染

### 图片选择

- 支持系统 PhotoPicker 和自定义相册
- 微信式交互：点击图片预览，勾选框独立显示
- 九宫格拖拽排序，支持长按拖拽和点击删除

### 草稿保存

- **主动保存**：退出时弹窗确认，点击「保存」写入永久草稿
- **防抖保存**：`onPause` 写入临时草稿，`onResume` 删除（用户回来了），`onStop` 升级为永久（用户真走了）
- 草稿箱列表页，支持恢复和删除

---

## 开发日志（精选 Commit）

| Commit | 功能 |
|---|---|
| `d289d87` | 项目初始化，确立 MVI 分包 |
| `02c4065` | 定义 `PublishState` 唯一状态源 |
| `32572c6` | MVI 主项目逻辑迁移（PhotoPicker + 话题 + 九宫格） |
| `e4b0eda` | `SavedStateHandle` 状态恢复 |
| `c39d0b9` | 富文本格式化（粗体/斜体/颜色） |
| `08715f5` | @提及功能 + 蓝色 Span 插入 |
| `1c6b62d` | 表情选择面板 |
| `50b3baa` | 相册下采样缩略图（修复 ANR） |
| `7304fe9` | 字数统计 + 发布成功反馈页 |
| `222b8e7` | 草稿自动保存（JSON 格式） |
| `91f2e98` | 热门话题选择器 BottomSheet |
| `afd9fdd` | 包结构重构（四层强隔离） |
| `96181e8` | `SpanWatcher` 块删除 + `NineGridLayout` 自定义九宫格 |
| `a045699` | 加号占位符显示 + 拖拽顺序保存 |
| `fa277e4` | 相册选图微信式交互（点击预览 + 勾选） |
| `9bcaf86` | SQLite 幽灵字段清理 + 事务包裹 `beginTransaction/endTransaction` |
| `1fb8375` | **Day 28 大提交**：`di/domain/repository` 模块 + `DiskThumbnailCache` + `RichEditText` 封装 + `ImageCompressor` 比例自适应 + 阈值放宽 + `NineGridLayout` 接口化 |

**总 Commit 数：47**（远超要求的 ≥30）

---

## 文档索引

| 文件 | 说明 |
|---|---|
| [`README.md`](README.md) | 项目概述、功能清单、架构图、技术栈 |
| [`DECISIONS.md`](DECISIONS.md) | 10 篇架构决策记录（ADR 格式） |
| [`AI_USAGE.md`](AI_USAGE.md) | 7 个 AI 交互案例（场景→AI 方案+概念解释→决策+理由→新问题→排查→解决） |
| [`BUGFIX.md`](BUGFIX.md) | 4 个 Bug 修复案例（场景→AI 排查建议→验证→根因→AI 修复方案→采纳） |
| [`PERFORMANCE.md`](PERFORMANCE.md) | 性能测试报告（Profiler 内存/CPU/缓存效率） |

## 性能摘要

详见 [`PERFORMANCE.md`](PERFORMANCE.md)，测试数据见 [`mem_profile_20260609_201734.csv`](mem_profile_20260609_201734.csv)。

**测试结论**：
- 9 图场景内存峰值 191MB，稳定 ~157MB（未 OOM）
- 退出相册后 Native Heap 从 36.8MB 降回 12MB（回落 67%，无明显泄漏）
- `ThumbnailCache` 二级缓存有效避免重复解码

---

## 已知问题

1. **DiskThumbnailCache 淘汰策略**：每次 `put()` 后执行全量排序，图片数量大时（>100）有性能优化空间，当前场景可忽略

---

## 许可证

MIT License

---

## 作者

- 开发者：Android 开发工程师
- 日期：2026-06-11
