package com.example.test_micrott.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.test_micrott.models.SpanDescriptor
import com.example.test_micrott.repository.DraftData
import com.example.test_micrott.repository.DraftRepository
import com.example.test_micrott.repository.DraftSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * 微头条草稿管理器（Day 22+ 重构为单表多草稿）
 *
 * 存储方式：原生 SQLiteOpenHelper（手写 SQL，禁用 Room）
 *   DB 文件: filesDir/../databases/wtt_draft.db
 *   图片缓存: filesDir/draft_images/draft_<id>_<index>.jpg
 *
 * 单表：drafts(id, text, images_json, spans_json, saved_at, is_temporary)
 *
 * 双层草稿机制：
 *   - 主动保存：退出弹窗确认 → is_temporary=0（永久草稿）
 *   - 防抖草稿：onPause 自动存 → is_temporary=1（临时草稿）
 *     onResume → 删除所有临时草稿（用户回来了，数据没丢）
 *     onStop   → 标记所有临时为永久（用户真走了）
 */
class DraftManager(private val context: Context) : DraftRepository {

    companion object {
        private const val TAG = "DraftManager"
        private const val DRAFT_IMG_PREFIX = "draft_img"

        /** 永久草稿数量上限，超过后自动删除最旧的记录 */
        const val MAX_DRAFT_COUNT = 50

        /** JPEG 压缩质量（缩略图 / 磁盘缓存共用） */
        const val THUMBNAIL_JPEG_QUALITY = 85
    }

    private val dbHelper = DraftDatabaseHelper(context)

    private val draftImgDir: File
        get() = File(context.filesDir, "draft_images").also { it.mkdirs() }

    // ================================================================
    // 公开 API
    // ================================================================

    /** 是否存在任意草稿 */
    override fun hasDraft(): Boolean {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM ${DraftDatabaseHelper.TABLE_DRAFTS}", null
        )
        return cursor.use { c -> c.moveToFirst() && c.getInt(0) > 0 }
    }

    /**
     * 获取所有草稿的摘要信息（草稿箱列表用）。
     * 按 saved_at 逆序，包含文本预览（前 80 字符）和图片数量。
     */
    override fun getAllDrafts(): List<DraftSummary> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT ${DraftDatabaseHelper.COL_ID}, " +
            "       ${DraftDatabaseHelper.COL_TEXT}, " +
            "       ${DraftDatabaseHelper.COL_IMAGES_JSON}, " +
            "       ${DraftDatabaseHelper.COL_SAVED_AT} " +
            "FROM ${DraftDatabaseHelper.TABLE_DRAFTS} " +
            "WHERE ${DraftDatabaseHelper.COL_IS_TEMPORARY} = 0 " +
            "ORDER BY ${DraftDatabaseHelper.COL_SAVED_AT} DESC",
            null
        )
        return cursor.use { c ->
            val list = mutableListOf<DraftSummary>()
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val text = c.getString(1)
                val imagesJson = c.getString(2)
                val imageCount = try {
                    JSONArray(imagesJson).length()
                } catch (_: Exception) { 0 }
                val savedAt = c.getLong(3)

                // 文本预览：取前 80 字符，去换行
                val preview = text.take(80).replace('\n', ' ').trim()

                list.add(DraftSummary(
                    id = id,
                    textPreview = preview,
                    textLength = text.length,
                    imageCount = imageCount,
                    savedAt = savedAt,
                ))
            }
            list
        }
    }

    /**
     * 读取单条草稿的完整数据（用于恢复）。
     */
    override suspend fun getDraft(id: Long): DraftData? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT ${DraftDatabaseHelper.COL_TEXT}, " +
            "       ${DraftDatabaseHelper.COL_IMAGES_JSON}, " +
            "       ${DraftDatabaseHelper.COL_SPANS_JSON}, " +
            "       ${DraftDatabaseHelper.COL_SAVED_AT} " +
            "FROM ${DraftDatabaseHelper.TABLE_DRAFTS} " +
            "WHERE ${DraftDatabaseHelper.COL_ID} = ?",
            arrayOf(id.toString())
        )
        cursor.use { c ->
            if (c.moveToFirst()) {
                val text = c.getString(0)
                val imagesJson = c.getString(1)
                val spansJson = c.getString(2)
                val savedAt = c.getLong(3)

                val imageUris = parseImagePaths(imagesJson)
                val spans = parseSpans(spansJson)

                DraftData(
                    text = text,
                    images = imageUris,
                    formatSpans = spans,
                    savedAt = savedAt,
                )
            } else null
        }
    }

    /**
     * 保存永久草稿（IO 操作，需在协程 IO 线程中调用）。
     * 来源：退出弹窗用户主动确认保存。
     *
     * @param updateDraftId 非 null 时 UPDATE 该记录（从草稿箱恢复后修改），null 时 INSERT 新记录
     * @return 草稿 id（-1 表示保存失败）
     */
    override suspend fun saveDraft(
        text: String,
        imageUris: List<Uri>,
        formatSpans: List<SpanDescriptor>,
        updateDraftId: Long?
    ): Long = withContext(Dispatchers.IO) {
        internalSave(text, imageUris, formatSpans, isTemporary = false, updateDraftId = updateDraftId)
    }

    /**
     * 保存临时草稿（防抖机制，onPause 自动调用）。
     * is_temporary=1，后续 onResume 删除 / onStop 变永久。
     *
     * UPSERT 策略：如果已存在临时草稿 → UPDATE 它，否则 INSERT 新记录。
     * 保证同时最多只有 1 条临时草稿，避免反复 INSERT 堆记录。
     */
    override suspend fun saveTemporaryDraft(
        text: String,
        imageUris: List<Uri>,
        formatSpans: List<SpanDescriptor>
    ): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val existingTempId = findExistingTemporaryDraftId(db)
        internalSave(text, imageUris, formatSpans, isTemporary = true, updateDraftId = existingTempId)
    }

    /**
     * 内部实现：写入或更新草稿记录。
     *
     * @param updateDraftId 非 null 时 UPDATE 该 id 的记录，null 时 INSERT 新记录
     */
    private fun internalSave(
        text: String,
        imageUris: List<Uri>,
        formatSpans: List<SpanDescriptor>,
        isTemporary: Boolean,
        updateDraftId: Long?
    ): Long {
        try {
            val savedAt = System.currentTimeMillis()
            val db = dbHelper.writableDatabase

            db.beginTransaction()
            try {
                // ── 1. 收集旧图片路径（UPDATE 时用于清理不再使用的文件） ──
                val oldPaths: Set<String> = if (updateDraftId != null) {
                    readImagePathsForDraft(db, updateDraftId)
                } else emptySet()

                // ── 2. 图片去重：已存在于私有目录的复用，新 URI 才复制 ──
                val newPaths = mutableListOf<String>()
                imageUris.forEach { uri ->
                    try {
                        val path = resolveImageToPrivatePath(uri, savedAt)
                        newPaths.add(path)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resolve draft image: ${e.message}")
                    }
                }

                // ── 3. 清理不再使用的旧图片文件 ──
                val newPathSet = newPaths.toSet()
                oldPaths.filter { it !in newPathSet }.forEach { path ->
                    File(path).delete()
                    Log.v(TAG, "清理不再使用的图片: $path")
                }

                // ── 4. 序列化 ──
                val imagesJson = JSONArray()
                newPaths.forEach { imagesJson.put(it) }

                val spansJson = JSONArray()
                formatSpans.forEach { spansJson.put(it.serialize()) }

                val values = ContentValues().apply {
                    put(DraftDatabaseHelper.COL_TEXT, text)
                    put(DraftDatabaseHelper.COL_IMAGES_JSON, imagesJson.toString())
                    put(DraftDatabaseHelper.COL_SPANS_JSON, spansJson.toString())
                    put(DraftDatabaseHelper.COL_SAVED_AT, savedAt)
                    put(DraftDatabaseHelper.COL_IS_TEMPORARY, if (isTemporary) 1 else 0)
                }

                // ── 5. INSERT 或 UPDATE ──
                val id: Long = if (updateDraftId != null) {
                    db.update(
                        DraftDatabaseHelper.TABLE_DRAFTS, values,
                        "${DraftDatabaseHelper.COL_ID} = ?",
                        arrayOf(updateDraftId.toString())
                    )
                    updateDraftId
                } else {
                    db.insert(DraftDatabaseHelper.TABLE_DRAFTS, null, values)
                }

                // ── 6. INSERT 后检查草稿数量上限 ──
                if (!isTemporary && updateDraftId == null) {
                    enforceDraftLimit(db)
                }

                db.setTransactionSuccessful()

                val label = if (isTemporary) "临时" else "永久"
                val action = if (updateDraftId != null) "更新" else "新建"
                Log.d(TAG, "$label 草稿已$action: id=$id, text=${text.length}chars, images=${imageUris.size}")
                return id
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.w(TAG, "internalSave failed", e)
            return -1L
        }
    }

    /** 查找现有临时草稿的 id，不存在返回 null */
    private fun findExistingTemporaryDraftId(db: android.database.sqlite.SQLiteDatabase): Long? {
        val cursor = db.rawQuery(
            "SELECT ${DraftDatabaseHelper.COL_ID} FROM ${DraftDatabaseHelper.TABLE_DRAFTS} " +
            "WHERE ${DraftDatabaseHelper.COL_IS_TEMPORARY} = 1 LIMIT 1", null
        )
        return cursor.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    /**
     * 永久草稿超过 [MAX_DRAFT_COUNT] 条时，删除最旧的记录及关联图片。
     * 仅在 INSERT 新永久草稿后调用（UPDATE 不改变数量）。
     */
    private fun enforceDraftLimit(db: android.database.sqlite.SQLiteDatabase) {
        // 查出超限的最旧草稿 id
        val cursor = db.rawQuery(
            "SELECT ${DraftDatabaseHelper.COL_ID}, ${DraftDatabaseHelper.COL_IMAGES_JSON} " +
            "FROM ${DraftDatabaseHelper.TABLE_DRAFTS} " +
            "WHERE ${DraftDatabaseHelper.COL_IS_TEMPORARY} = 0 " +
            "ORDER BY ${DraftDatabaseHelper.COL_SAVED_AT} DESC " +
            "LIMIT -1 OFFSET $MAX_DRAFT_COUNT",
            null
        )
        val toDelete = mutableListOf<Pair<Long, String?>>()
        cursor.use { c ->
            while (c.moveToNext()) {
                toDelete.add(c.getLong(0) to c.getString(1))
            }
        }
        if (toDelete.isEmpty()) return

        toDelete.forEach { (draftId, imagesJson) ->
            db.delete(DraftDatabaseHelper.TABLE_DRAFTS,
                "${DraftDatabaseHelper.COL_ID} = ?",
                arrayOf(draftId.toString()))
            imagesJson?.let { deleteImageFiles(it) }
        }
        Log.d(TAG, "草稿数量超限，已清理 ${toDelete.size} 条最旧草稿")
    }

    /** 读取指定草稿的图片路径集合（用于 UPDATE 时做差量清理） */
    private fun readImagePathsForDraft(db: android.database.sqlite.SQLiteDatabase, draftId: Long): Set<String> {
        val cursor = db.rawQuery(
            "SELECT ${DraftDatabaseHelper.COL_IMAGES_JSON} FROM ${DraftDatabaseHelper.TABLE_DRAFTS} " +
            "WHERE ${DraftDatabaseHelper.COL_ID} = ?", arrayOf(draftId.toString())
        )
        return cursor.use { c ->
            if (c.moveToFirst()) {
                val json = c.getString(0)
                try {
                    val arr = JSONArray(json)
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                } catch (_: Exception) { emptySet() }
            } else emptySet()
        }
    }

    /**
     * 图片去重：如果 URI 已经指向私有目录中的文件则直接复用，否则复制。
     *
     * 典型场景：
     * - 恢复草稿后图片是 file:// → 直接复用（零 IO）
     * - 新选的图片是 content:// → 复制到私有目录
     */
    private fun resolveImageToPrivatePath(uri: Uri, savedAt: Long): String {
        if (uri.scheme == "file") {
            val path = uri.path ?: return copyImageToPrivate(uri, savedAt)
            val file = File(path)
            // 已在私有目录且文件存在 → 复用
            if (file.exists() && file.parentFile == draftImgDir) {
                return path
            }
        }
        return copyImageToPrivate(uri, savedAt)
    }

    /**
     * 将所有临时草稿（is_temporary=1）标记为永久。
     * onStop 调用：应用真正进入后台后，临时草稿晋升为永久草稿。
     */
    override fun markAllTemporaryPermanent() {
        try {
            val db = dbHelper.writableDatabase
            db.execSQL(
                "UPDATE ${DraftDatabaseHelper.TABLE_DRAFTS} " +
                "SET ${DraftDatabaseHelper.COL_IS_TEMPORARY} = 0 " +
                "WHERE ${DraftDatabaseHelper.COL_IS_TEMPORARY} = 1"
            )
            Log.d(TAG, "临时草稿已全部标记为永久")
        } catch (e: Exception) {
            Log.w(TAG, "markAllTemporaryPermanent failed", e)
        }
    }

    /**
     * 删除所有临时草稿（数据库记录 + 关联图片文件）。
     * onResume 调用：用户回到应用，临时草稿不再需要。
     */
    override fun deleteAllTemporaryDrafts() {
        try {
            val db = dbHelper.writableDatabase
            // 先查所有临时草稿的图片 JSON
            val cursor = db.rawQuery(
                "SELECT ${DraftDatabaseHelper.COL_IMAGES_JSON} " +
                "FROM ${DraftDatabaseHelper.TABLE_DRAFTS} " +
                "WHERE ${DraftDatabaseHelper.COL_IS_TEMPORARY} = 1",
                null
            )
            val imagesJsonList = mutableListOf<String>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    imagesJsonList.add(c.getString(0))
                }
            }

            // 删除记录
            db.delete(
                DraftDatabaseHelper.TABLE_DRAFTS,
                "${DraftDatabaseHelper.COL_IS_TEMPORARY} = 1",
                null
            )

            // 清理关联图片
            imagesJsonList.forEach { deleteImageFiles(it) }

            Log.d(TAG, "临时草稿已全部删除: ${imagesJsonList.size} 条")
        } catch (e: Exception) {
            Log.w(TAG, "deleteAllTemporaryDrafts failed", e)
        }
    }

    /**
     * 删除指定草稿（数据库记录 + 关联图片文件）。
     */
    override fun deleteDraft(id: Long) {
        try {
            // 先读图片路径，再删除记录
            val db = dbHelper.writableDatabase
            val cursor = db.rawQuery(
                "SELECT ${DraftDatabaseHelper.COL_IMAGES_JSON} " +
                "FROM ${DraftDatabaseHelper.TABLE_DRAFTS} " +
                "WHERE ${DraftDatabaseHelper.COL_ID} = ?",
                arrayOf(id.toString())
            )
            val imagesJson = cursor.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }

            db.delete(DraftDatabaseHelper.TABLE_DRAFTS,
                "${DraftDatabaseHelper.COL_ID} = ?",
                arrayOf(id.toString()))

            // 清理关联图片文件
            imagesJson?.let { deleteImageFiles(it) }

            Log.d(TAG, "Draft deleted: id=$id")
        } catch (e: Exception) {
            Log.w(TAG, "deleteDraft failed", e)
        }
    }

    /** 估算总大小 */
    fun estimateSize(): Long {
        val dbFile = context.getDatabasePath(DraftDatabaseHelper.DATABASE_NAME)
        var size = if (dbFile.exists()) dbFile.length() else 0L
        if (draftImgDir.exists()) {
            draftImgDir.listFiles()?.forEach { size += it.length() }
        }
        return size
    }

    // ================================================================
    // 内部工具
    // ================================================================

    /** 将 content:// Uri 复制到 app-private 目录 */
    private fun copyImageToPrivate(uri: Uri, savedAt: Long): String {
        val ext = guessImageExtension(uri)
        val destFile = File(draftImgDir, "${DRAFT_IMG_PREFIX}_${savedAt}_${System.nanoTime()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return destFile.absolutePath
    }

    /** JSONArray 路径 → Uri 列表（过滤已删除文件，丢失时打 Log.w） */
    private fun parseImagePaths(imagesJson: String): List<Uri> {
        return try {
            val arr = JSONArray(imagesJson)
            (0 until arr.length()).mapNotNull { i ->
                val path = arr.getString(i)
                val file = File(path)
                if (file.exists()) {
                    Uri.fromFile(file)
                } else {
                    Log.w(TAG, "Draft image file missing: $path")
                    null
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    /** JSONArray → SpanDescriptor 列表 */
    private fun parseSpans(spansJson: String): List<SpanDescriptor> {
        return try {
            val arr = JSONArray(spansJson)
            (0 until arr.length()).mapNotNull { i ->
                SpanDescriptor.deserialize(arr.getString(i))
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 根据图片 JSON 路径列表删除关联文件 */
    private fun deleteImageFiles(imagesJson: String) {
        try {
            val arr = JSONArray(imagesJson)
            for (i in 0 until arr.length()) {
                File(arr.getString(i)).delete()
            }
        } catch (_: Exception) { /* ignore */ }
    }

    private fun guessImageExtension(uri: Uri): String {
        val mime = context.contentResolver.getType(uri)
        return when {
            mime == "image/png" -> "png"
            mime == "image/webp" -> "webp"
            mime == "image/gif" -> "gif"
            else -> "jpg"
        }
    }
}
