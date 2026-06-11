package com.example.test_micrott.domain

/**
 * 话题 / 提及 的文本识别规则（纯 Kotlin，零 Android 依赖）。
 *
 * 集中管理所有「什么样的文本算话题 / 提及」的正则模式、格式化模板和保护色常量，
 * 确保 View 层的渲染、插入、守卫逻辑引用同一套规则，杜绝正则不一致导致的视觉错位。
 *
 * 单元测试可以直接验证这些正则，无需 Android 模拟器。
 */
object TopicMentionRules {

    /** 话题 / 提及的统一保护色 */
    const val PROTECTED_COLOR = "#2A62FF"

    /**
     * 零宽空格：用作 @提及 的不可见边界标记。
     * 插在 @用户名 之后、空格之前，防止与后续文本粘连。
     */
    const val ZERO_WIDTH_SPACE = "\u200B"

    // ================================================================
    // 匹配正则 — 用于 reapplyProtectedSpans() 在已有文本中识别话题 / 提及
    // ================================================================

    /** 匹配 #话题名# 格式（两端各一个 #） */
    val TOPIC_PATTERN = Regex("#[^#]*#")

    /** 匹配 @用户名（以零宽空格 / 空白 / @ / # 为边界） */
    val MENTION_PATTERN = Regex("@[^\\s@#${ZERO_WIDTH_SPACE}]+(?=[\\s${ZERO_WIDTH_SPACE}@#]|$)")

    // ================================================================
    // 格式化模板 — 用于插入时构造显示文本
    // ================================================================

    /** 构造话题显示文本，例如 "#科技# "（尾部空格作为自然分隔） */
    fun topicFormat(name: String): String = "#$name# "

    /** 构造提及显示文本，例如 "@张三 "（零宽空格 + 尾部空格） */
    fun mentionFormat(name: String): String = "@$name$ZERO_WIDTH_SPACE "
}
