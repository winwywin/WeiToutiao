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
import com.example.test_micrott.domain.AtomicSpanRules
import com.example.test_micrott.domain.AtomicSpanRules.ProtectedRange
import com.example.test_micrott.domain.TopicMentionRules

/**
 * #话题# 和 @提及 的块删除守卫。
 *
 * 职责：拦截 EditText 层面的键盘/触摸/IME/选区事件，提取保护性 Span 范围，
 * 委托 [AtomicSpanRules] 做决策，再执行返回结果。
 *
 * 决策逻辑（该删多少、该弹到哪）在纯 Kotlin 的 AtomicSpanRules 中，
 * 本类只负责 Android 框架的事件拦截和 Editable 操作。
 */
class SpanWatcher private constructor(
    private val editText: EditText,
    private val topicSpanColor: Int
) {
    companion object {
        /**
         * 将 SpanWatcher 附加到 EditText，自动注册所有守卫。
         * @param editText 目标输入框
         * @param topicSpanColor 保护色（ForegroundColorSpan 的颜色值），默认取 TopicMentionRules
         * @return SpanWatcher 实例（Activity/Fragment 销毁时无需手动解绑）
         */
        fun attach(
            editText: EditText,
            topicSpanColor: Int = TopicMentionRules.PROTECTED_COLOR.toColorInt()
        ): SpanWatcher {
            return SpanWatcher(editText, topicSpanColor)
        }
    }

    // ── 内部状态 ────────────────────────────────────────────────────────

    /** 是否正在执行程序化文本变更（防止递归触发守卫） */
    @Volatile
    var isProgrammaticChange: Boolean = false

    // ═══════════════════════════════════════════════════════════════════
    // 注册所有守卫
    // ═══════════════════════════════════════════════════════════════════

    init {
        // 1. 退格键守卫
        editText.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                val start = editText.selectionStart
                val end = editText.selectionEnd
                if (start == end) {
                    val editable = editText.text ?: return@setOnKeyListener false
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
        editText.setOnClickListener {
            val editable = editText.text ?: return@setOnClickListener
            val ranges = extractProtectedRanges(editable)
            when (val decision = AtomicSpanRules.resolveCursorSnap(editText.selectionStart, ranges)) {
                is AtomicSpanRules.CursorSnapDecision.SnapTo -> editText.setSelection(decision.position)
                is AtomicSpanRules.CursorSnapDecision.Keep -> { /* 不动 */ }
            }
        }

        // 3. 选区守卫
        editText.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                super.sendAccessibilityEvent(host, eventType)
                if (eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                    val editable = editText.text ?: return
                    val ranges = extractProtectedRanges(editable)
                    when (val decision = AtomicSpanRules.resolveSelection(
                        editText.selectionStart, editText.selectionEnd, ranges
                    )) {
                        is AtomicSpanRules.SelectionDecision.Expand ->
                            editText.setSelection(decision.newStart, decision.newEnd)
                        is AtomicSpanRules.SelectionDecision.Keep -> { /* 不动 */ }
                    }
                }
            }
        }

        // 4. 输入拦截
        val existingFilters = editText.filters
        val newFilter = SpanBoundaryInputFilter()
        editText.filters = existingFilters + newFilter

        editText.addTextChangedListener(SpanCleanupWatcher())
    }

    // ──────────────────────────────────────────────────────────────────
    // 辅助：从 Editable 提取保护性 Span 范围
    // ──────────────────────────────────────────────────────────────────

    private fun extractProtectedRanges(editable: Editable): List<ProtectedRange> {
        return editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
            .filter { it.foregroundColor == topicSpanColor }
            .map { ProtectedRange(editable.getSpanStart(it), editable.getSpanEnd(it)) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // InputFilter：委托 AtomicSpanRules 做扩展决策
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

    /** InputFilter 标记的待扩展区间，由 afterTextChanged 消费 */
    @Volatile
    private var pendingExpand: Pair<Int, Int>? = null

    // ═══════════════════════════════════════════════════════════════════
    // TextWatcher：执行 InputFilter 标记的删除（执行层细节）
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

            // 找到该区间内的保护色 Span 并从后往前整体删除
            val spans = s.getSpans(expandStart, expandEnd, ForegroundColorSpan::class.java)
                .filter { it.foregroundColor == topicSpanColor }
                .sortedByDescending { s.getSpanEnd(it) }

            if (spans.isEmpty()) {
                // 没有 Span 但区间内有残留文本 → 直接删除整个区间
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
