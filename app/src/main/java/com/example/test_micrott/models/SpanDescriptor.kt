package com.example.test_micrott.models

/**
 * 格式化 Span 描述符。
 *
 * 通过自定义字符串序列化（无需 kotlinx-parcelize）存入 SavedStateHandle。
 * 格式: "start|end|type|value"
 *   type: 0=BOLD, 1=ITALIC, 2=COLOR
 *   value: color int（仅 COLOR 类型使用，其他填 0）
 *
 * 例: "5|12|0|0"  = 位置 5-12 粗体
 *     "8|15|2|16711680" = 位置 8-15 红色
 */
data class SpanDescriptor(
    val start: Int,
    val end: Int,
    val type: SpanType,
    val value: Int = 0
) {
    companion object {
        private const val SEP_FIELD = "|"

        /** 将 List<SpanDescriptor> 序列化为 ArrayList<String>（SavedStateHandle 兼容） */
        fun serializeList(list: List<SpanDescriptor>): ArrayList<String> {
            return ArrayList(list.map { it.serialize() })
        }

        /** 从 ArrayList<String> 反序列化为 List<SpanDescriptor> */
        fun deserializeList(strings: ArrayList<String>?): List<SpanDescriptor> {
            if (strings == null) return emptyList()
            return strings.mapNotNull { deserialize(it) }
        }

        /** 从单行字符串反序列化 */
        fun deserialize(raw: String): SpanDescriptor? {
            val parts = raw.split(SEP_FIELD)
            if (parts.size != 4) return null
            return try {
                SpanDescriptor(
                    start = parts[0].toInt(),
                    end = parts[1].toInt(),
                    type = when (parts[2].toInt()) {
                        0 -> SpanType.BOLD
                        1 -> SpanType.ITALIC
                        2 -> SpanType.COLOR
                        else -> return null
                    },
                    value = parts[3].toInt()
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
    }

    /** 序列化为单行字符串 */
    fun serialize(): String {
        val typeCode = when (type) {
            SpanType.BOLD -> 0
            SpanType.ITALIC -> 1
            SpanType.COLOR -> 2
        }
        return "$start$SEP_FIELD$end$SEP_FIELD$typeCode$SEP_FIELD$value"
    }
}

enum class SpanType {
    BOLD, ITALIC, COLOR
}
