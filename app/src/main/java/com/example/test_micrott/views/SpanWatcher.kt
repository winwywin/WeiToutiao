package com.example.test_micrott.views

import android.text.Editable
import android.text.InputFilter
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import androidx.core.graphics.toColorInt

/**
 * #话题# 和 @提及 的块删除守卫。
 *
 * 功能：
 * 1. ~~退格键守卫~~ — 光标在 Span 边界按 DEL，整块删除（onKeyListener）
 * 2. ~~光标磁吸~~ — 触摸 Span 内部时自动弹回边界（onClickListener）
 * 3. ~~选区守卫~~ — 长按拖选时强制扩展选区分界（AccessibilityDelegate）
 * 4. ~~输入拦截~~ — IME 替换或部分删除 Span 时整体删除（InputFilter）
 *
 * 使用方式：
 * ```kotlin
 * SpanWatcher.attach(editText, topicSpanColor = "#2A62FF".toColorInt())
 * ```
 */
class SpanWatcher private constructor(
    private val editText: EditText,
    private val topicSpanColor: Int
) {
    companion object {
        /** 默认保护色：微头条话题/提及统一蓝色 #2A62FF */
        const val DEFAULT_TOPIC_COLOR = "#2A62FF"

        /**
         * 将 SpanWatcher 附加到 EditText，自动注册所有守卫。
         * @param editText 目标输入框
         * @param topicSpanColor 保护色（ForegroundColorSpan 的颜色值），默认 #2A62FF
         * @return SpanWatcher 实例（Activity/Fragment 销毁时无需手动解绑）
         */
        fun attach(
            editText: EditText,
            topicSpanColor: Int = DEFAULT_TOPIC_COLOR.toColorInt()
        ): SpanWatcher {
            return SpanWatcher(editText, topicSpanColor)
        }
    }

    // ── 内部状态 ────────────────────────────────────────────────────────

    /** 是否正在执行程序化文本变更（防止递归触发守卫） */
    @Volatile
    var isProgrammaticChange: Boolean = false

    // ═══════════════════════════════════════════════════════════════════
    // 1. 退格键守卫 — 光标在 Span 右边界按 DEL → 整块删除
    // ═══════════════════════════════════════════════════════════════════

    init {
        editText.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                val start = editText.selectionStart
                val end = editText.selectionEnd
                if (start == end) {
                    val editable = editText.text ?: return@setOnKeyListener false
                    val spans = editable.getSpans(start, start, ForegroundColorSpan::class.java)
                    for (span in spans) {
                        if (span.foregroundColor != topicSpanColor) continue
                        val spanEnd = editable.getSpanEnd(span)
                        if (start == spanEnd) {
                            val spanStart = editable.getSpanStart(span)
                            isProgrammaticChange = true
                            editable.delete(spanStart, spanEnd)
                            isProgrammaticChange = false
                            return@setOnKeyListener true
                        }
                    }
                }
            }
            false
        }

        // ═══════════════════════════════════════════════════════════════════
        // 2. 光标磁吸 — 触摸 Span 内部时弹回最近的边界
        // ═══════════════════════════════════════════════════════════════════

        editText.setOnClickListener {
            snapCursorToBoundary()
        }

        // ═══════════════════════════════════════════════════════════════════
        // 3. 选区守卫 — 长按拖选时强制选区分界贴合 Span 边界
        // ═══════════════════════════════════════════════════════════════════

        editText.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                super.sendAccessibilityEvent(host, eventType)
                if (eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                    enforceSelectionBoundary()
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // 4. 输入拦截 — 防止 IME 替换或选区部分删除破坏 Span
        // ═══════════════════════════════════════════════════════════════════

        val existingFilters = editText.filters
        val newFilter = SpanBoundaryInputFilter()
        editText.filters = existingFilters + newFilter

        editText.addTextChangedListener(SpanCleanupWatcher())
    }

    // ──────────────────────────────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────────────────────────────

    /** 光标磁吸：如果光标在 Span 内部，弹到最近的 # 边界 */
    private fun snapCursorToBoundary() {
        val position = editText.selectionStart
        val editable = editText.text ?: return
        val spans = editable.getSpans(position, position, ForegroundColorSpan::class.java)
        for (span in spans) {
            if (span.foregroundColor != topicSpanColor) continue
            val start = editable.getSpanStart(span)
            val end = editable.getSpanEnd(span)
            if (position in (start + 1)..<end) {
                editText.setSelection(
                    if (position < (start + end) / 2) start else end
                )
                break
            }
        }
    }

    /** 选区边界强制对齐：确保选区的起止点不在 Span 内部 */
    private fun enforceSelectionBoundary() {
        val start = editText.selectionStart
        val end = editText.selectionEnd
        val editable = editText.text ?: return

        val spans = editable.getSpans(start, end, ForegroundColorSpan::class.java)
        for (span in spans) {
            if (span.foregroundColor != topicSpanColor) continue
            val spanStart = editable.getSpanStart(span)
            val spanEnd = editable.getSpanEnd(span)

            if (start == end) {
                // 纯光标：如果陷在 Span 内部 → 弹回边界
                if (start in (spanStart + 1)..<spanEnd) {
                    editText.setSelection(
                        if (start < (spanStart + spanEnd) / 2) spanStart else spanEnd
                    )
                    return
                }
            } else {
                // 有选区：如果边界斩断了 Span → 扩大选区包裹完整 Span
                var newStart = start
                var newEnd = end
                if (start in (spanStart + 1)..<spanEnd) newStart = spanStart
                if (end in (spanStart + 1)..<spanEnd) newEnd = spanEnd
                if (newStart != start || newEnd != end) {
                    editText.setSelection(newStart, newEnd)
                    return
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // InputFilter：拦截部分删除/替换保护色 Span 的操作
    // ═══════════════════════════════════════════════════════════════════

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

            // 只拦截有删除动作的操作（de > ds 表示有文本被替换/删除）
            if (de <= ds) return null

            val checkEnd = maxOf(de, ds + 1)
            val spans = dest.getSpans(ds, checkEnd, ForegroundColorSpan::class.java)
                .filter { it.foregroundColor == topicSpanColor }

            if (spans.isEmpty()) return null

            // 计算需要扩展到的完整区间（包含所有被部分命中的 Span）
            var expandedDs = ds
            var expandedDe = de
            for (span in spans) {
                val ss = dest.getSpanStart(span)
                val se = dest.getSpanEnd(span)
                if (ss < de && se > ds) {
                    expandedDs = minOf(expandedDs, ss)
                    expandedDe = maxOf(expandedDe, se)
                }
            }

            if (expandedDs == ds && expandedDe == de) return null

            // 记录需要清理的区间，由 TextWatcher 完成实际删除
            pendingExpand = expandedDs to expandedDe
            return null
        }
    }

    /** InputFilter 检测到需要扩展的区间，由 afterTextChanged 消费 */
    @Volatile
    private var pendingExpand: Pair<Int, Int>? = null

    // ═══════════════════════════════════════════════════════════════════
    // TextWatcher：清理 InputFilter 标记的半破坏 Span
    // ═══════════════════════════════════════════════════════════════════

    private inner class SpanCleanupWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            val expand = pendingExpand ?: return
            pendingExpand = null
            if (s == null) return

            val (expandStart, expandEnd) = expand
            if (expandStart < 0 || expandEnd > s.length || expandStart >= expandEnd) return

            // 找到该区间内的保护色 Span 并整体删除
            val spans = s.getSpans(expandStart, expandEnd, ForegroundColorSpan::class.java)
                .filter { it.foregroundColor == topicSpanColor }
                .sortedByDescending { s.getSpanEnd(it) } // 从后往前删，避免 offset 偏移

            if (spans.isEmpty()) {
                // 没有 Span 但区间内可能有残留的 #...# 文本 → 直接删除整个区间
                isProgrammaticChange = true
                s.delete(expandStart, expandEnd)
                isProgrammaticChange = false
                return
            }

            var anyDeleted = false
            isProgrammaticChange = true
            for (span in spans) {
                val ss = s.getSpanStart(span)
                val se = s.getSpanEnd(span)
                if (ss in expandStart until expandEnd || se in (expandStart + 1)..expandEnd) {
                    s.delete(ss, se)
                    anyDeleted = true
                }
            }
            isProgrammaticChange = false

            // 如果有删除，把光标放到被删 Span 的位置
            if (anyDeleted) {
                // 光标自然落在删除点，无需手动设置
            }
        }
    }
}
