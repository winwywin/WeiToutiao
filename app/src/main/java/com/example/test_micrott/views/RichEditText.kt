package com.example.test_micrott.views

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.graphics.toColorInt
import com.example.test_micrott.domain.AtomicSpanRules
import com.example.test_micrott.domain.AtomicSpanRules.ProtectedRange
import com.example.test_micrott.domain.TopicMentionRules
import com.example.test_micrott.models.SpanDescriptor
import com.example.test_micrott.models.SpanType
import android.graphics.Typeface

/**
 * 自研富文本编辑器，聚拢分散在 FormattingToolbarDelegate / SpanWatcher / MainActivity 的
 * 所有富文本逻辑（格式切换、type-ahead、Span 序列化、话题/提及块删除守卫、插入操作）。
 *
 * 外部只需：
 * 1. XML 中声明 <com.example.test_micrott.views.RichEditText>
 * 2. 设置 callbacks：onTextContentChanged / onSpansChanged / onButtonStatesChanged
 * 3. 调用公共方法：toggleBold() / toggleItalic() / applyColor() / insertTopic() 等
 */
class RichEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "RichEditText"
        /** 保护色 — #话题# 和 @提及 的蓝色 */
        private val PROTECTED_COLOR_INT: Int by lazy {
            TopicMentionRules.PROTECTED_COLOR.toColorInt()
        }
    }

    // ========================================================================
    // 公开回调
    // ========================================================================

    /** 每次文本内容变化时回调（包括程序化 setText），传递纯文本 */
    var onTextContentChanged: ((text: String) -> Unit)? = null

    /** 非程序化文本变更时回调，传递当前所有格式化 Span 描述 */
    var onSpansChanged: ((descriptors: List<SpanDescriptor>) -> Unit)? = null

    /**
     * 按钮状态更新回调：(isBold, isItalic, activeColor?)。
     * activeColor 为 null 表示当前光标处无颜色 Span（排除保护色）。
     */
    var onButtonStatesChanged: ((isBold: Boolean, isItalic: Boolean, activeColor: Int?) -> Unit)? = null

    // ========================================================================
    // 内部状态
    // ========================================================================

    /** 是否正在执行程序化文本变更（setText / insert / SpanWatcher 清理） */
    private var isProgrammaticChange: Boolean = false

    // Type-Ahead 待定格式
    private var pendingBoldActive = false
    private var pendingItalicActive = false
    private var pendingColor: Int? = null

    // SpanWatcher 辅助
    private var pendingExpand: Pair<Int, Int>? = null

    // ========================================================================
    // 初始化 — 在构造完成后注册所有守卫
    // ========================================================================

    init {
        setupSpanGuard()
        setupTypeAheadWatcher()
        setupTouchButtonStateRefresh()
    }

    /**
     * 触摸 EditText 时刷新按钮状态（光标位置变化后 B/I/A 高亮需更新）。
     */
    private fun setupTouchButtonStateRefresh() {
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                notifyButtonStates()
                performClick()
            }
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 公开 API — 格式化操作（供外部工具栏按钮调用）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 切换粗体：无选区 → type-ahead 模式；有选区 → 应用/移除。
     * 调用后自动触发 [onButtonStatesChanged] 和 [onSpansChanged]。
     */
    fun toggleBold() {
        val editable = text ?: return
        val selStart = selectionStart
        val selEnd = selectionEnd

        // Type-Ahead 模式
        if (selStart < 0 || selStart == selEnd) {
            pendingBoldActive = !pendingBoldActive
            notifyButtonStates()
            return
        }

        // 选区模式
        val existingBoldSpans = editable.getSpans(selStart, selEnd, StyleSpan::class.java)
            .filter { it.style == Typeface.BOLD }

        if (existingBoldSpans.isNotEmpty()) {
            existingBoldSpans.forEach { span ->
                removeSpanFromSelection(editable, span, selStart, selEnd) {
                    StyleSpan(Typeface.BOLD)
                }
            }
            pendingBoldActive = false
        } else {
            editable.setSpan(
                StyleSpan(Typeface.BOLD), selStart, selEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            pendingBoldActive = true
        }

        notifyButtonStates()
        saveSpans()
    }

    /**
     * 切换斜体：无选区 → type-ahead 模式；有选区 → 应用/移除。
     */
    fun toggleItalic() {
        val editable = text ?: return
        val selStart = selectionStart
        val selEnd = selectionEnd

        // Type-Ahead 模式
        if (selStart < 0 || selStart == selEnd) {
            pendingItalicActive = !pendingItalicActive
            notifyButtonStates()
            return
        }

        // 选区模式
        val existingItalicSpans = editable.getSpans(selStart, selEnd, StyleSpan::class.java)
            .filter { it.style == Typeface.ITALIC }

        if (existingItalicSpans.isNotEmpty()) {
            existingItalicSpans.forEach { span ->
                removeSpanFromSelection(editable, span, selStart, selEnd) {
                    StyleSpan(Typeface.ITALIC)
                }
            }
            pendingItalicActive = false
        } else {
            editable.setSpan(
                StyleSpan(Typeface.ITALIC), selStart, selEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            pendingItalicActive = true
        }

        notifyButtonStates()
        saveSpans()
    }

    /**
     * 应用颜色：有选区 → 给选区内文字着色；无选区 → 设置 type-ahead 颜色。
     */
    fun applyColor(color: Int) {
        val editable = text ?: return
        val selStart = selectionStart
        val selEnd = selectionEnd
        val hasSelection = selStart >= 0 && selStart != selEnd

        if (hasSelection) {
            // 清除选区内的旧颜色 Span（排除保护色）
            val colorSpans = editable.getSpans(selStart, selEnd, ForegroundColorSpan::class.java)
            colorSpans.filter { it.foregroundColor != PROTECTED_COLOR_INT }.forEach { span ->
                removeSpanFromSelection(editable, span, selStart, selEnd) {
                    ForegroundColorSpan(span.foregroundColor)
                }
            }
            editable.setSpan(
                ForegroundColorSpan(color), selStart, selEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            pendingColor = color
            saveSpans()
        } else {
            pendingColor = color
        }

        notifyButtonStates()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 公开 API — 插入操作
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 在光标位置插入 #话题名（蓝色保护 Span）。
     * 调用后自动触发 [onTextContentChanged]。
     */
    fun insertTopic(topicName: String) {
        val editable = text ?: return
        var start = selectionStart
        var end = selectionEnd
        if (start < 0) { start = editable.length; end = editable.length }

        val topicText = TopicMentionRules.topicFormat(topicName)
        val spannable = SpannableStringBuilder(topicText).apply {
            setSpan(
                ForegroundColorSpan(PROTECTED_COLOR_INT),
                0, topicText.length - 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        isProgrammaticChange = true
        editable.replace(start, end, spannable)
        isProgrammaticChange = false
        setSelection(start + topicText.length)
    }

    /**
     * 在光标位置插入 @用户名（蓝色保护 Span）。
     * 调用后自动触发 [onTextContentChanged]。
     */
    fun insertMention(userName: String) {
        val editable = text ?: return
        var start = selectionStart
        var end = selectionEnd
        if (start < 0) { start = editable.length; end = editable.length }

        val mentionText = TopicMentionRules.mentionFormat(userName)
        val spannable = SpannableStringBuilder(mentionText).apply {
            setSpan(
                ForegroundColorSpan(PROTECTED_COLOR_INT),
                0, mentionText.length - 2,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        isProgrammaticChange = true
        editable.replace(start, end, spannable)
        isProgrammaticChange = false
        setSelection(start + mentionText.length)
    }

    /**
     * 在光标位置插入一个 emoji 字符。
     */
    fun insertEmoji(emoji: String) {
        val editable = text ?: return
        var start = selectionStart
        var end = selectionEnd
        if (start < 0) { start = editable.length; end = editable.length }

        isProgrammaticChange = true
        editable.replace(start, end, emoji)
        isProgrammaticChange = false
        setSelection(start + emoji.length)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 公开 API — Span 管理 / 状态恢复
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 程序化设置文本，内部设置 [isProgrammaticChange] 守卫。
     * 用于 MVI render() 回写 EditText（配置变更/草稿恢复/话题插入等外部触发的文本变更）。
     */
    fun setTextProgrammatic(text: CharSequence) {
        isProgrammaticChange = true
        setText(text)
        isProgrammaticChange = false
    }

    /**
     * 从 SpanDescriptor 列表恢复格式化 Span（粗体/斜体/颜色）。
     * 会先清除旧的格式化 Span，再从 descriptors 重新应用。
     */
    fun reapplyFormattingSpans(descriptors: List<SpanDescriptor>) {
        val editable = text ?: return
        if (descriptors.isEmpty()) return

        val textLen = editable.length

        // 清除旧的格式化 Span（保留话题/提及保护色）
        val oldStyleSpans = editable.getSpans(0, textLen, StyleSpan::class.java)
        oldStyleSpans.forEach { editable.removeSpan(it) }
        val oldColorSpans = editable.getSpans(0, textLen, ForegroundColorSpan::class.java)
        oldColorSpans.filter { it.foregroundColor != PROTECTED_COLOR_INT }
            .forEach { editable.removeSpan(it) }

        descriptors.forEach { desc ->
            val safeStart = desc.start.coerceIn(0, textLen)
            val safeEnd = desc.end.coerceIn(0, textLen)
            if (safeStart >= safeEnd) return@forEach
            when (desc.type) {
                SpanType.BOLD -> editable.setSpan(
                    StyleSpan(Typeface.BOLD), safeStart, safeEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                SpanType.ITALIC -> editable.setSpan(
                    StyleSpan(Typeface.ITALIC), safeStart, safeEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                SpanType.COLOR -> editable.setSpan(
                    ForegroundColorSpan(desc.value), safeStart, safeEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    /**
     * 重新着色所有 #话题# 和 @提及 为保护色蓝色。
     * 通常用于 setTextProgrammatic 之后恢复保护 Span。
     */
    fun reapplyProtectedSpans() {
        val editable = text ?: return
        val textStr = editable.toString()
        if (textStr.isBlank()) return

        // 清除所有旧保护色 Span
        val oldSpans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        oldSpans.filter { it.foregroundColor == PROTECTED_COLOR_INT }
            .forEach { editable.removeSpan(it) }

        // 重新匹配并着色 #话题#
        TopicMentionRules.TOPIC_PATTERN.findAll(textStr).forEach { match ->
            editable.setSpan(
                ForegroundColorSpan(PROTECTED_COLOR_INT),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 重新匹配并着色 @提及
        TopicMentionRules.MENTION_PATTERN.findAll(textStr).forEach { match ->
            editable.setSpan(
                ForegroundColorSpan(PROTECTED_COLOR_INT),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /**
     * 提取当前 EditText 中所有格式化 Span 并通知 ViewModel。
     * 用于程序化文本变更完成后手动同步 Span 状态。
     */
    fun notifySpansChanged() {
        saveSpans()
    }

    /**
     * 根据当前光标/选区位置刷新按钮状态，触发 [onButtonStatesChanged] 回调。
     */
    fun notifyButtonStates() {
        val editable = text ?: return
        val selStart = selectionStart
        val selEnd = selectionEnd

        // B 按钮：待定粗体 或 光标位置有粗体 Span
        val isBold = pendingBoldActive || run {
            val (cs, ce) = resolveCheckRange(selStart, selEnd, editable.length)
            if (cs < 0) false
            else editable.getSpans(cs, ce, StyleSpan::class.java)
                .any { it.style == Typeface.BOLD }
        }

        // I 按钮：待定斜体 或 光标位置有斜体 Span
        val isItalic = pendingItalicActive || run {
            val (cs, ce) = resolveCheckRange(selStart, selEnd, editable.length)
            if (cs < 0) false
            else editable.getSpans(cs, ce, StyleSpan::class.java)
                .any { it.style == Typeface.ITALIC }
        }

        // A 按钮：待定颜色 或 光标位置有颜色 Span（排除保护色）
        val activeColor = pendingColor ?: run {
            val (cs, ce) = resolveCheckRange(selStart, selEnd, editable.length)
            if (cs < 0) null
            else editable.getSpans(cs, ce, ForegroundColorSpan::class.java)
                .firstOrNull { it.foregroundColor != PROTECTED_COLOR_INT }
                ?.foregroundColor
        }

        onButtonStatesChanged?.invoke(isBold, isItalic, activeColor)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部 — Span 序列化
    // ═══════════════════════════════════════════════════════════════════════

    private fun saveSpans() {
        val editable = text ?: return
        val descriptors = mutableListOf<SpanDescriptor>()

        editable.getSpans(0, editable.length, StyleSpan::class.java).forEach { span ->
            val start = editable.getSpanStart(span)
            val end = editable.getSpanEnd(span)
            when (span.style) {
                Typeface.BOLD -> descriptors.add(SpanDescriptor(start, end, SpanType.BOLD))
                Typeface.ITALIC -> descriptors.add(SpanDescriptor(start, end, SpanType.ITALIC))
                Typeface.BOLD_ITALIC -> {
                    descriptors.add(SpanDescriptor(start, end, SpanType.BOLD))
                    descriptors.add(SpanDescriptor(start, end, SpanType.ITALIC))
                }
            }
        }

        editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
            .filter { it.foregroundColor != PROTECTED_COLOR_INT }
            .forEach { span ->
                descriptors.add(SpanDescriptor(
                    editable.getSpanStart(span),
                    editable.getSpanEnd(span),
                    SpanType.COLOR,
                    span.foregroundColor
                ))
            }

        onSpansChanged?.invoke(descriptors)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部 — Span 选区移除/拆分工具
    // ═══════════════════════════════════════════════════════════════════════

    private fun removeSpanFromSelection(
        editable: Editable,
        span: Any,
        selStart: Int,
        selEnd: Int,
        spanFactory: () -> Any
    ) {
        val s = editable.getSpanStart(span)
        val e = editable.getSpanEnd(span)

        when {
            s >= selStart && e <= selEnd -> editable.removeSpan(span)
            s < selStart && e > selEnd -> {
                editable.removeSpan(span)
                editable.setSpan(spanFactory(), s, selStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                editable.setSpan(spanFactory(), selEnd, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            s >= selStart -> {
                editable.removeSpan(span)
                editable.setSpan(spanFactory(), selEnd, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            else -> {
                editable.removeSpan(span)
                editable.setSpan(spanFactory(), s, selStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun resolveCheckRange(selStart: Int, selEnd: Int, textLen: Int): Pair<Int, Int> {
        if (selStart < 0) return Pair(-1, -1)
        val s = if (selStart == selEnd) selStart else selStart
        val e = if (selStart == selEnd) (selEnd + 1).coerceAtMost(textLen) else selEnd
        return Pair(s, e)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Type-Ahead TextWatcher — 自动将待定格式应用到新输入
    // ═══════════════════════════════════════════════════════════════════════

    private fun setupTypeAheadWatcher() {
        addTextChangedListener(object : TextWatcher {
            private var insertStart = 0
            private var insertLen = 0

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (isProgrammaticChange) return
                insertStart = start
                insertLen = after
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                // ---- 1. 文本内容通知（总是触发） ----
                onTextContentChanged?.invoke(s?.toString() ?: "")

                // ---- 2. Span 提取（仅在非程序化变更时） ----
                if (!isProgrammaticChange) {
                    saveSpans()
                }

                // ---- 3. Type-Ahead 待定格式应用 ----
                if (isProgrammaticChange) return
                if (s == null || insertLen <= 0) return
                if (!hasPendingFormat()) return

                val end = (insertStart + insertLen).coerceAtMost(s.length)
                if (insertStart >= end) return

                applyPendingSpansToRange(s, insertStart, end)
                // Span 已在 saveSpans() 中保存，此处仅更新按钮状态
                notifyButtonStates()
            }
        })
    }

    private fun hasPendingFormat(): Boolean =
        pendingBoldActive || pendingItalicActive || pendingColor != null

    private fun applyPendingSpansToRange(editable: Editable, start: Int, end: Int) {
        if (pendingBoldActive) {
            val existing = editable.getSpans(start, end, StyleSpan::class.java)
                .filter { it.style == Typeface.BOLD }
            existing.forEach { editable.removeSpan(it) }
            editable.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (pendingItalicActive) {
            val existing = editable.getSpans(start, end, StyleSpan::class.java)
                .filter { it.style == Typeface.ITALIC }
            existing.forEach { editable.removeSpan(it) }
            editable.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        pendingColor?.let { color ->
            val existing = editable.getSpans(start, end, ForegroundColorSpan::class.java)
                .filter { it.foregroundColor != PROTECTED_COLOR_INT }
            existing.forEach { editable.removeSpan(it) }
            editable.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SpanWatcher — #话题# 和 @提及 的块删除守卫
    // ═══════════════════════════════════════════════════════════════════════

    private fun setupSpanGuard() {
        // 1. 退格键守卫
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                val start = selectionStart
                val end = selectionEnd
                if (start == end) {
                    val editable = this.text ?: return@setOnKeyListener false
                    val ranges = extractProtectedRanges(editable)
                    when (val decision = AtomicSpanRules.resolveBackspace(start, ranges)) {
                        is AtomicSpanRules.BackspaceDecision.DeleteWholeSpan -> {
                            isProgrammaticChange = true
                            editable.delete(decision.range.start, decision.range.end)
                            isProgrammaticChange = false
                            return@setOnKeyListener true
                        }
                        is AtomicSpanRules.BackspaceDecision.DeleteSingleChar -> { /* 放行 */ }
                    }
                }
            }
            false
        }

        // 2. 光标磁吸
        setOnClickListener {
            val editable = this.text ?: return@setOnClickListener
            val ranges = extractProtectedRanges(editable)
            when (val decision = AtomicSpanRules.resolveCursorSnap(selectionStart, ranges)) {
                is AtomicSpanRules.CursorSnapDecision.SnapTo -> setSelection(decision.position)
                is AtomicSpanRules.CursorSnapDecision.Keep -> { /* 不动 */ }
            }
        }

        // 3. 选区守卫
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                super.sendAccessibilityEvent(host, eventType)
                if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                    val editable = this@RichEditText.text ?: return
                    val ranges = extractProtectedRanges(editable)
                    when (val decision = AtomicSpanRules.resolveSelection(
                        selectionStart, selectionEnd, ranges
                    )) {
                        is AtomicSpanRules.SelectionDecision.Expand ->
                            setSelection(decision.newStart, decision.newEnd)
                        is AtomicSpanRules.SelectionDecision.Keep -> { /* 不动 */ }
                    }
                }
            }
        }

        // 4. 输入拦截 InputFilter
        val existingFilters = filters
        filters = existingFilters + SpanBoundaryInputFilter()

        // 5. Span 清理 TextWatcher
        addTextChangedListener(SpanCleanupWatcher())
    }

    private fun extractProtectedRanges(editable: Editable): List<ProtectedRange> {
        return editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
            .filter { it.foregroundColor == PROTECTED_COLOR_INT }
            .map { ProtectedRange(editable.getSpanStart(it), editable.getSpanEnd(it)) }
    }

    // ── InputFilter ─────────────────────────────────────────────────

    private inner class SpanBoundaryInputFilter : InputFilter {
        override fun filter(
            source: CharSequence?,
            sp: Int,
            ep: Int,
            dest: Spanned?,
            ds: Int,
            de: Int
        ): CharSequence? {
            if (dest == null) return null
            if (isProgrammaticChange) return null
            if (de <= ds) return null

            val ranges = extractProtectedRanges(dest as Editable)
            when (val decision = AtomicSpanRules.resolveFilterExpansion(ds, de, ranges)) {
                is AtomicSpanRules.FilterExpansion.Expand -> {
                    pendingExpand = decision.newStart to decision.newEnd
                }
                is AtomicSpanRules.FilterExpansion.NoExpansion -> { /* 不拦截 */ }
            }
            return null
        }
    }

    // ── Span 清理 TextWatcher ─────────────────────────────────────

    private inner class SpanCleanupWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            val expand = pendingExpand ?: return
            pendingExpand = null
            if (s == null) return

            val (expandStart, expandEnd) = expand
            if (expandStart < 0 || expandEnd > s.length || expandStart >= expandEnd) return

            val spans = s.getSpans(expandStart, expandEnd, ForegroundColorSpan::class.java)
                .filter { it.foregroundColor == PROTECTED_COLOR_INT }
                .sortedByDescending { s.getSpanEnd(it) }

            if (spans.isEmpty()) {
                isProgrammaticChange = true
                s.delete(expandStart, expandEnd)
                isProgrammaticChange = false
                return
            }

            isProgrammaticChange = true
            for (span in spans) {
                val ss = s.getSpanStart(span)
                val se = s.getSpanEnd(span)
                if (ss in expandStart until expandEnd || se in (expandStart + 1)..expandEnd) {
                    s.delete(ss, se)
                }
            }
            isProgrammaticChange = false
        }
    }
}
