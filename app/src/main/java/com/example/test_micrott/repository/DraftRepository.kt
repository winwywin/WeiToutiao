package com.example.test_micrott.repository

import android.net.Uri
import com.example.test_micrott.models.SpanDescriptor

/**
 * 草稿仓库接口 — Data 层的抽象边界。
 *
 * ViewModel / Activity 只依赖此接口，不直接依赖 DraftManager。
 * 将来换 Room / DataStore / 远端 API，只需新增实现类，ViewModel 零改动。
 */
interface DraftRepository {

    /** 是否存在任意草稿 */
    fun hasDraft(): Boolean

    /** 获取所有永久草稿的摘要列表（草稿箱列表用），按 saved_at 逆序 */
    fun getAllDrafts(): List<DraftSummary>

    /** 读取单条草稿的完整数据（用于恢复到编辑器） */
    suspend fun getDraft(id: Long): DraftData?

    /**
     * 保存永久草稿（退出弹窗用户主动确认）。
     *
     * @param updateDraftId 非 null 时 UPDATE 该记录（从草稿箱恢复后修改），null 时 INSERT 新记录
     */
    suspend fun saveDraft(
        text: String,
        imageUris: List<Uri>,
        formatSpans: List<SpanDescriptor>,
        updateDraftId: Long? = null
    ): Long

    /** 保存临时草稿（onPause 自动调用，防抖机制） */
    suspend fun saveTemporaryDraft(
        text: String,
        imageUris: List<Uri>,
        formatSpans: List<SpanDescriptor>
    ): Long

    /** 将所有临时草稿升级为永久（onStop：用户真的离开了） */
    fun markAllTemporaryPermanent()

    /** 删除所有临时草稿及关联图片文件（onResume：用户回来了） */
    fun deleteAllTemporaryDrafts()

    /** 删除指定草稿及关联图片文件 */
    fun deleteDraft(id: Long)
}

// ========================================================================
// 数据类
// ========================================================================

/**
 * 草稿摘要（草稿箱列表项展示用）。
 */
data class DraftSummary(
    val id: Long,
    val textPreview: String,
    val textLength: Int,
    val imageCount: Int,
    val savedAt: Long
) {
    /** 摘要预览文字："128字，3张图片" */
    fun toStatsText(): String {
        val parts = mutableListOf<String>()
        if (textLength > 0) parts.add("${textLength}字")
        if (imageCount > 0) parts.add("${imageCount}张图")
        return if (parts.isEmpty()) "空草稿" else parts.joinToString("，")
    }

    /** 相对时间（如 "3分钟前"、"昨天 14:30"） */
    fun toRelativeTime(): String {
        val now = System.currentTimeMillis()
        val diff = now - savedAt
        if (diff < 0) return "刚刚"

        val minutes = diff / 60_000
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            days < 2 -> "昨天 ${
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(savedAt))
            }"
            days < 7 -> "${days}天前"
            else -> java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(savedAt))
        }
    }
}

/**
 * 草稿完整数据（恢复到编辑器用）。
 */
data class DraftData(
    val text: String,
    val images: List<Uri>,
    val formatSpans: List<SpanDescriptor>,
    val savedAt: Long
)
