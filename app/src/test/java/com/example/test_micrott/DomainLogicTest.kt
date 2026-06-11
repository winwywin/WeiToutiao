package com.example.test_micrott

import com.example.test_micrott.domain.AtomicSpanRules
import com.example.test_micrott.domain.AtomicSpanRules.BackspaceDecision
import com.example.test_micrott.domain.AtomicSpanRules.CursorSnapDecision
import com.example.test_micrott.domain.AtomicSpanRules.FilterExpansion
import com.example.test_micrott.domain.AtomicSpanRules.ProtectedRange
import com.example.test_micrott.domain.AtomicSpanRules.SelectionDecision
import com.example.test_micrott.domain.TopicMentionRules
import com.example.test_micrott.models.SpanDescriptor
import com.example.test_micrott.models.SpanType
import com.example.test_micrott.models.TopicItem
import org.junit.Assert.*
import org.junit.Test

/**
 * 纯逻辑层单元测试 — 零 Android 依赖，纯 JVM 运行。
 *
 * 覆盖：AtomicSpanRules（4 种守卫）、TopicMentionRules（正则）、SpanDescriptor（序列化）、TopicItem
 */
class DomainLogicTest {

    // ========================================================================
    // AtomicSpanRules — 退格键守卫
    // ========================================================================

    @Test
    fun resolveBackspace_cursorInsideSpan_returnsDeleteSingleChar() {
        val ranges = listOf(ProtectedRange(5, 10))
        val result = AtomicSpanRules.resolveBackspace(7, ranges)
        assertTrue("光标在 Span 内部应普通退格", result is BackspaceDecision.DeleteSingleChar)
    }

    @Test
    fun resolveBackspace_cursorAtLeftEdge_returnsDeleteSingleChar() {
        val ranges = listOf(ProtectedRange(5, 10))
        val result = AtomicSpanRules.resolveBackspace(5, ranges)
        assertTrue("光标在左边界应普通退格", result is BackspaceDecision.DeleteSingleChar)
    }

    @Test
    fun resolveBackspace_cursorAtRightEdge_returnsDeleteWholeSpan() {
        val ranges = listOf(ProtectedRange(5, 10))
        val result = AtomicSpanRules.resolveBackspace(10, ranges)
        assertTrue("光标在右边界应整块删除", result is BackspaceDecision.DeleteWholeSpan)
        val delete = result as BackspaceDecision.DeleteWholeSpan
        assertEquals("应删除范围 5-10", ProtectedRange(5, 10), delete.range)
    }

    @Test
    fun resolveBackspace_cursorOutsideSpan_returnsDeleteSingleChar() {
        val ranges = listOf(ProtectedRange(5, 10))
        val result = AtomicSpanRules.resolveBackspace(3, ranges)
        assertTrue("光标在 Span 外应普通退格", result is BackspaceDecision.DeleteSingleChar)
    }

    @Test
    fun resolveBackspace_multipleSpans_hitsRightmostAtCursor() {
        val ranges = listOf(ProtectedRange(3, 6), ProtectedRange(8, 14))
        // 光标在 14 (#2 的右边界)
        val result = AtomicSpanRules.resolveBackspace(14, ranges)
        assertTrue(result is BackspaceDecision.DeleteWholeSpan)
        assertEquals(ProtectedRange(8, 14), (result as BackspaceDecision.DeleteWholeSpan).range)
    }

    // ========================================================================
    // AtomicSpanRules — 光标磁吸
    // ========================================================================

    @Test
    fun resolveCursorSnap_cursorOnLeftHalf_snapsToLeftEdge() {
        // Span 5-10，中点 = 7.5
        val ranges = listOf(ProtectedRange(5, 10))
        val result = AtomicSpanRules.resolveCursorSnap(6, ranges)
        assertTrue(result is CursorSnapDecision.SnapTo)
        assertEquals("位置 6 < 中点 7.5 → 弹到左边界 5", 5, (result as CursorSnapDecision.SnapTo).position)
    }

    @Test
    fun resolveCursorSnap_cursorOnRightHalf_snapsToRightEdge() {
        val ranges = listOf(ProtectedRange(5, 10))
        val result = AtomicSpanRules.resolveCursorSnap(9, ranges)
        assertTrue(result is CursorSnapDecision.SnapTo)
        assertEquals("位置 9 > 中点 7.5 → 弹到右边界 10", 10, (result as CursorSnapDecision.SnapTo).position)
    }

    @Test
    fun resolveCursorSnap_cursorAtMidpoint_snapsToRight() {
        // 在中点 (=5+10)/2=7 (整数除法)，不是 < 7 → 弹右到 10
        val ranges = listOf(ProtectedRange(5, 10))
        val result = AtomicSpanRules.resolveCursorSnap(7, ranges)
        assertTrue(result is CursorSnapDecision.SnapTo)
        assertEquals(10, (result as CursorSnapDecision.SnapTo).position)
    }

    @Test
    fun resolveCursorSnap_cursorOnEdge_returnsKeep() {
        val ranges = listOf(ProtectedRange(5, 10))
        assertTrue(AtomicSpanRules.resolveCursorSnap(5, ranges) is CursorSnapDecision.Keep)
        assertTrue(AtomicSpanRules.resolveCursorSnap(10, ranges) is CursorSnapDecision.Keep)
    }

    @Test
    fun resolveCursorSnap_noSpanHit_returnsKeep() {
        val ranges = listOf(ProtectedRange(5, 10))
        assertTrue(AtomicSpanRules.resolveCursorSnap(0, ranges) is CursorSnapDecision.Keep)
        assertTrue(AtomicSpanRules.resolveCursorSnap(12, ranges) is CursorSnapDecision.Keep)
    }

    // ========================================================================
    // AtomicSpanRules — 选区守卫
    // ========================================================================

    @Test
    fun resolveSelection_cursorInsideSpan_expandsToNearEdge() {
        val ranges = listOf(ProtectedRange(5, 10))
        // cursor=7, midpoint=(5+10)/2=7(整数除法), 7<7=false → 弹右到10
        val result = AtomicSpanRules.resolveSelection(7, 7, ranges)
        assertTrue(result is SelectionDecision.Expand)
        val expand = result as SelectionDecision.Expand
        assertEquals("光标在 Span 右半 → 选区扩展为 (10,10)", 10, expand.newStart)
        assertEquals(10, expand.newEnd)
    }

    @Test
    fun resolveSelection_selStartInsideSpan_expandsToSpanStart() {
        val ranges = listOf(ProtectedRange(5, 10))
        // 选中 7-12 → selStart=7 在 5-10 内部 → newStart 扩展为 5
        val result = AtomicSpanRules.resolveSelection(7, 12, ranges)
        assertTrue(result is SelectionDecision.Expand)
        assertEquals(5, (result as SelectionDecision.Expand).newStart)
        assertEquals(12, result.newEnd)
    }

    @Test
    fun resolveSelection_selEndInsideSpan_expandsToSpanEnd() {
        val ranges = listOf(ProtectedRange(5, 10))
        val result = AtomicSpanRules.resolveSelection(2, 8, ranges)
        assertTrue(result is SelectionDecision.Expand)
        assertEquals(2, (result as SelectionDecision.Expand).newStart)
        assertEquals(10, result.newEnd)
    }

    @Test
    fun resolveSelection_bothInsideSpan_expandsFully() {
        val ranges = listOf(ProtectedRange(5, 10))
        val result = AtomicSpanRules.resolveSelection(7, 9, ranges)
        assertTrue(result is SelectionDecision.Expand)
        assertEquals(5, (result as SelectionDecision.Expand).newStart)
        assertEquals(10, result.newEnd)
    }

    @Test
    fun resolveSelection_outsideSpan_returnsKeep() {
        val ranges = listOf(ProtectedRange(5, 10))
        assertTrue(AtomicSpanRules.resolveSelection(0, 3, ranges) is SelectionDecision.Keep)
        assertTrue(AtomicSpanRules.resolveSelection(12, 15, ranges) is SelectionDecision.Keep)
    }

    // ========================================================================
    // AtomicSpanRules — InputFilter 替换扩展
    // ========================================================================

    @Test
    fun resolveFilterExpansion_replaceOverlapsSpan_expands() {
        val ranges = listOf(ProtectedRange(5, 10))
        // 替换 6-8 (命中 Span)
        val result = AtomicSpanRules.resolveFilterExpansion(6, 8, ranges)
        assertTrue(result is FilterExpansion.Expand)
        assertEquals(5, (result as FilterExpansion.Expand).newStart)
        assertEquals(10, result.newEnd)
    }

    @Test
    fun resolveFilterExpansion_replaceExactlySpan_noExpansion() {
        val ranges = listOf(ProtectedRange(5, 10))
        // 精确匹配 Span 范围 → 无需扩展
        val result = AtomicSpanRules.resolveFilterExpansion(5, 10, ranges)
        assertTrue("精确替换 Span 无需扩展", result is FilterExpansion.NoExpansion)
    }

    @Test
    fun resolveFilterExpansion_replaceOutsideSpan_noExpansion() {
        val ranges = listOf(ProtectedRange(5, 10))
        assertTrue(AtomicSpanRules.resolveFilterExpansion(0, 3, ranges) is FilterExpansion.NoExpansion)
        assertTrue(AtomicSpanRules.resolveFilterExpansion(12, 15, ranges) is FilterExpansion.NoExpansion)
        assertTrue(AtomicSpanRules.resolveFilterExpansion(10, 15, ranges) is FilterExpansion.NoExpansion)
    }

    @Test
    fun resolveFilterExpansion_replacePartiallyOverlapsMultipleSpans_expandsToCoverAll() {
        val ranges = listOf(ProtectedRange(3, 6), ProtectedRange(8, 12))
        // 替换 5-10 → 命中两个 Span
        val result = AtomicSpanRules.resolveFilterExpansion(5, 10, ranges)
        assertTrue(result is FilterExpansion.Expand)
        assertEquals(3, (result as FilterExpansion.Expand).newStart)
        assertEquals(12, result.newEnd)
    }

    // ========================================================================
    // ProtectedRange — 辅助方法
    // ========================================================================

    @Test
    fun protectedRange_isStrictlyInside() {
        val range = ProtectedRange(5, 10)
        assertFalse(range.isStrictlyInside(5))
        assertTrue(range.isStrictlyInside(6))
        assertTrue(range.isStrictlyInside(9))
        assertFalse(range.isStrictlyInside(10))
        assertFalse(range.isStrictlyInside(4))
    }

    @Test
    fun protectedRange_isAtRightEdge() {
        val range = ProtectedRange(5, 10)
        assertTrue(range.isAtRightEdge(10))
        assertFalse(range.isAtRightEdge(9))
        assertFalse(range.isAtRightEdge(5))
    }

    // ========================================================================
    // TopicMentionRules — 正则匹配
    // ========================================================================

    @Test
    fun topicPattern_matchesSimpleTopic() {
        val result = TopicMentionRules.TOPIC_PATTERN.findAll("#科技# 今日热点")
        val list = result.toList()
        assertEquals("应匹配1个话题", 1, list.size)
        assertEquals("应匹配 #科技#", "#科技#", list[0].value)
    }

    @Test
    fun topicPattern_matchesMultipleTopics() {
        val result = TopicMentionRules.TOPIC_PATTERN.findAll("关于 #AI# 和 #科技# 的讨论")
        val list = result.toList()
        assertEquals("应匹配2个话题", 2, list.size)
        assertEquals("#AI#", list[0].value)
        assertEquals("#科技#", list[1].value)
    }

    @Test
    fun topicPattern_noMatch_whenNoTopic() {
        val result = TopicMentionRules.TOPIC_PATTERN.find("这是一条普通文本")
        assertNull("没有#号时不应匹配", result)
    }

    @Test
    fun topicPattern_noMatch_whenUnclosedHash() {
        val result = TopicMentionRules.TOPIC_PATTERN.find("这是一个#未闭合的话题")
        assertNull("未闭合的#号不应匹配", result)
    }

    @Test
    fun mentionPattern_matchesSimpleMention() {
        val text = "感谢@张三 的帮助"
        val result = TopicMentionRules.MENTION_PATTERN.find(text)
        assertNotNull("应匹配 @张三", result)
        assertEquals("@张三", result?.value)
    }

    @Test
    fun mentionPattern_matchesMentionWithZeroWidthSpace() {
        val text = "感谢@张三​ 的帮助"
        val result = TopicMentionRules.MENTION_PATTERN.find(text)
        assertNotNull(result)
        assertEquals("@张三", result?.value)
    }

    @Test
    fun mentionPattern_afterHash_matchesBecauseRegexIncludesHash() {
        val text = "讨论#@科技#"
        val result = TopicMentionRules.MENTION_PATTERN.find(text)
        // MENTION_PATTERN = @[^\s@#​]+(?=[\s​@#]|$)
        // 其中 ​ 是零宽空格
        // "讨论#@科技#" → @科技 前面的字符是 #，不在 [^\s@#​] 的排除集中，
        // 但 lookahead 锚定的下一个字符是 #（在 [\s​@#] 中），所以匹配成功
        assertNotNull("@在#后仍可能被正则命中（取决于前后字符）", result)
    }

    // ========================================================================
    // TopicMentionRules — 格式化模板
    // ========================================================================

    @Test
    fun topicFormat_correctOutput() {
        assertEquals("#科技# ", TopicMentionRules.topicFormat("科技"))
    }

    @Test
    fun mentionFormat_containsZeroWidthSpace() {
        val result = TopicMentionRules.mentionFormat("张三")
        assertTrue("提及应包含零宽空格", result.contains("​"))
        assertTrue("提及应以空格结尾", result.endsWith(" "))
        assertEquals("@张三​ ", result)
    }

    // ========================================================================
    // SpanDescriptor — 序列化/反序列化
    // ========================================================================

    @Test
    fun spanDescriptor_serializeBold() {
        val span = SpanDescriptor(5, 12, SpanType.BOLD)
        assertEquals("5|12|0|0", span.serialize())
    }

    @Test
    fun spanDescriptor_serializeItalic() {
        val span = SpanDescriptor(3, 8, SpanType.ITALIC)
        assertEquals("3|8|1|0", span.serialize())
    }

    @Test
    fun spanDescriptor_serializeColor() {
        val span = SpanDescriptor(8, 15, SpanType.COLOR, 0xFF0000)
        assertEquals("8|15|2|16711680", span.serialize())
    }

    @Test
    fun spanDescriptor_roundTrip() {
        val original = SpanDescriptor(10, 20, SpanType.COLOR, 0x2A62FF)
        val serialized = original.serialize()
        val deserialized = SpanDescriptor.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun spanDescriptor_deserialize_badFormat_returnsNull() {
        assertNull(SpanDescriptor.deserialize("invalid"))
        assertNull(SpanDescriptor.deserialize("1|2|3"))
        assertNull(SpanDescriptor.deserialize("a|b|c|d"))
        assertNull(SpanDescriptor.deserialize("1|2|9|0")) // type=9 unknown
    }

    @Test
    fun spanDescriptor_serializeList() {
        val list = listOf(
            SpanDescriptor(0, 5, SpanType.BOLD),
            SpanDescriptor(5, 10, SpanType.ITALIC)
        )
        val serialized = SpanDescriptor.serializeList(list)
        assertEquals(2, serialized.size)
        assertEquals("0|5|0|0", serialized[0])
        assertEquals("5|10|1|0", serialized[1])
    }

    @Test
    fun spanDescriptor_deserializeList() {
        val strings = arrayListOf("0|5|0|0", "5|10|1|0")
        val list = SpanDescriptor.deserializeList(strings)
        assertEquals(2, list.size)
        assertEquals(SpanType.BOLD, list[0].type)
        assertEquals(SpanType.ITALIC, list[1].type)
    }

    @Test
    fun spanDescriptor_deserializeList_nullInput() {
        val list = SpanDescriptor.deserializeList(null)
        assertTrue("null 输入应返回空列表", list.isEmpty())
    }

    // ========================================================================
    // TopicItem — displayText
    // ========================================================================

    @Test
    fun topicItem_displayText_wrapsWithHash() {
        val item = TopicItem("t1", "科技")
        assertEquals("#科技#", item.displayText)
    }

    @Test
    fun topicItem_defaultHotTopics_count() {
        assertEquals("应有10个默认热门话题", 10, TopicItem.DEFAULT_HOT_TOPICS.size)
    }

    @Test
    fun topicItem_defaultHotTopics_containsExpected() {
        assertTrue(TopicItem.DEFAULT_HOT_TOPICS.any { it.name == "天涯社区回归" })
        assertTrue(TopicItem.DEFAULT_HOT_TOPICS.any { it.name == "AI人工智能" })
    }

    @Test
    fun topicItem_hotIndexOrdered() {
        val topics = TopicItem.DEFAULT_HOT_TOPICS
        // 验证热度编号 1-10
        val indices = topics.map { it.hotIndex }.sorted()
        assertEquals((1..10).toList(), indices)
    }
}
