package com.example.test_micrott.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 缩略图磁盘缓存（三级缓存中的 L2）。
 *
 * 缓存层级：
 *   L1 — ThumbnailCache (内存 LruCache, 16MB)
 *   L2 — DiskThumbnailCache (磁盘, cacheDir/thumbnails/, 50MB)
 *   L3 — 原始文件 (MediaStore ContentResolver)
 *
 * 命中规则：L1 → L2 → L3，逐级回退。
 *
 * Key: URI.toString().hashCode() 的十六进制字符串
 */
object DiskThumbnailCache {

    private const val TAG = "DiskThumbCache"
    private const val MAX_DISK_BYTES = 50L * 1024 * 1024 // 50MB
    private const val DIR_NAME = "thumbnails"

    private var cacheDir: File? = null

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, DIR_NAME).also {
            if (!it.exists()) it.mkdirs()
        }
        Log.d(TAG, "磁盘缓存目录: ${cacheDir?.absolutePath}")
    }

    private fun keyToFile(key: String): File? {
        val dir = cacheDir ?: return null
        val hash = key.hashCode().toString(16)
        return File(dir, "${hash}.jpg")
    }

    /**
     * 从磁盘缓存读取缩略图。
     * @return Bitmap 或 null（未命中）
     */
    fun get(key: String): Bitmap? {
        val file = keyToFile(key) ?: return null
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                Log.d(TAG, "磁盘命中: ${key.take(40)}... → ${bitmap.width}x${bitmap.height}")
                bitmap
            } else {
                file.delete() // 损坏文件清理
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "磁盘读取失败: ${e.message}")
            null
        }
    }

    /**
     * 将缩略图写入磁盘缓存（JPEG quality 由 DraftManager.THUMBNAIL_JPEG_QUALITY 统一管理）。
     */
    fun put(key: String, bitmap: Bitmap) {
        val dir = cacheDir
        if (dir == null) {
            Log.w(TAG, "磁盘缓存未初始化，跳过写入")
            return
        }
        trimIfNeeded()
        val file = keyToFile(key) ?: return
        if (file.exists()) return // 已存在

        try {
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, DraftManager.THUMBNAIL_JPEG_QUALITY, fos)
            }
            Log.d(TAG, "磁盘写入: ${key.take(40)}... → ${file.length() / 1024}KB")
        } catch (e: Exception) {
            Log.w(TAG, "磁盘写入失败: ${e.message}")
        }
    }

    /**
     * 移除单个缓存条目。
     */
    fun remove(key: String) {
        keyToFile(key)?.delete()
    }

    /**
     * 清空所有磁盘缓存。
     */
    fun evictAll() {
        cacheDir?.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "磁盘缓存已清空")
    }

    /**
     * 总大小超过上限时，按最后修改时间删除最旧的文件。
     */
    private fun trimIfNeeded() {
        val dir = cacheDir ?: return
        val files = dir.listFiles() ?: return
        val totalBytes = files.sumOf { it.length() }
        if (totalBytes <= MAX_DISK_BYTES) return

        // 按修改时间升序（最先选的的排前面），删除直到低于上限
        val sorted = files.sortedBy { it.lastModified() }
        var currentBytes = totalBytes
        for (file in sorted) {
            if (currentBytes <= MAX_DISK_BYTES * 0.7) break // 降到 70% 即停止
            currentBytes -= file.length()
            file.delete()
            Log.d(TAG, "磁盘淘汰: ${file.name}")
        }
    }
}
