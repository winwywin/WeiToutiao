# Bug 修复记录（Bug Fix Report）

本文档记录开发过程中的真实踩坑案例，每个案例按「我发现了问题 → AI 给出排查建议 → 我验证定位 → 找到根因 → AI 给出修复方案 → 我采纳」的对话叙事展开。

---

## Bug 1：拖拽排序时图片跳位到 position 9

**场景**：九宫格拖拽排序基本功能写完后，我长按第 1 张图片（位置 0）开始拖拽，图片没有直接跟随手指，而是先"飞"到了第 9 格（最后一格），整个九宫格的布局瞬间错乱。但松手后，图片又恢复了正确的位置。

**AI 初始排查建议**：

AI 建议我在两个地方加 log 来定位问题：
1. 在 `initDrag` 里打印 `dragImageIndex` 和被拖图片的 `childIndex`
2. 在 `onLayout` 入口打印每个 child 的 index 和对应的 layout 位置

AI 的推理是：如果 `initDrag` 里 index 正确，但 `onLayout` 里 index 变了，说明有某个操作在中间改变了 ViewGroup 的 children 数组顺序。

**我的验证**：

我按 AI 建议加了 log，发现：
- `initDrag` 里：`dragImageIndex=0`，被拖 child 的 index=0（正确）
- `onLayout` 里：同一个 child 的 index 变成了 `children.size()-1`（即被移到了数组末尾）

这证实了 AI 的推断：确实有某个操作把被拖的 child 移到了 children 数组的末尾。

**AI 新一轮排查建议**：

AI 问："你的 `initDrag` 里有没有调用 `bringToFront()`？这是 RecyclerView/ViewGroup 拖拽排序里很常见的写法，目的是让被拖的图片显示在最顶层。"

我立刻去查 `initDrag` 的代码，发现确实有一行：
```kotlin
dragChild.bringToFront()
```
这行是之前从 RecyclerView + ItemTouchHelper 方案迁移时残留的代码，当时直接照搬了"让被拖 item 到最后层"的思路。

**根因确认**：

`bringToFront()` 的底层实现是 `removeView(child)` 再 `addView(child)`——把 child 从当前位置物理移除，再 append 到 children 数组末尾。

而我的九宫格 `onLayout` 是按 children 数组的顺序依次 layout 的（`for (i in 0 until childCount)`），用 `i % 3` 和 `i / 3` 计算行列位置。

所以 bringToFront() 后：
- 被拖 child 的 index 从 0 变成了 9（假设一共 9 张图 + 1 个加号按钮）
- `onLayout` 把它 layout 到了最后一格（第 9 格）的位置
- 但虚槽映射 `virtualSlots[0]` 还是 0（逻辑上还在位置 0）
- `applyDragTranslation` 用 `virtualSlots` 计算 translation，基准位置是最后一格——导致巨大的偏移量，图片"飞"了

**AI 修复方案**：

AI 建议：移除 `bringToFront()`，改用 `dragChild.translationZ = 8f`。

解释：`translationZ` 只影响渲染层的 Z-order（绘制顺序），完全不触碰 ViewGroup 的 children 数组。`bringToFront()` 是"改数据结构来实现视觉层效果"，而 `translationZ` 是"直接改视觉层效果"——后者在自定义 ViewGroup + 虚槽映射的架构下才是正确的选择。

**我的决策**：采纳。

移除 `bringToFront()` 并加上 `translationZ = 8f` 后，被拖图片正确地浮在其他图片上方，且不再"飞"到最后一格。

**关联 Commit**：`f8a652a`（Day 11: 拖拽改为松手吸附 + 图片选择去重）

---

## Bug 2：富文本格式后插入话题 → Span 格式全部丢失

**场景**：我在 EditText 里输入了红色粗体文字（比如 `"测试"` 是红色粗体），然后点击话题按钮插入 `#话题#`，结果：红色粗体全部变回了默认黑色普通文字，只有话题的蓝色保留了。但如果先插入话题再输入文字，格式是正常的。

**AI 初始排查建议**：

AI 建议我在 `MainActivity.render()` 里打 log，检查 `state.text` 和 `EditText.text` 是否一致，以及是否会触发 `setText()`。

AI 的推理是：如果插入话题后触发了 `setText()`，那么 EditText 里的所有 Span 都会被清除（`setText()` 会重建 Spanned，但如果不正确处理，Span 信息会丢失）。

**我的验证**：

我按 AI 建议加了 log：
```kotlin
val editTextDifferent = binding.ktg.text.toString() != state.text
Log.d("Render", "editText='${binding.ktg.text}', state.text='${state.text}', different=$editTextDifferent")
```

发现插入话题后 `editTextDifferent == true`，确实触发了 `setText(state.text)`。

**AI 新一轮排查建议**：

AI 建议我追踪 `state.text` 的变化来源，特别是 `handleInsertTopic`（键盘输入触发）和 `handleSelectTopic`（话题 BottomSheet 选择触发）两条链路。

AI 问："`handleInsertTopic` 里是不是有类似 `state.text = currentText + topicText` 的推算逻辑？如果 `currentText` 不是实时从 EditText 读取的，就可能双写。"

**我的验证**：

我追踪了两条链路，发现：
1. `Editable.replace()` 插入话题 → `TextWatcher.afterTextChanged()` → 同步到 `state.text`（正确）
2. 但 `handleInsertTopic` 里有一段"追加估算"逻辑：`if (!currentText.endsWith(topicText))` 然后 `state.text = currentText + topicText`

问题：`currentText` 是 `state.text` 的上一次值（还没被 `TextWatcher` 更新），导致 `state.text` 被覆盖成了不带格式纯文本。然后 `render()` 里的 `setText(state.text)` 把 EditText 里的 Span 全清了。

**但修复双写后，还有新问题**：如果话题插入在粗体文字的中间（光标在粗体范围内），新插入的话题文字会被粗体 Span 覆盖（变成了粗体）——这是 `SPAN_EXCLUSIVE_EXCLUSIVE` 的特性：在 Span 内部插入文字时，Span 会扩张而非分割。

**AI 新一轮排查建议**：

AI 建议我直接用 `Editable` 操作来插入话题（而不是通过 ViewModel 绕一圈），并且在插入前把穿过插入点的格式 Span 切开，插入后再按新位置分两段挂回。

具体方案：
```kotlin
// 插入前：切开穿过插入点的格式 Span
val snapshots = cutCrossingFormatSpans(editable, insertPos)
// 插入话题文本
editable.replace(insertPos, insertPos, "#新话题# ")
// 插入后：按新位置分两段挂回
reapplySplitSpans(editable, snapshots, insertPos, insertedLen, 0)
```

**根因确认**：

**三层问题叠加**：
1. 话题插入走了 `ViewModel → render() → setText()` 路径，摧毁了已有 Span
2. ViewModel 里的"追加估算"逻辑导致 `state.text` 双写（覆盖了带格式的实时文本）
3. `SPAN_EXCLUSIVE_EXCLUSIVE` 在 Span 内部插入文字时会扩张，导致话题文字被格式覆盖

**AI 修复方案**：

AI 给出三层修复方案：
1. 话题插入改为直接操作 `Editable`（不走 `ViewModel`），保留已有 Span
2. 删除 ViewModel 里的文本追加逻辑（`TextWatcher` 已经精确同步了）
3. 插入前用 `cutCrossingFormatSpans()` 切开格式 Span，插入后用 `reapplySplitSpans()` 按新位置分两段挂回

**我的决策**：采纳全套方案。

修改后，无论在什么样的位置插入话题，已有格式都不会丢失，话题文字也不会被格式覆盖。

**关联 Commit**：`a045699`（Day 24 修复：加号占位符显示 + 拖拽顺序保存）→ `1fb8375`（Day 28: 架构拆分收尾 + RichEditText 封装，从根源消除 setText 双写路径）

---

## Bug 3：粗体按钮高亮在取消后仍不消失

**场景**：我选中一段粗体文字，点击「B」按钮取消粗体，粗体确实被移除了，但「B」按钮仍然高亮显示为激活状态。直到我点击 EditText 其他地方，「B」才变灰。

**AI 初始排查建议**：

AI 建议我在 `toggleBold()` 的末尾（`updateButtonStates()` 调用前）加 log，打印 `selectionStart` 和 `selectionEnd` 的值。

AI 的推理是：如果 `selectionStart == selectionEnd`（光标没有选中任何文字），`updateButtonStates()` 会检查光标所在位置的格式——但如果这两个值读错了，`updateButtonStates()` 就会返回错误的结果。

**我的验证**：

我按 AI 建议加了 log：
```kotlin
val selStart = ktg.selectionStart
val selEnd = ktg.selectionEnd
Log.d("Bold", "toggleBold selStart=$selStart, selEnd=$selEnd")
toggleBoldStyle(selStart, selEnd)
updateButtonStates(selStart, selEnd)
```

发现 `selStart` 和 `selEnd` 在 `toggleBold` 里读到的确实是正确的值（选中范围）。但 `updateButtonStates()` 内部再去读 `ktg.selectionStart` 时，竟然变成了 0！

**AI 新一轮排查建议**：

AI 问："你是不是在 `updateButtonStates()` 里直接读 `ktg.selectionStart`？如果 `toggleBold` 是在按钮的点击事件里调用的，那此时 EditText 可能已经失焦了——按钮被点击时，焦点从 EditText 转移到了按钮上。"

我立刻意识到问题：我在 `updateButtonStates()` 内部用的是 `ktg.selectionStart`（实时读取），而不是 `toggleBold` 入口传进来的 `selStart` 参数！

**我的修复尝试（失败）**：

我改成在 `toggleBold` 入口先用局部变量捕获 `selStart` 和 `selEnd`，然后传参给 `updateButtonStates()`。

但 AI 提醒我："你改完后在 `updateButtonStates()` 里是不是还有 `ktg.isFocused` 的判断？如果 EditText 失焦了，你可能跳过了按钮状态更新。"

我去查 `updateButtonStates()` 的代码，发现确实有 `if (!ktg.isFocused) return` 的保护逻辑——这是之前为了防止"后台更新按钮状态"加的，但在按钮点击的场景下，EditText 已经失焦了，这个保护导致 `updateButtonStates()` 直接 return 了！

**AI 新一轮排查建议**：

AI 建议我：
1. 删除 `updateButtonStates()` 里的 `isFocused` 保护（按钮状态更新不应该依赖焦点）
2. 在 `show()` 方法里也调用一次 `updateButtonStates()`（`show()` 是工具栏重新显示时调用的，此时需要刷新按钮状态）

**根因确认**：

**双根因**：
1. 点击按钮导致 EditText 失焦，`updateButtonStates()` 里实时读的 `selectionStart/End` 是失焦后的错误值（通常为 0）
2. `updateButtonStates()` 里有 `isFocused` 保护，在失焦时直接跳过——但按钮点击的场景下，我们恰恰需要更新按钮状态

**AI 修复方案**：

```kotlin
fun toggleBold() {
    val selStart = ktg.selectionStart   // ← 在按钮点击前捕获（此时焦点还在 EditText）
    val selEnd = ktg.selectionEnd
    toggleBoldStyle(selStart, selEnd)
    updateButtonStates(selStart, selEnd) // ← 用捕获的值，而不是让 updateButtonStates 内部再读
}

fun show() {
    // ... 显示工具栏 ...
    updateButtonStates(ktg.selectionStart, ktg.selectionEnd)  // ← show() 时也刷新
}
```

**我的决策**：采纳。

修改后，点击「B」按钮取消粗体，按钮高亮立刻消失，不需要再点击其他地方。

**关联 Commit**：`a045699`（Day 24 修复：加号占位符显示 + 拖拽顺序保存）

---

## Bug 4：草稿恢复后 Span 格式丢失

**场景**：我在编辑器里写了粗体+红色文字，保存草稿退出。再次打开草稿后，文字内容正确恢复了，但粗体和红色格式全部消失了。这个问题是偶发的，不是 100% 复现。

**AI 初始排查建议**：

AI 建议我在 `render()` 里打 log，检查草稿恢复时 `spansJson` 是否正确解析，以及 `reapplyFormattingSpans()` 的返回值。

AI 的推理是：如果 `reapplyFormattingSpans()` 返回 0，说明 Span 挂上后又被某个逻辑清除了——就像 Bug 2 里的 `setText()` 清除 Span 一样。

**我的验证**：

我按 AI 建议加了 log：
```kotlin
Log.d("Draft", "restored spans: ${draft.spansJson}")  // ✓ 正确解析了
Log.d("Draft", "reapplied count: ${reapplyFormattingSpans(...)}")  // ✗ 返回 0
```

`reapplyFormattingSpans()` 返回 0——说明 Span 确实挂上去了，但接着又被清除了。

**AI 新一轮排查建议**：

AI 建议我在 `onTextChanged()` 和 `doAfterTextChanged()` 里加 log，看看是谁在 `reapplyFormattingSpans()` 之后又清除了 Span。

AI 特别问："`doAfterTextChanged()` 里是不是有同步 Span 到 `state.formatSpanDescriptors` 的逻辑？如果 `isProgrammaticChange` 的标志位时机不对，`doAfterTextChanged()` 可能在 `reapply` 之前就把空的 Span 列表同步到 state 里了。"

**我的验证**：

我加了 log，发现：
1. `render()` 调用 `setText(savedText)` → Android 内部同步触发 `TextWatcher.onTextChanged()`
2. `onTextChanged()` 里读到的 Span 是空的（因为 `reapplyFormattingSpans()` 还没执行）
3. `onTextChanged()` 把空的 Span 列表写入了 `state.formatSpanDescriptors`
4. 然后 `reapplyFormattingSpans()` 才执行，把 Span 挂上了——但 state 里记录的是空列表
5. 等到下次 `render()` → `lastSpanDescriptors != state.formatSpanDescriptors` → 重新 `setText()` → Span 全丢

**根因确认**：

`setText()` 内部同步触发 `TextWatcher.onTextChanged()`，而 `onTextChanged()` 捕捉 Span 状态写入 `PublishState` 的时机**早于** `reapplyFormattingSpans()`，导致 state 记录的是空 Span 列表，下次 render 又触发 `setText()` → 循环清除。

**AI 修复方案**：

AI 给出两个修复：
1. 在 `doAfterTextChanged()` 里加 `isProgrammaticChange` 守卫：如果当前是程序化变更（`setText()` 触发的），直接 return，不同步 Span 到 state
2. 在 `reapplyFormattingSpans()` 之后，手动调用一次 `onTextChanged()`，把恢复后的 Span 正确同步到 state

```kotlin
// 1. 在 doAfterTextChanged 中守卫程序化变更
override fun afterTextChanged(s: Editable?) {
    if (isProgrammaticChange) return  // ← 跳过 setText() 触发的 TextWatcher
    // ... 正常同步 Span 到 state ...
}

// 2. reapply 后显式同步到 state
reapplyFormattingSpans(draft.spans)
onTextChanged()  // ← 手动触发一次，把恢复的 Span 写回 state
```

**我的决策**：采纳。

修改后，草稿恢复时格式（粗体/斜体/颜色）都正确恢复了，且 100% 可复现。

**关联 Commit**：`a045699`（Day 24 修复：加号占位符显示 + 拖拽顺序保存）

---

## 总结：4 个 Bug 的排查方法对比

| # | Bug | 核心根因 | 关键排查方法 |
|---|-----|----------|-------------|
| 1 | 拖拽图片跳位 | `bringToFront()` 改变 children 数组顺序 | 在 `initDrag` 和 `onLayout` 加 log 对比 child index |
| 2 | 插入话题后格式丢失 | 三层叠加：`setText` 路径 + 双写 + `SPAN_EXCLUSIVE_EXCLUSIVE` | 追踪 `state.text` 变化链路（Editable → ViewModel → render） |
| 3 | B 按钮高亮不消失 | 按钮点击导致 EditText 失焦，`selectionStart` 读到错误值 | 在 `toggleBold` 入口捕获 `selStart/selEnd`，对比 `updateButtonStates` 内部实时读的值 |
| 4 | 草稿恢复 Span 丢失 | `setText()` 同步触发 `TextWatcher`，早于 `reapplySpans` | 在 `onTextChanged` 和 `doAfterTextChanged` 加 log 确认时序 |
