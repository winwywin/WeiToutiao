package com.example.test_micrott.models

/**
 * 热门话题条目。
 *
 * 存储格式对齐今日头条 WTT 微头条发布页面话题选择器：
 *   - id: 话题唯一标识（后续对接真实 API 时对应 topic_id）
 *   - name: 话题展示名称（不含 # 包裹符号）
 *   - hotIndex: 热度排名（1-10），数字越小越热
 */
data class TopicItem(
    val id: String,
    val name: String,
    val hotIndex: Int = 0
) {
    /** 插入到编辑器中的完整话题文本，如 #天涯社区回归# */
    val displayText: String get() = "#$name#"

    companion object {
        /**
         * 默认热门话题列表（基于今日头条 2026-06-01 实时热搜编制）。
         * 每次从 PublishState 注入时可 shuffle 以模拟"刷新"效果。
         */
        val DEFAULT_HOT_TOPICS: List<TopicItem> = listOf(
            TopicItem("t1", "天涯社区回归", 1),
            TopicItem("t2", "歌手2026", 2),
            TopicItem("t3", "六一儿童节", 3),
            TopicItem("t4", "国资国企改革", 4),
            TopicItem("t5", "AI人工智能", 5),
            TopicItem("t6", "小米17T系列发布", 6),
            TopicItem("t7", "美伊局势", 7),
            TopicItem("t8", "存储器价格暴涨", 8),
            TopicItem("t9", "樊振东三冠王", 9),
            TopicItem("t10", "日系车溃败", 10),
        )
    }
}
