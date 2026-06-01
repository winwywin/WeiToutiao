package com.example.test_micrott.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.test_micrott.model.SpanDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 微头条草稿自动保存管理器
 *
 * 存储格式（参照今日头条 WTT 草稿结构）:
 *   JSON 文件: filesDir/wtt_draft.json
 *   图片缓存:  filesDir/draft_images/draft_img_*.jpg
 *
 * JSON schema:
 * {
 *   "version": 1,
 *   "text": "...",
 *   "images": ["/data/data/.../draft_img_0.jpg", ...],
 *   "formatSpans": ["5|12|0|0", "8|15|2|16711680"],
 *   "savedAt": 1717200000000
 * }
 */
class DraftManager(private val context: Context) {

    companion object {
        private const val TAG = "DraftManager"

        // ================================================================
        // 文件路径
        // ================================================================
        private const val DRAFT_FILE_NAME = "wtt_draft.json"
        private const val DRAFT_IMG_DIR = "draft_images"

        // ================================================================
        // JSON Key（与 WTT 草稿字段对齐）
        // ================================================================
        private const val KEY_VERSION = "version"
        private const val KEY_TEXT = "text"
        private const val KEY_IMAGES = "images"
        private const val KEY_FORMAT_SPANS = "formatSpans"
        private const val KEY_SAVED_AT = "savedAt"

        private const val CURRENT_VERSION = 1
    }

    // ================================================================
    // 文件路径
    // ================================================================

    /** 草稿 JSON 文件 */
    private val draftFile: File
        get() = File(context.filesDir, DRAFT_FILE_NAME)

    /** 草稿图片缓存目录（自动创建） */
    private val draftImgDir: File
        get() = File(context.filesDir, DRAFT_IMG_DIR).also { it.mkdirs() }

    // ================================================================
    // 公开 API
    // ================================================================

    /** 是否存在有效草稿 */
    fun hasDraft(): Boolean = draftFile.exists() && draftFile.length() > 0

    /**
     * 读取草稿元信息（轻量级，用于弹窗预览）。
     * 不解析图片和 Span，仅读取 text 长度和图片数量。
     *
     * @return DraftMeta 或 null（无草稿/解析失败）
     */
    fun getDraftMeta(): DraftMeta? {
        if (!hasDraft()) return null
        return try {
            val json = JSONObject(draftFile.readText())
            DraftMeta(
                textLength = json.optString(KEY_TEXT, "").length,
                imageCount = json.optJSONArray(KEY_IMAGES)?.length() ?: 0,
                savedAt = json.optLong(KEY_SAVED_AT, 0L)
            )
        } catch (e: Exception) {
            Log.w(TAG, "getDraftMeta failed", e)
            null
        }
    }

    /**
     * 完整加载草稿（用于恢复）。
     * 包含文本、图片 Uri、格式化 Span 描述符。
     *
     * @return DraftData 或 null（无草稿/解析失败）
     */
    fun loadDraft(): DraftData? {
        if (!hasDraft()) return null
        return try {
            val json = JSONObject(draftFile.readText())

            // 版本校验（未来升级兼容）
            val version = json.optInt(KEY_VERSION, 1)
            if (version != CURRENT_VERSION) {
                Log.w(TAG, "Draft version mismatch: $version != $CURRENT_VERSION")
            }

            // 图片路径 → Uri（过滤已删除的文件）
            val imagesArr = json.optJSONArray(KEY_IMAGES) ?: JSONArray()
            val imageUris = mutableListOf<Uri>()
            for (i in 0 until imagesArr.length()) {
                val path = imagesArr.getString(i)
                val file = File(path)
                if (file.exists()) {
                    imageUris.add(Uri.fromFile(file))
                } else {
                    Log.d(TAG, "Draft image file missing, skip: $path")
                }
            }

            // 格式化 Span 反序列化
            val spansArr = json.optJSONArray(KEY_FORMAT_SPANS) ?: JSONArray()
            val spans = mutableListOf<SpanDescriptor>()
            for (i in 0 until spansArr.length()) {
                SpanDescriptor.deserialize(spansArr.getString(i))?.let { spans.add(it) }
            }

            DraftData(
                text = json.optString(KEY_TEXT, ""),
                images = imageUris,
                formatSpans = spans,
                savedAt = json.optLong(KEY_SAVED_AT, 0L)
            )
        } catch (e: Exception) {
            Log.w(TAG, "loadDraft failed", e)
            null
        }
    }

    /**
     * 保存草稿（IO 操作，需在协程中调用）。
     *
     * 流程：
     *   1. 清空旧图片缓存目录
     *   2. 逐一复制 content:// Uri 图片到 app-private 目录
     *   3. 写入 JSON（文本 + 本地图片路径 + Span 描述符）
     *
     * @param text        输入框文本
     * @param imageUris   选中的图片 Uri 列表
     * @param formatSpans 格式化 Span 描述符列表
     */
    suspend fun saveDraft(
        text: String,
        imageUris: List<Uri>,
        formatSpans: List<SpanDescriptor>
    ) = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()

            // Step 1: 清空旧图片
            clearDraftImages()

            // Step 2: 复制图片到 app-private 存储
            val savedPaths = mutableListOf<String>()
            imageUris.forEachIndexed { index, uri ->
                try {
                    val ext = guessImageExtension(uri)
                    val destFile = File(draftImgDir, "draft_img_${index}.$ext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    savedPaths.add(destFile.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to copy draft image #$index: ${e.message}")
                }
            }

            // Step 3: 构建 JSON
            val json = JSONObject().apply {
                put(KEY_VERSION, CURRENT_VERSION)
                put(KEY_TEXT, text)
                put(KEY_IMAGES, JSONArray(savedPaths))
                put(KEY_FORMAT_SPANS, JSONArray(formatSpans.map { it.serialize() }))
                put(KEY_SAVED_AT, startTime)
            }

            draftFile.writeText(json.toString())
            Log.d(TAG, "Draft saved: text=${text.length}chars, images=${savedPaths.size}, spans=${formatSpans.size}, took=${System.currentTimeMillis() - startTime}ms")
        } catch (e: Exception) {
            Log.w(TAG, "saveDraft failed", e)
        }
    }

    /** 删除草稿（JSON + 图片缓存目录） */
    fun deleteDraft() {
        try {
            if (draftFile.exists()) draftFile.delete()
            clearDraftImages()
            Log.d(TAG, "Draft deleted")
        } catch (e: Exception) {
            Log.w(TAG, "deleteDraft failed", e)
        }
    }

    /** 估算草稿文件总大小（bytes） */
    fun estimateSize(): Long {
        var size = if (draftFile.exists()) draftFile.length() else 0L
        if (draftImgDir.exists()) {
            draftImgDir.listFiles()?.forEach { size += it.length() }
        }
        return size
    }

    // ================================================================
    // 内部工具
    // ================================================================

    /** 清空草稿图片目录 */
    private fun clearDraftImages() {
        if (draftImgDir.exists()) {
            draftImgDir.listFiles()?.forEach { it.delete() }
        }
    }

    /** 从 Uri 推断图片扩展名 */
    private fun guessImageExtension(uri: Uri): String {
        // 尝试从 ContentResolver 获取 MIME type
        val mime = context.contentResolver.getType(uri)
        return when {
            mime == "image/png" -> "png"
            mime == "image/webp" -> "webp"
            mime == "image/gif" -> "gif"
            else -> "jpg" // 默认 JPEG
        }
    }
}

// ========================================================================
// 数据类
// ========================================================================

/**
 * 草稿元信息（轻量级，用于弹窗预览）。
 * 不包含实际文本和图片数据，仅统计摘要。
 */
data class DraftMeta(
    val textLength: Int,
    val imageCount: Int,
    val savedAt: Long
) {
    /** 格式化为 "X字，Y张图片" */
    fun toPreviewText(): String {
        val parts = mutableListOf<String>()
        if (textLength > 0) parts.add("${textLength}字")
        if (imageCount > 0) parts.add("${imageCount}张图片")
        return parts.joinToString("，")
    }

    /** 相对时间描述（如 "3分钟前"、"1小时前"、"昨天 14:30"） */
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
            days < 2 -> {
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                "昨天 ${sdf.format(Date(savedAt))}"
            }
            days < 7 -> "${days}天前"
            else -> {
                val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                sdf.format(Date(savedAt))
            }
        }
    }
}

/**
 * 草稿完整数据（用于恢复）。
 */
data class DraftData(
    val text: String,
    val images: List<Uri>,
    val formatSpans: List<SpanDescriptor>,
    val savedAt: Long
)
