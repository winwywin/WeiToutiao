# AI 使用说明（AI Usage Report）

本文档记录在微头条发布器项目开发过程中与 AI（WorkBuddy）的交互案例。每个案例展示 AI 给的方案、我做出决策的理由，以及新问题如何被发现和解决。

---

## 案例 1：九宫格拖拽排序 — 从 ItemTouchHelper 到自定义 ViewGroup 的方案迭代

**场景**：实现一个 3×3 的九宫格，用户可以长按图片拖拽到其他位置，松手后图片重新排序。拖拽过程中，被拖的图片要浮在其他图片上方，手指划过其他格子时，那些格子要实时让位。

**AI 初始方案**：用 RecyclerView + GridLayoutManager + ItemTouchHelper 实现。

- **RecyclerView**：Android 里用来显示列表或网格的控件，可以高效复用每个格子的视图。
- **GridLayoutManager**：让 RecyclerView 按网格排列（比如 3 列）。
- **ItemTouchHelper**：Android 官方提供的拖拽排序工具类，封装了手指按下、移动、松手的检测逻辑，开发者只需要告诉它「当图片从 A 位置移到 B 位置时怎么处理」。

**我的决策**：拒绝。

**拒绝原因**：ItemTouchHelper 的 `onMove` 回调只在手指跨过一个格子边界时触发一次，每次触发时交换相邻两个位置的条目。ItemTouchHelper 的「相邻交换」模型和九宫格的「实时让位」模型，本质上是两种不同的交互逻辑。前者是「松手后一次性跳转」，后者是「拖拽中连续位移」。

**AI 的新方案**：放弃 ItemTouchHelper，创建一个继承 `ViewGroup` 的自定义布局（叫 `NineGridLayout`），自己实现拖拽逻辑。

**核心设计**：

- **虚槽映射**：创建一个映射表 `virtualSlots: MutableMap<Int, Int>`，记录「每张图片当前应该显示在第几个格子」。比如 `virtualSlots[0] = 2` 表示「原始顺序第 0 张的图片，现在应该显示在第 2 格」。
- **物理顺序不变**：ViewGroup 里的 children 数组始终保持原始顺序（图片添加时的顺序），不动。所有视觉变化通过 `translationX` / `translationY`（图片相对原始位置的偏移量）实现。
- **拖拽过程**：手指移动时，计算手指当前在第几格 → 更新虚槽映射（被拖图片的虚槽 = 手指所在格子，中间图片的虚槽依次向拖动方向挤一格）→ 根据虚槽映射设置每张图片的 `translationX` / `translationY`。
- **松手提交**：手指松开后，根据虚槽映射重新排列图片列表，提交最终顺序。

**我的决策**：接受。

**新问题**：

1. 长按拖拽启动时，图片会跳到手指偏右偏下的位置，而不是手指正中心。看起来手指捏住了图片的左上角。
2. 图片拖到其他格子上方时，下面的图片没让位，只有松手后才交换。

**AI 排查原因**：

1. 图片不在手指中心：`touchOffset` 用 `event.x - child.left` 计算，值取决于手指按在图片的哪个位置（左上角或右下角不同），导致图片位置 = 手指位置 - touchOffset - 图片原始位置，中心没对准手指。
2. 其他图片不让位：虚槽映射更新了，但遍历逻辑只更新了被拖图片的 `translation`，没更新其他图片。

**AI 解决方法**：

1. 图片不在手指中心：`touchOffset` 改为图片中心点 `(child.width/2f, child.height/2f)`。
2. 其他图片不让位：遍历每张图片（被拖的除外），根据虚槽映射用 `translationX` / `translationY` 立即移到目标位置，调用 `cancel()` 取消上一个动画，松手时再统一做归位动画。

**我的决策**：接受。

---

## 案例 2：草稿持久化 — 连续拒绝两次 AI 方案后自建完整机制

**场景**：发布器需要自动保存草稿（文本 + 图片 URI + Span 格式），用户切到后台时自动保存，切回来时自动恢复。不能引入 Room（项目约束），只能用 Kotlin + XML + 手写 SQLite。

**AI 初始方案**：用 SharedPreferences + JSON 序列化，在 ViewModel 的 `onCleared()` 中保存草稿。

- **SharedPreferences**：Android 的键值对存储，适合存简单配置（用户设置、token 等）。AI 认为草稿数据量不大，一个 JSON 字符串存到单个 key 即可。
- **onCleared()**：ViewModel 的销毁回调。AI 认为「用户离开时 ViewModel 销毁 → onCleared 触发 → 保存草稿」是一条自然的链路。

**我的决策**：拒绝（存储方案和生命周期方案都拒绝）。

**拒绝原因**：

SharedPreferences 的存储问题：
1. 在主线程做 I/O，连续写入有 ANR 风险。
2. 不支持事务——草稿保存需要「复制图片到私有目录 + 写入数据」原子操作，SharedPreferences 做不到。
3. 未来需要草稿箱列表（按时间倒序查询所有草稿），SharedPreferences 的键值模型完全不支持列表查询。
4. 项目约束是禁用 Room，不是禁用 SQLite。手写 `SQLiteOpenHelper` + 原生 SQL 完全合规。

onCleared() 的生命周期问题：
- `onCleared()` 只在 ViewModel 被销毁时触发（即 Activity.finish() 且不再使用该 ViewModel）。用户按 Home 键切到微信只触发 `onPause/onStop`，`onCleared` 根本不会调用。
- 这意味着：用户切到微信回条消息 → 切回来 → `onCleared` 没触发 → 草稿没保存 → 数据丢失。

**AI 的新方案**：用 `SQLiteOpenHelper` 手写 SQL 做存储，ViewModel 实现 `DefaultLifecycleObserver` 做自动保存。

- **SQLiteOpenHelper**：Android 的 SQLite 数据库工具类，支持建表、增删改查、事务。一表多行，按 `saved_at` 倒序查询即可实现草稿箱列表。
- **DefaultLifecycleObserver**：Android 生命周期观察者接口，可以覆写 `onPause()`、`onResume()`、`onStop()` 等精确的 Activity 生命周期回调方法。

**核心设计**：

- 单表 `drafts(id, text, images_json, spans_json, saved_at, is_temporary)`。
- ViewModel 注册为 `lifecycle.addObserver(viewModel)`，在 `onPause`/`onResume`/`onStop` 三个钩子中精确驱动草稿操作。

**我的决策**：接受。

**新问题**：需要区分「用户暂时离开（切到微信马上回来）」和「用户真的走了（按 Home 键不回来了）」。两种场景都需要保存草稿以防万一，但前者不应该产生永久草稿（用户回来时草稿自动恢复，不需要草稿箱里多一条）。

**我的方案**（这一轮 AI 没给出完整方案，我自行设计）：

- `onPause` → 保存为**临时草稿**（`is_temporary=1`）。
- `onResume` → 删除临时草稿（用户回来了，数据没丢，临时草稿不需要了）。
- `onStop` → 所有临时草稿**升级为永久草稿**（用户真的走了）。
- 额外处理两类竞态：① `saveJob?.join()` 确保 onPause 保存完成后再执行 onStop 升级；② `launchingInternalActivity` 标记，Activity 跳转到内部页面（草稿箱/相册/大图预览）时 onStop 不误升级草稿。

**我的决策**：接受。防抖保存机制覆盖了所有生命周期场景，不产生冗余草稿。

---

## 案例 3：B 按钮高亮不消失 — post 延迟方案失败后的根因重新定位

**场景**：用户选中粗体文本后点击 B 按钮取消粗体，EditText 中格式已取消，但 B 按钮仍然显示高亮状态（选中色背景）。

**AI 初始方案**：在 `toggleBold()` 最后加 `post { updateButtonStates() }`，等 EditText 完成选区更新后再刷新按钮状态。

- **post {}**：Android 的 `View.post(Runnable)` 方法，把刷新操作延迟到当前消息队列执行完之后执行。这是处理 EditText 异步状态更新的常见范式。

**我的决策**：采纳并实施。

**新问题**：post 方案失败，B 按钮高亮依然不消失。我在 `updateButtonStates()` 入口打 log，发现 `post {}` 执行时 `selectionStart` 读到的值是 0（默认值），而不是取消粗体后的正确位置。

**AI 排查原因**：

1. 点击 B 按钮 → `toggleBold()` 被调用 → EditText 失焦 → `onFocusChange` 触发 → `hideFormattingToolbar()` 被调用 → toolbar 隐藏。
2. 当 `post {}` 延迟执行时，EditText 已经失焦，`selectionStart`​ 读到的是无效值 0，导致 `updateButtonStates()` 无法判断当前光标位置的格式状态。
3. 同时，toolbar 因其他事件重新显示时，`show()` 方法里没有刷新按钮状态——按钮带着之前的旧状态回到可见状态。

**AI 解决方法**：

1. `toggleBold()` / `toggleItalic()` / `applyColor()` 中，用**方法开头捕获的 selStart/selEnd** 直接同步调用 `updateButtonStates()`，不依赖 post 延迟。
2. `show()` 方法中新增 `updateButtonStates()` 调用，覆盖「点击按钮导致失焦 → toolbar 重回 VISIBLE 时按钮状态过期」的场景。

**我的决策**：接受。B/I 按钮高亮正确跟随文本格式状态。

---

## 案例 4：插入话题后格式丢失 — doAfterTextChanged 方向对但三层根因需要逐个击破

**场景**：用户在粗体文本后插入话题 `#话题名#`，粗体文本前面的格式全部丢失。以及草稿保存时 Span 的 offset 漂移，恢复后格式错位。

**AI 初始方案**：在 `TextWatcher` 的 `doAfterTextChanged` 回调中调用 `saveCurrentFormattingState()`。每次文本变更后都从 EditText 摘取所有格式 Span 重新扫描一遍，更新到 state 中。这样草稿保存时拿到的一定是最新的 Span offset。

- **TextWatcher**：Android 文本变更监听接口，`afterTextChanged` 在 EditText 完成内部文本更新后触发，是捕获「文本发生了改变」的标准钩子。

**我的决策**：采纳方向。实施时加上了守卫逻辑（`!isProgrammaticChange` 标志 + 防重入）。

**新问题**：草稿恢复时 Span offset 漂移问题解决了，但插入话题后格式丢失的问题仍在。

**AI 排查原因**（加 log 逐层追踪）：

1. **第 1 层 — setText() 导致 Span 丢失**：`insertTopicIntoEditor()` 内部调用 `editText.setText()` 替换内容。`setText()` 会清空 EditText 中所有已有 Span → 触发 `TextWatcher` → `doAfterTextChanged` 从 EditText 摘 Span 时，旧的格式 Span 已经被 setText 清空 → 扫到空列表 → state 被覆盖为空列表。
2. **第 2 层 — 双写竞态**：`SpanDescriptor` 通过两条路径写入 state（SavedStateHandle + DraftDB）。两条路径虽调用同一个 `serialize()`，但在快速连续操作时，前一次 setState 还没渲染完，后一次就从草稿恢复读出了旧 state，造成不一致。
3. **第 3 层 — SPAN_EXCLUSIVE_EXCLUSIVE 的副作用**：Android 的 `SpannableStringBuilder` 在插入文本时，如果新文本的位置与已有 Span 的边界相邻，`SPAN_EXCLUSIVE_EXCLUSIVE` 标记会导致 Span 被自动扩展或删除——这是 Android 框架层的隐式行为。

**AI 解决方法**：

1. **第 1 层**：`doAfterTextChanged` 守卫 `!isProgrammaticChange` 标志，程序化 setText 时不触发格式状态清理。
2. **第 2 层**：`insertTopicIntoEditor()` 改为直接操作 `Editable`（`editable.insert()` + `ForegroundColorSpan`），不触发 `setText`，从根本上避免 Span 被清空。
3. **第 3 层**：话题正则 `#[^#]*#` 与 `reapplyProtectedSpans()` 对齐，确保草稿恢复后话题蓝色 Span 不丢失。

**我的决策**：接受。AI 的 `doAfterTextChanged` 同步方向是对的，但不是完整解。三层根因中前两层是代码设计问题，第三层是 Android 框架的隐式行为，需要分别修复。

---

## 案例 5：RichEditText 封装 — AI 给了正确的拆分第一步，但边界不全

**场景**：MainActivity 膨胀到约 1400 行，富文本格式化逻辑散落在 Activity 中。涉及三处逻辑都在操作同一个 EditText——格式化工具栏、SpanWatcher 文本守卫、话题/提及/Emoji 插入。

**AI 初始方案**：提取格式化工具栏为独立的 Delegate 类 `FormattingToolbarDelegate`。

- **Delegate 模式**：将一组相关方法提取到独立类，原调用方通过委托对象间接调用。MainActivity 把 toolbar View 传给 Delegate，只需调用 `delegate.toggleBold()` 等一行方法。Delegate 负责 toolbar 显隐、B/I 切换、颜色选择器弹出。零 XML 改动，零 Fragment 风险。

**我的决策**：采纳。Delegate 提取后 MainActivity 从 1400 行降至约 672 行（减少 52%），B/I/颜色逻辑全部迁出。

**新问题**：架构审计指出——Delegate 只管了工具栏的 UI 控制，SpanWatcher 的文本守卫逻辑（4 个 TextWatcher + InputFilter）和插入操作（`insertTopic` / `insertMention` / `insertEmoji`）还在 MainActivity 里。三处逻辑共享同一个 EditText 上下文，天然应该聚合成一个**自定义 View**，而不是三个独立的类。

**AI 的新方案**：创建 `RichEditText`（`AppCompatEditText` 子类），聚合全部三处逻辑。对外暴露统一的格式化 API。MainActivity 只需持有 `RichEditText` 引用并调用其方法。

**核心设计**：

- `RichEditText.kt`（约 683 行），内置 SpanWatcher 守卫、格式化操作、插入操作。
- 公开 API：`toggleBold()` / `toggleItalic()` / `applyColor()` / `insertTopic()` / `insertMention()` / `insertEmoji()` / `setTextProgrammatic()`。
- 删除 `FormattingToolbarDelegate.kt`，格式化逻辑从此只有一个入口。
- MainActivity 进一步降至 525 行（总减少 63%）。

**我的决策**：接受。AI 的 Delegate 方向是对的——它识别出了「拆分职责」的需求。但它没有覆盖「哪些职责应该属于同一个模块」的系统性判断。这个判断来自架构审计——三处逻辑共享同一个 EditText 上下文，应该封装成一个自定义 View 而不是三个独立组件。

---

## 案例 6：ImageCompressor 比例自适应 — inSampleSize 方向对但精度不够

**场景**：项目早期，ImageCompressor 的缩略图目标尺寸被硬编码为 800×800（1:1 比例）。非正方形图片被强制缩放到正方形，图片变形严重。

**AI 初始方案**：用 `BitmapFactory.Options.inSampleSize` 按照原图尺寸自动选择合适的采样率。

- **inSampleSize**：Android 图片解码的标准下采样 API，只能按 2 的幂次缩放（2、4、8……）。比如 inSampleSize=4 能把图片尺寸除以 4，大幅减少解码内存。

**我的决策**：采纳方向。但 inSampleSize 的精度不够——当原图是 3000×1200（超宽）时，inSampleSize=4 只能缩到 750×300，还需要二次精确缩放才能匹配目标宽度。

**新问题**：非正方形图片仍然变形。需要按原图宽高比计算精确目标尺寸，而不是硬编码 800×800。

**AI 的新方案**：新增 `adaptTargetSize(rawW, rawH, maxW, maxH)` 方法，在 inSampleSize 之前先计算实际目标尺寸。

**核心设计**：`loadThumbnail` 路径先 `inJustDecodeBounds` 读原始尺寸 → `adaptTargetSize()` 按原图比例计算精确目标 → 传入下采样。效果：3000×1200 原图 → 目标 800×320（保持原始宽高比），而不是 800×800。

**我的决策**：接受。

**新问题**：ImageCompressor 的尺寸守卫阈值 `targetWidth / 2` 太严格。300×300 的缩略图也会被尺寸守卫拒绝，触发不必要的全分辨率解码路径。

**AI 的调整方案**：将阈值从 `targetWidth / 2` 放宽到 `targetWidth / 3`。目标宽度 800px 时，阈值从 400px 降至约 267px，300×300 正常走下采样路径，只有真正太小的（< 267px）才回退全分辨率。

**我的决策**：接受。AI 的 inSampleSize 建议是正确的技术方向，但精确比例匹配需要额外的 adaptTargetSize 计算，阈值调优需要根据实际使用场景（300×300 在 UI 中完全够用）来定。

---

## 案例 7：AI 幻觉 — 声称代码已实现但实际不存在

**场景**：在撰写 DECISIONS.md 的 ADR-009（派生状态计算策略）时，需要记录 `PublishState` 中哪些字段是派生属性、哪些是手动维护的。

**AI 给出的内容**：AI 在 ADR-009 草稿中写道：

> "`isPublishButtonEnabled` 已实现为 `val isPublishButtonEnabled: Boolean get() = !isCharLimitExceeded && hasContent`，即派生 getter，永远与源字段保持一致。"

AI 声称 `isPublishButtonEnabled` 是 Kotlin 派生属性（带 `get()` 的 val），自动从 `isCharLimitExceeded` 和 `hasContent` 计算得出。

**我的核实过程**：要求逐行核实代码后，打开 `PublishState.kt`：

```kotlin
val isPublishButtonEnabled: Boolean = false
```

这不是派生属性，没有 `get()` 块，只是一个普通的构造参数，默认值为 `false`。它的值由 ViewModel 的 6 个 handler（`handleTextChanged`、`handleImagesChanged` 等）通过 `copy(isPublishButtonEnabled = ...)` 手动写入。

**如何识别这次幻觉**：`isPublishButtonEnabled` 的命名模式（`isXxxEnabled`）让 AI 基于对 MVI 模式的「常识」推断它应该是派生属性。但实际代码中它是构造参数。这个差异只有打开源文件逐行读才能发现。唯一真正的派生属性是 `val hasContent: Boolean get() = text.isNotBlank() || selectedImages.isNotEmpty()`。

**修正**：将 ADR-009 从「AI 想象中的派生属性」改为如实记录当前代码状态。

