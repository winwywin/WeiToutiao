package com.example.test_micrott.domain

/**
 * 原子化 Span 的决策规则（纯 Kotlin，零 Android 依赖）。
 *
 * 只负责回答四个问题：
 * 1. 退格键该删多少？（整个块 vs 一个字符）
 * 2. 光标该不该弹回边界？弹到哪边？
 * 3. 选区该不该扩展包裹完整块？
 * 4. IME 替换该不该扩展到完整块？
 *
 * View 层（SpanWatcher）负责：
 * - 从 Editable 提取 ProtectedRange 列表传给本类
 * - 根据返回结果执行 Editable.delete() / setSelection() 等操作
 *
 * 单元测试可以直接验证决策逻辑，无需 Android 模拟器。
 */
object AtomicSpanRules {

    // ================================================================
    // 数据模型
    // ================================================================

    /** 一个保护性 Span 的范围 */
    data class ProtectedRange(val start: Int, val end: Int) {
        /** 位置是否严格在范围内部（不在边界上） */
        fun isStrictlyInside(position: Int): Boolean = position in (start + 1)..<end

        /** 位置是否在右边界 */
        fun isAtRightEdge(position: Int): Boolean = position == end
    }

    // ================================================================
    // 1. 退格键决策
    // ================================================================

    sealed interface BackspaceDecision {
        /** 删除整个保护性 Span */
        data class DeleteWholeSpan(val range: ProtectedRange) : BackspaceDecision
        /** 普通退格，删除光标前一个字符 */
        data object DeleteSingleChar : BackspaceDecision
    }

    /** 光标在右边界 → 整块删除；否则普通退格 */
    fun resolveBackspace(cursorPosition: Int, protectedRanges: List<ProtectedRange>): BackspaceDecision {
        val hit = protectedRanges.firstOrNull { it.isAtRightEdge(cursorPosition) }
        return if (hit != null) {
            BackspaceDecision.DeleteWholeSpan(hit)
        } else {
            BackspaceDecision.DeleteSingleChar
        }
    }

    // ================================================================
    // 2. 光标磁吸决策
    // ================================================================

    sealed interface CursorSnapDecision {
        /** 光标在 Span 内部 → 弹到最近的边界 */
        data class SnapTo(val position: Int) : CursorSnapDecision
        /** 光标不在 Span 内部 → 保持不动 */
        data object Keep : CursorSnapDecision
    }

    /** 光标在 Span 内部 → 弹到最近边界（中点左侧弹左，右侧弹右） */
    fun resolveCursorSnap(position: Int, protectedRanges: List<ProtectedRange>): CursorSnapDecision {
        val hit = protectedRanges.firstOrNull { it.isStrictlyInside(position) }
            ?: return CursorSnapDecision.Keep
        val snapTo = if (position < (hit.start + hit.end) / 2) hit.start else hit.end
        return CursorSnapDecision.SnapTo(snapTo)
    }

    // ================================================================
    // 3. 选区守卫决策
    // ================================================================

    sealed interface SelectionDecision {
        /** 选区边界切断了 Span → 扩展包裹完整 Span */
        data class Expand(val newStart: Int, val newEnd: Int) : SelectionDecision
        /** 选区正常，不需要调整 */
        data object Keep : SelectionDecision
    }

    /**
     * 纯光标（start==end）→ 同磁吸逻辑
     * 有选区 → 边界若在 Span 内部则扩展包裹
     */
    fun resolveSelection(
        selStart: Int,
        selEnd: Int,
        protectedRanges: List<ProtectedRange>
    ): SelectionDecision {
        if (selStart == selEnd) {
            return when (val snap = resolveCursorSnap(selStart, protectedRanges)) {
                is CursorSnapDecision.SnapTo -> SelectionDecision.Expand(snap.position, snap.position)
                is CursorSnapDecision.Keep -> SelectionDecision.Keep
            }
        }

        var newStart = selStart
        var newEnd = selEnd
        for (range in protectedRanges) {
            if (range.isStrictlyInside(selStart)) newStart = range.start
            if (range.isStrictlyInside(selEnd)) newEnd = range.end
        }
        return if (newStart != selStart || newEnd != selEnd) {
            SelectionDecision.Expand(newStart, newEnd)
        } else {
            SelectionDecision.Keep
        }
    }

    // ================================================================
    // 4. InputFilter 替换扩展决策
    // ================================================================

    sealed interface FilterExpansion {
        /** 替换操作部分覆盖了 Span → 扩展到完整范围 */
        data class Expand(val newStart: Int, val newEnd: Int) : FilterExpansion
        /** 不需要扩展 */
        data object NoExpansion : FilterExpansion
    }

    /** 替换/删除范围部分命中保护性 Span → 扩展到包含所有被命中的完整 Span */
    fun resolveFilterExpansion(
        replaceStart: Int,
        replaceEnd: Int,
        protectedRanges: List<ProtectedRange>
    ): FilterExpansion {
        var expandedStart = replaceStart
        var expandedEnd = replaceEnd
        for (range in protectedRanges) {
            // 有交集：range 的起止与替换范围重叠
            if (range.start < replaceEnd && range.end > replaceStart) {
                expandedStart = minOf(expandedStart, range.start)
                expandedEnd = maxOf(expandedEnd, range.end)
            }
        }
        return if (expandedStart != replaceStart || expandedEnd != replaceEnd) {
            FilterExpansion.Expand(expandedStart, expandedEnd)
        } else {
            FilterExpansion.NoExpansion
        }
    }
}
