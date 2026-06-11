# 架构决策记录（Architecture Decision Records）

本文档记录项目中的关键架构决策，包括决策背景、方案对比、最终选择和理由。

## ADR-001：MVI 架构选型

**日期**：2026-06-03  
**状态**：已采纳

### 背景

项目需要选择一个合适的架构模式，确保：
1. 状态管理清晰，避免 UI 状态分散
2. 支持进程销毁后状态恢复
3. Intent 分发路径确定性，便于调试

### 方案对比

| 方案 | 优点 | 缺点 |
|---|---|---|
| **MVC** | 简单直接 | 状态分散，难以维护 |
| **MVVM** | 数据绑定，双向通信 | 状态同步复杂，易出竞态 |
| **MVI** | 单向数据流，状态不可变，易调试 | 学习成本高，模板代码多 |

### 决策

**采用 MVI 架构**。

理由：
1. **单向数据流**：用户操作 → Intent → ViewModel → State → View，状态变化可追溯
2. **不可变状态**：`PublishState` 是不可变数据类，每次状态变化都生成新对象，避免并发修改
3. **进程销毁恢复**：通过 `SavedStateHandle` 持久化 `PublishState`
4. **Intent 分发确定性**：给定 Intent，对应 handler 必然执行；`AtomicSpanRules` 等纯逻辑层可独立 JUnit 测试（ViewModel 中 `handleClickPublish()` 含协程，需 `runTest` 环境）

### 实施

- `PublishState`：不可变数据类，包含所有 UI 状态
- `PublishIntent`：`sealed class`，定义所有用户操作（按业务子域分 5 组）
- `PublishViewModel`：处理 Intent，直接计算出新 State（`reduce` 模式）
- `MainActivity.render(state)`：驱动 UI 更新

**关联**：前置无（根决策）。后续：ADR-007（ViewModel 生命周期依赖 MVI 架构）、ADR-009（派生状态依赖 PublishState 设计）、ADR-010（render 依赖 State 数据结构）

---

## ADR-002：核心组件自研策略

**日期**：2026-06-03  
**状态**：已采纳

### 背景

项目约束：禁止使用 Glide、Coil、Room、Hilt、Retrofit 等第三方框架库。
（注：`androidx.*`、`material`、`kotlinx-coroutines`、`leakcanary` 为 Android 官方/调试基础设施依赖，不视为业务层第三方库。）

### 方案对比

| 功能 | 第三方库方案 | 自研方案 |
|---|---|---|
| 图片加载 | Glide/Coil | `ImageCompressor`（四步下采样）+ `ThumbnailCache`（L1+L2） |
| 数据库 | Room | `DraftDatabaseHelper`（SQLiteOpenHelper）+ 手写 SQL |
| 依赖注入 | Hilt/Dagger | `AppContainer`（手动 DI 单例） |
| 网络请求 | Retrofit/OkHttp | 模拟上传（`delay(600ms/张)`） |

### 决策

**采用自研方案**，不使用任何业务层第三方框架库。

理由：
1. **项目约束**：硬性要求核心组件自研
2. **学习价值**：自研图片压缩、缓存、数据库等组件，深入理解 Android 底层机制
3. **可控性**：自研组件可根据需求灵活调整（如 `ThumbnailCache` 的 L1/L2 缓存策略）

### 实施

- **图片压缩**：`ImageCompressor.decodeSampledBitmap()`（四步下采样：读取尺寸 → 计算采样率 → 解码 → 回退手动 fd 解码）
- **缓存**：`ThumbnailCache`（L1: `LruCache<String, Bitmap>` 16MB + L2: 自研 `DiskThumbnailCache` 50MB）
- **数据库**：`DraftDatabaseHelper`（SQLiteOpenHelper）+ 手写 `CREATE TABLE` / `INSERT` / `SELECT`
- **依赖注入**：`AppContainer` 单例，持有 `DraftRepository` 实例

**关联**：前置无（根决策）。后续：ADR-003（禁止 Room，必须手写 SQLite）、ADR-004（禁止第三方富文本库）、ADR-005（禁止 Glide/Coil，自研缓存）、ADR-008（禁止第三方图片库，自研压缩）

---

## ADR-003：SQLite 草稿存储 + 双层草稿机制

**日期**：2026-06-01  
**状态**：已采纳

### 背景

草稿需要持久化存储，支持：
1. 主动保存（用户点击「保存」）
2. 防抖保存（`onPause` 临时 → `onStop` 永久）
3. 草稿恢复（从草稿箱点击恢复）

### 方案对比

| 方案 | 优点 | 缺点 |
|---|---|---|
| **JSON 文件** | 简单，易调试 | 大文本搜索慢，不支持事务 |
| **SQLite 单表** | 支持事务，查询快 | 需要手写 SQL |
| **SharedPreferences** | 简单 | 仅适合小数据，不支持复杂查询 |

### 决策

**采用 SQLite 单表 + 双层草稿机制**。

理由：
1. **事务支持**：草稿保存需要原子操作（图片复制到 app 私有目录 + DB 写入），SQLite 事务确保两者要么都成功要么都失败
2. **查询效率**：草稿箱列表需要按时间倒序查询，SQLite `ORDER BY saved_at DESC` 效率高
3. **双层保护**：`onPause` 写入临时草稿（用户短暂切换），`onStop` 升级为永久（用户真离开了）

### 实施

- **表结构**：
  ```sql
  CREATE TABLE drafts (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      text        TEXT    NOT NULL DEFAULT '',
      images_json TEXT    NOT NULL DEFAULT '[]',
      spans_json  TEXT    NOT NULL DEFAULT '[]',
      saved_at    INTEGER NOT NULL,
      is_temporary INTEGER NOT NULL DEFAULT 0
  )
  ```
- **事务包裹**：`DraftManager.internalSave()` 用 `db.beginTransaction()` / `db.endTransaction()` 包裹图片复制 + DB INSERT
- **双层草稿机制**：
  - 主动保存：`is_temporary = 0`（永久草稿）
  - 防抖保存：`onPause` → `is_temporary = 1`（临时草稿），`onStop` → 升级为永久
  - `onResume` → 删除临时草稿（用户回来了，数据没丢）

**关联**：前置：ADR-002（禁止 Room，必须手写 SQLiteOpenHelper）、ADR-007（onPause/onStop 生命周期驱动草稿保存）。后续：无

---

## ADR-004：自研 SpanWatcher + AtomicSpanRules

**日期**：2026-06-02  
**状态**：已采纳

### 背景

富文本编辑器需要支持：
1. 话题（`#话题#`）蓝色渲染
2. @提及蓝色渲染
3. 防止用户部分删除话题（原子块）

### 方案对比

| 方案 | 优点 | 缺点 |
|---|---|---|
| **第三方富文本库** | 功能全面 | 体积大，不满足自研约束 |
| **自研 SpanWatcher + AtomicSpanRules** | 轻量，精准控制 | 需要处理多处边界情况 |

### 决策

**采用自研 SpanWatcher + AtomicSpanRules**。

理由：
1. **自研约束**：不能使用第三方富文本库
2. **精准控制**：通过 `AtomicSpanRules` 的 4 种守卫精确控制话题/提及的删除行为
3. **轻量**：`SpanWatcher`（~200 行）+ `AtomicSpanRules`（纯 Kotlin，~100 行），不增加 APK 体积

### 实施

- **`AtomicSpanRules`**：纯 Kotlin 决策层，4 种守卫：
  1. `resolveBackspace()` — 退格键守卫（整块删除 vs 逐字删除）
  2. `resolveCursorSnap()` — 光标磁吸（点击话题中间自动弹到边界）
  3. `resolveSelection()` — 选区守卫（选区分跨话题边界时自动扩展）
  4. `resolveFilterExpansion()` — IME 输入拦截（输入法候选词上屏时防止话题被部分覆盖）
- **`SpanWatcher`**：Android 框架层，注册 4 个监听点（`setOnKeyListener` / `setOnClickListener` / `AccessibilityDelegate` / `InputFilter` + `TextWatcher`），委托 `AtomicSpanRules` 做决策
- **蓝色渲染**：通过 `ForegroundColorSpan` 实现，颜色值 `TopicMentionRules.PROTECTED_COLOR = "#2A62FF"`

**关联**：前置：ADR-002（禁止第三方富文本库）。后续：ADR-010（RichEditText 内含 SpanWatcher 守卫逻辑）

---

## ADR-005：ThumbnailCache 二级缓存策略

**日期**：2026-05-31  
**状态**：已采纳

### 背景

相册加载大量图片，需要缓存机制避免重复解码，确保滚动流畅。

### 方案对比

| 方案 | 优点 | 缺点 |
|---|---|---|
| **仅内存缓存** | 速度快 | 内存占用高，容易 OOM |
| **仅磁盘缓存** | 内存占用低 | 磁盘 I/O 慢，滚动卡顿 |
| **L1 内存 + L2 磁盘** | 平衡速度和内存 | 实现复杂 |

### 决策

**采用 L1 内存 + L2 磁盘二级缓存**。

理由：
1. **性能**：L1 缓存命中时直接返回 Bitmap，无需解码
2. **内存友好**：L1 缓存大小限制为 16MB（`LruCache`），超出时自动回收
3. **磁盘友好**：L2 缓存大小限制为 50MB（自研 `DiskThumbnailCache`），避免占用过多存储空间

### 实施

- **L1 内存缓存**：`LruCache<String, Bitmap>`（key = `uri.toString().hashCode().toString(16)`，16MB）
- **L2 磁盘缓存**：自研 `DiskThumbnailCache`（key = 同上 hashCode 字符串，50MB）
- **缓存策略**：
  1. 先查 L1，命中直接返回
  2. L1 未命中，查 L2，命中返回并写入 L1
  3. L1/L2 均未命中，解码图片并写入 L1 和 L2
- **淘汰策略**：L1 由 `LruCache` 自动 LRU 淘汰；L2 由自研 `DiskThumbnailCache` 在每次 `put()` 后按文件修改时间排序淘汰最旧条目（当前场景图片数 < 50，性能影响可忽略）

**关联**：前置：ADR-002（禁止 Glide/Coil，必须自研缓存）、ADR-008（L2 磁盘缓存存储压缩后 Bitmap）。后续：ADR-006（ThumbnailLoader 接口封装缓存调用）

---

## ADR-006：NineGridLayout 回调接口化（A9 补全）

**日期**：2026-06-11  
**状态**：已采纳

### 背景

`NineGridLayout` 需要异步加载缩略图，最初通过 `CoroutineScope` 属性传入 `lifecycleScope`，导致 View 直接依赖协程框架。

### 方案对比

| 方案 | 优点 | 缺点 |
|---|---|---|
| **直接持有 CoroutineScope** | 简单 | View 依赖协程，泄漏风险 |
| **回调接口（ThumbnailLoader）** | 解耦，防泄漏 | 需要外部实现加载逻辑 |

### 决策

**采用回调接口（`ThumbnailLoader fun interface`）**。

理由：
1. **解耦**：View 不再依赖协程框架，只定义"我需要异步加载这张图"的接口
2. **防泄漏**：外部（Activity）实现加载逻辑，使用 `lifecycleScope`，Activity 销毁时自动取消
3. **灵活性**：未来可以替换加载实现（如用线程池替代协程）
4. **A9 合规**：自定义控件抽象合理，不持有外部框架依赖

### 实施

- **`fun interface ThumbnailLoader`**：定义 `load(uri, targetW, targetH, onLoaded)` 接口
- **`NineGridLayout.thumbnailLoader`**：持有 `ThumbnailLoader` 实例（替代旧 `scope` 属性）
- **`MainActivity.initRecyclerView()`**：实现 `ThumbnailLoader`，用 `lifecycleScope` 异步加载
- **安全性**：`onLoaded` 回调中检查 `images.getOrNull(index) == uri`，防止 View 重建后写入错误 ImageView

**关联**：前置：ADR-005（ThumbnailLoader 接口封装 L1+L2 缓存调用）。后续：无

---

## ADR-007：ViewModel 生命周期自管理

**日期**：2026-05-31  
**状态**：已采纳

### 背景

`PublishViewModel` 需要响应 Activity 生命周期（onPause/onResume/onStop）驱动草稿保存逻辑，同时需要 `cacheDir` 用于图片压缩，而 View 层不应直接调用 ViewModel 的业务方法。

### 方案对比

| 方案 | 优点 | 缺点 |
|---|---|---|
| **Activity 中手动调用 ViewModel 方法** | 简单直接 | View 层感知业务逻辑，耦合高 |
| **ViewModel + onCleared()** | 系统回调 | `onCleared()` 只在 ViewModel 销毁时触发，无法处理 onPause/onStop |
| **AndroidViewModel + DefaultLifecycleObserver** | 精细控制 onPause/onResume/onStop | 需要 Activity 注册观察者 |

### 决策

**采用 `AndroidViewModel`（继承）+ `DefaultLifecycleObserver`（实现）**。

理由：
1. **`cacheDir` 访问**：`AndroidViewModel` 持有 `Application` 引用，`getApplication<Application>().cacheDir` 可直接访问
2. **生命周期驱动**：实现 `DefaultLifecycleObserver`，ViewModel 自主监听 onPause/onResume/onStop，View 层无需感知业务逻辑
3. **解耦**：Activity `onCreate()` 中执行 `lifecycle.addObserver(viewModel)` 完成注册，此后生命周期驱动完全由 ViewModel 自管

### 实施

- **继承 `AndroidViewModel`**：`class PublishViewModel(application: Application) : AndroidViewModel(application), DefaultLifecycleObserver`
- **`onPause()`**：保存临时草稿（`is_temporary = 1`），如已明确退出则跳过
- **`onResume()`**：等待 onPause 保存协程完成，然后删除所有临时草稿（用户回来了）
- **`onStop()`**：将所有临时草稿升级为永久草稿（用户真的离开了）
- **`cacheDir` 使用**：`ImageCompressor.compressImage()` 使用 `getApplication<Application>().cacheDir` 作为压缩输出目录

**关联**：前置：ADR-001（ViewModel 是 MVI 核心层）。后续：ADR-003（onPause/onStop 驱动草稿保存）

---

## ADR-008：ImageCompressor 双路径设计（A8 核心）

**日期**：2026-05-30  
**状态**：已采纳

### 背景

相册缩略图加载需要兼顾速度和内存：优先使用系统 `ContentResolver.loadThumbnail()`（速度快，内存友好），但该系统 API 可能返回过小的缩略图（如 `MICRO_KIND` 96×96），需要回退到手动 `BitmapFactory.decodeFileDescriptor()`。

### 方案对比

| 方案 | 优点 | 缺点 |
|---|---|---|
| **仅 loadThumbnail()** | 速度快 | 可能返回过小缩略图，清晰度不足 |
| **仅手动解码** | 清晰度可控 | 每次都开两次 fd，性能差 |
| **双路径：loadThumbnail → 尺寸守卫 → 回退手动解码** | 兼顾速度和清晰度 | 实现复杂 |

### 决策

**采用双路径设计**。

理由：
1. **性能**：`loadThumbnail()` 命中时无需手动解码，速度快
2. **清晰度**：尺寸守卫（阈值 = 目标尺寸 / 3）确保返回的缩略图足够大
3. **回退安全**：尺寸过小时自动回退到手动 fd 解码，保证清晰度

### 实施

- **路径 1**：`ContentResolver.loadThumbnail(uri, Size(targetW, targetH), null)` → 返回 `Bitmap?`
- **尺寸守卫**：`bitmap.width >= targetW/3 && bitmap.height >= targetH/3` → 接受；否则回退路径 2
- **路径 2**：`BitmapFactory.decodeFileDescriptor(fd, null, options)` → 四步下采样
- **阈值调优**：最初 `/2`（200px），后放宽为 `/3`（133px），防止 300×300 合理缩略图被拒绝

**关联**：前置：ADR-002（禁止第三方图片库，必须自研压缩）。后续：ADR-005（L2 磁盘缓存存储压缩后 Bitmap）

---

## ADR-009：派生状态计算策略

**日期**：2026-06-05  
**状态**：已采纳（当前手动维护）

### 背景

`PublishState` 中有多个状态字段可以通过其他字段派生（如 `isEditorTouched`、`isPublishButtonEnabled`），需要决定是在 State 中声明为派生 getter，还是由 ViewModel 每次手动计算写入。

### 方案对比

| 方案 | 优点 | 缺点 |
|---|---|---|
| **State 中 val 派生 getter** | 声明式，永远与源字段一致，不可能不同步 | 数据类中不能直接声明 `val xxx get() =`（会产生冗余构造参数） |
| **ViewModel 手动计算写入** | 可控，每个场景精细处理 | 多处重复逻辑，可能遗漏某个路径导致不一致 |

### 决策

**当前采用 ViewModel 手动计算写入**（`isPublishButtonEnabled` / `isEditorTouched` 均为构造参数，不是派生属性）。

理由：
1. **现实情况**：`PublishState` 是 Kotlin data class，`isPublishButtonEnabled: Boolean = false` 是构造参数；各 handler（`handleTextChanged`、`handleImagesChanged` 等，共约 6 处）在计算新 State 时手动赋值
2. **已有唯一派生**：`val hasContent: Boolean get() = text.isNotBlank() || selectedImages.isNotEmpty()` 是当前唯一真正的派生属性
3. **潜在改进**：可将 `isPublishButtonEnabled` 改为 `val isPublishButtonEnabled: Boolean get() = !isCharLimitExceeded && hasContent` 消除手动维护，但目前未实施

### 实施（当前实际代码）

- **构造参数**：`val isPublishButtonEnabled: Boolean = false`（由 ViewModel 6 个 handler 手动写入）
- **手动维护路径**：`handleTextChanged()` / `handleImagesChanged()` / `handlePublishClick()` 等各自计算 `isButtonEnabled` 后 `copy(isPublishButtonEnabled = isButtonEnabled)`
- **唯一派生**：`val hasContent: Boolean get() = text.isNotBlank() || selectedImages.isNotEmpty()`

**关联**：前置：ADR-001（State 是 MVI 核心数据结构）。后续：ADR-010（render 依赖 PublishState 字段，如 `isPublishButtonEnabled`）

---

## ADR-010：View 层 Delegate 拆分模式（A7 核心）

**日期**：2026-06-11  
**状态**：已采纳

### 背景

`MainActivity` 在重构前约 1400 行，承担了渲染引擎、格式化工具栏、话题选择器、@提及选择器、退出确认弹窗等至少 7 个独立职责，严重违反单一职责原则。

### 方案对比

| 方案 | 优点 | 缺点 |
|---|---|---|
| **拆分为多个 Fragment** | 生命周期清晰 | 需要新建 XML + Fragment 类 + 通信契约，改动大 |
| **拆分为自定义 View** | 封装性好 | 需要重写 XML 布局，风险高 |
| **Delegate 模式：提取独立 Kotlin 类** | 零 XML 改动，零 Fragment 风险 | 需要谨慎处理 EditText 引用生命周期 |

### 决策

**采用 Delegate 模式**，将独立 UI 职责提取为独立的 Kotlin 类，MainActivity 只保留生命周期绑定和 1 行委托调用。

理由：
1. **零 XML 改动**：复用现有布局，不碰任何 XML 文件
2. **零 Fragment 风险**：不涉及 FragmentManager 事务，无 Fragment 重建状态丢失风险
3. **渐进式重构**：每个 Delegate 可独立测试和修改，不影响其他功能
4. **通信简单**：通过 lambda 回调替代 FragmentResult API 或共享 ViewModel

### 实施

- **`RichEditText`**（683 行，`AppCompatEditText` 子类）：聚合富文本全部逻辑
  - B/I/A 按钮 + Type-Ahead + Span 序列化/反序列化
  - SpanWatcher 守卫（话题/提及原子块删除防护）
  - insertTopic/insertMention/insertEmoji 插入逻辑
- **`TopicPickerSheet`**（~185 行）：话题 BottomSheet 完整生命周期
- **`MentionPickerHelper`**（~30 行）：@提及 AlertDialog
- **`MainActivity` 保留**（525 行，从 1400 行砍掉约 63%）：
  - `render()` 渲染引擎
  - 权限处理 + 照片选择 + 九宫格初始化
  - 发布成功页 + 退出确认
  - `RichEditText` 回调绑定（`onTextContentChanged` / `onSpansChanged` / `onButtonStatesChanged`）

**关联**：前置：ADR-001（render 依赖 PublishState）、ADR-004（SpanWatcher 守卫内置在 RichEditText）、ADR-009（`isPublishButtonEnabled` 等字段由 ViewModel 手动维护）。后续：无

---

## 总结

| ADR | 决策 | 核心理由 | 前置依赖 |
|---|---|---|---|
| ADR-001 | MVI 架构 | 单向数据流，状态可追溯 | 根决策（无前置） |
| ADR-002 | 核心组件自研 | 项目约束 + 学习价值 | 根决策（无前置） |
| ADR-003 | SQLite 单表 + 双层草稿 | 事务支持 + 查询效率 + 防抖保护 | ADR-002（禁 Room）、ADR-007（生命周期驱动） |
| ADR-004 | 自研 SpanWatcher + AtomicSpanRules | 自研约束 + 精准控制 | ADR-002（禁第三方富文本库） |
| ADR-005 | L1+L2 二级缓存 | 性能 + 内存友好 | ADR-002（禁 Glide/Coil）、ADR-008（提供压缩 Bitmap） |
| ADR-006 | NineGridLayout 回调接口化 | 解耦 + 防泄漏 + A9 合规 | ADR-005（ThumbnailLoader 封装缓存调用） |
| ADR-007 | ViewModel 生命周期自管理 | AndroidViewModel + DefaultLifecycleObserver，onPause/onResume/onStop 驱动草稿逻辑 | ADR-001（ViewModel 是 MVI 核心层） |
| ADR-008 | 图片压缩双路径策略 | 性能 + 清晰度 + 回退安全 | ADR-002（禁第三方图片库） |
| ADR-009 | 派生状态计算策略 | 当前手动维护（isPublishButtonEnabled 为构造参数），hasContent 为唯一派生 getter | ADR-001（State 是 MVI 核心数据结构） |
| ADR-010 | View 层 Delegate 拆分 + RichEditText 封装 | 零 XML 改动 + 零 Fragment 风险 + RichEditText 聚合 683 行富文本逻辑 | ADR-001（render 依赖 State）、ADR-004（SpanWatcher 守卫内置）、ADR-009（PublishState 字段） |
