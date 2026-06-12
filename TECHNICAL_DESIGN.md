# 微头条发布器 — 技术方案文档

## 一、整体架构

### 1.1 架构选型：MVI（Model-View-Intent）

采用 **MVI 单向数据流**架构，核心链路：

用户操作 → `PublishIntent` → `PublishViewModel` → `PublishState` → View `render()`

```
                      ┌─────────────┐
                      │  用户操作    │
                      │ (点击/输入)  │
                      └──────┬──────┘
                             │ sendIntent()
                             ▼
                     ┌───────────────┐
                     │ PublishIntent │  sealed class，按业务子域分 5 组
                     │               │  Text / Image / Publish / Draft / Internal
                     └───────┬───────┘
                             │ handleIntent()
                             ▼
                    ┌──────────────────┐
                    │ PublishViewModel │  reduce: 旧 State + Intent → 新 State
                    │ (StateFlow)      │  AndroidViewModel + DefaultLifecycleObserver
                    └───────┬──────────┘
                            │ StateFlow.collect()
                            ▼
                    ┌──────────────────┐
                    │   PublishState   │  不可变 data class，唯一状态源
                    │  (StateFlow)     │  所有 UI 状态集中管理
                    └───────┬──────────┘
                            │ render(state)
                            ▼
                    ┌──────────────────┐
                    │   View 层        │  setText() / setImages() / showToolbar()
                    │  MainActivity    │  纯渲染，不含业务逻辑
                    └──────────────────┘
```

**关键设计原则**：
- 状态不可变：每次状态变化生成新 `PublishState` 对象，消除并发修改
- Intent 确定性：给定 Intent 对应唯一 handler，执行路径可追踪
- Data 层懒加载：`DraftManager` / `ImageCompressor` 仅在发布或保存时调用，不参与 Intent 分发

### 1.2 包结构（七层）

```
app/src/main/java/com/example/test_micrott/
├── models/         数据模型
│   ├── PublishState.kt      唯一状态源（~60 行）
│   ├── PublishIntent.kt     sealed class 意图定义（~64 行）
│   ├── SpanDescriptor.kt    格式 Span 序列化/反序列化（~69 行）
│   └── TopicItem.kt         热门话题条目（~37 行）
├── data/           数据层
│   ├── ImageCompressor.kt       图片压缩（双路径设计）
│   ├── ThumbnailCache.kt        L1 内存缓存（LruCache 16MB）
│   ├── DiskThumbnailCache.kt    L2 磁盘缓存（自研 LRU 50MB）
│   ├── DraftManager.kt          草稿管理 CRUD + 事务包裹
│   └── DraftDatabaseHelper.kt   SQLiteOpenHelper 手写
├── views/          View 层（纯渲染 + 自定义控件）
│   ├── MainActivity.kt          发布页主 Activity
│   ├── RichEditText.kt          自定义富文本编辑器（~630 行）
│   ├── NineGridLayout.kt        自定义九宫格 ViewGroup（拖拽排序）
│   ├── SpanWatcher.kt           token 守卫（话题/提及块删除防护）
│   ├── GalleryPickerActivity.kt 自定义相册选择器
│   └── DraftListActivity.kt     草稿箱列表页
├── viewmodels/     ViewModel 层
│   └── PublishViewModel.kt      MVI ViewModel + 生命周期自管理
├── repository/     仓库接口
│   └── DraftRepository.kt       草稿存储抽象
├── di/             依赖注入
│   ├── AppContainer.kt          手动 DI 容器
│   └── App.kt                   Application 入口
└── domain/         领域规则
    ├── AtomicSpanRules.kt       原子化 Span 守卫决策（纯 Kotlin）
    └── TopicMentionRules.kt     话题/提及正则与格式化模板（纯 Kotlin）
```

---

## 二、核心技术方案

### 2.1 富文本编辑器（A2/A3 核心）

**方案**：自定义 `RichEditText`（继承 `AppCompatEditText`），使用 `SpannableString` 实现格式渲染。

**功能矩阵**：

| 功能 | 实现方式 | 技术要点 |
|------|----------|----------|
| 粗体/斜体 | `StyleSpan(Typeface.BOLD/ITALIC)` | Type-Ahead 模式：无选区时标记状态，后续输入自动带格式 |
| 文字颜色 | `ForegroundColorSpan` | 10 色选择器，支持选中上色和 Type-Ahead |
| #话题# 蓝色渲染 | `ForegroundColorSpan(0xFF2A62FF)` | 正则 `#[^#]*#` 匹配后插入，Span 标记为不可编辑块 |
| @提及 蓝色渲染 | `ForegroundColorSpan(0xFF2A62FF)` | 正则 `@[^\s@#​]+` 匹配，尾部插入零宽空格分隔 |
| Emoji 插入 | `Editable.insert()` | BottomSheet + ViewPager2 分类网格 |

### 2.2 话题与@提及原子块守卫（A3 进阶）

**难点**：用户可能部分删除话题（如删除 `#科技#` 中的 `科技` 两字），导致格式残留或文本错乱。

**方案**：`AtomicSpanRules` + `SpanWatcher` 双层守卫

```
AtomicSpanRules（纯 Kotlin 决策层，4 种守卫）
├── resolveBackspace()     退格键：光标在右边界 → 整块删除；否则逐字删除
├── resolveCursorSnap()    光标磁吸：点击话题内部 → 自动弹到边界
├── resolveSelection()     选区：边界切到 Span 内部 → 自动扩展包裹完整 Span
└── resolveFilterExpansion() IME 输入：替换范围覆盖 Span 部分 → 扩展到完整 Span

SpanWatcher（Android 框架层，注册 4 个监听点）
├── setOnKeyListener        键盘事件
├── setOnClickListener      点击事件
├── AccessibilityDelegate   辅助功能兼容
└── InputFilter + TextWatcher 输入法过滤
```

**单元测试覆盖**：22 个用例，涵盖全部 4 种守卫的边界情况，纯 JVM 可运行。

### 2.3 图片选择与九宫格（A4/A9 核心）

**方案**：自定义 `NineGridLayout`（继承 `ViewGroup`），自研虚槽映射拖拽算法。

**九宫格拖拽排序**：

```
虚槽映射表：virtualSlots: MutableMap<Int, Int>
  key   = 原始顺序中第几张图片
  value = 该图片当前应显示在第几格

拖拽流程：
  onInterceptTouchEvent → 检测长按+移动 → 启动拖拽
  onTouchEvent → slotOf[imageIndex]=slot 维护映射 → translation 动画实时跟随
    被挤走的 child → 150ms 过渡动画移到新位置
  onUp → 180ms 归位动画 → rebuildChildren 提交最终顺序
```

关键修复（BUGFIX#1）：`bringToFront()` 改变 children 数组顺序导致图片跳位，改用 `translationZ` 只改渲染层。

**图片选择上限**：动态控制，最多 9 张。超过上限时隐藏加号按钮。

### 2.4 图片压缩与缓存（A8 性能优化）

**压缩：`ImageCompressor` 双路径设计**

```
路径 1：系统 API → ContentResolver.loadThumbnail()
         优点：<10ms/张，硬件加速
         守卫：尺寸 < target/3 时回退路径 2

路径 2：手动解码 → BitmapFactory.decodeFileDescriptor()
         步骤：inJustDecodeBounds 读尺寸 → adaptTargetSize 计算比例
              → inSampleSize 下采样 → 精确缩放
```

**比例自适应**：`adaptTargetSize(rawW, rawH, maxW, maxH)` 按原图宽高比缩放，
解决非正方形图片被硬编码正方形导致的变形问题。

**缓存：三级缓存**

| 级别 | 容量 | 速度 | 存活范围 |
|------|------|------|----------|
| L1 内存 LruCache | 16MB | ns 级 | 进程内 |
| L2 磁盘 DiskThumbnailCache | 50MB | ms 级 | 跨进程 |
| L3 原始文件 (MediaStore) | 不限 | 慢 | 持久化 |

### 2.5 草稿自动保存（A5）

**方案**：SQLite 单表 + 双层草稿机制

```sql
CREATE TABLE drafts (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    text        TEXT    NOT NULL DEFAULT '',
    images_json TEXT    NOT NULL DEFAULT '[]',
    spans_json  TEXT    NOT NULL DEFAULT '[]',
    saved_at    INTEGER NOT NULL,
    is_temporary INTEGER NOT NULL DEFAULT 0  -- 0=永久 1=临时
);
```

**生命周期驱动**：

```
onPause → 保存临时草稿 (is_temporary=1)
onResume → 删除临时草稿（用户回来了）
onStop → 临时草稿升级为永久（用户真走了）
```

关键设计：内部 Activity 跳转（草稿箱/相册/预览）通过 `launchingInternalActivity` 标记防止误升级。

### 2.6 性能测试结论

| 指标 | 数值 | 说明 |
|------|:----:|------|
| 9 图峰值内存 | 191 MB (PSS) | 未 OOM |
| 稳定态 | ~155-157 MB | 振幅 < 5 MB |
| Native Heap 回落 | 67% | 无泄漏 |
| CPU 主线程占用 | 接近 0% | 协程 IO 线程解码 |
| 缓存 L1 命中率 | ~100% | 热场景无需解码 |

---

## 三、难点问题解决方案

### 3.1 插入话题后格式丢失（BUGFIX#2）

**现象**：在粗体文字后插入 `#话题#`，已有粗体格式全部丢失。

**三层根因**：
1. 话题插入走了 `ViewModel → render() → setText()`，`setText()` 清空已有 Span
2. ViewModel 中"文本追加估算"逻辑导致 `state.text` 双写
3. `SPAN_EXCLUSIVE_EXCLUSIVE` 在 Span 内部插入文字时自动扩张

**解决方案**：话题插入改为直接操作 `Editable`（不走 ViewModel），插入前切开穿越的格式 Span，插入后分两段重新挂回。

### 3.2 草稿恢复后格式丢失（BUGFIX#4）

**现象**：草稿恢复后文字内容正确，但粗体/颜色格式有时消失。

**根因**：`setText()` 同步触发 `TextWatcher`，执行时机早于 `reapplyFormattingSpans()`，导致 state 记录空 Span 列表。

**解决方案**：`doAfterTextChanged()` 中加 `isProgrammaticChange` 守卫跳过程序化变更，reapply 后手动同步回调。

### 3.3 大图场景内存控制

**策略**：
1. 缩略图统一 400px 下采样，禁止全分辨率加载
2. 大图预览按屏幕实际宽度（`widthPixels`）按需解码
3. LruCache 16MB 上限，防止 OOM
4. `Semaphore(3)` 限制并发解码线程数

---

## 四、技术亮点

### 4.1 零第三方库自研

| 通常选用的第三方库 | 自研替代 | 代码行数 |
|-------------------|----------|:--------:|
| Glide / Coil | ImageCompressor + ThumbnailCache + DiskThumbnailCache | ~400 行 |
| Room | DraftDatabaseHelper (SQLiteOpenHelper) | ~100 行 |
| Hilt / Dagger | AppContainer 手动 DI | ~30 行 |
| 第三方富文本库 | RichEditText + SpanWatcher + AtomicSpanRules | ~780 行 |

### 4.2 纯 Kotlin 可测试领域层

`AtomicSpanRules` 和 `TopicMentionRules` 零 Android 依赖，可独立 JUnit 运行。
已编写 **42 个单元测试**，全部通过。

### 4.3 手动 DI + SavedStateHandle 状态恢复

不使用 Hilt/Dagger，通过 `AppContainer` 单例管理依赖。结合 `SavedStateHandle` 实现旋转屏 100% 状态恢复，无状态丢失。

### 4.4 防抖草稿机制

区分"临时离开"和"真退出"两种场景，`onPause`→临时 / `onResume`→删除 / `onStop`→升级，不产生冗余草稿。配合 UPSERT 策略保证同时最多 1 条临时草稿。

---

## 五、功能勾选表

| 编号 | 功能模块 | 状态 | 备注 |
|:----:|----------|:----:|------|
| A1 | 发布页基础框架 | ✅ | 标题栏 + 编辑区 + 工具栏 + 发布按钮 |
| A2 | 富文本编辑器 | ✅ | 粗体/斜体/颜色 + @提及 + 自定义 RichEditText |
| A3 | 话题功能 | ✅ | BottomSheet 选择 + #话题# 蓝色渲染 + 原子块守卫 |
| A4 | 图片功能 | ✅ | 相册多选(上限9) + 预览 + 拖拽排序 + 删除 |
| A5 | 发布流程 | ✅ | 草稿保存/恢复 + 空内容校验 + 发布反馈 |
| A6 | UI 还原 | ✅ | 参考头条微头条发布器，结构清晰 |
| A7 | 架构优化 | ✅ | MVI + 七层包结构 |
| A8 | 性能优化 | ✅ | 三级缓存 + 双路径压缩 + 协程 IO |
| A9 | 自定义控件 | ✅ | NineGridLayout + RichEditText + SpanWatcher |

---

*文档版本：2026-06-11*
