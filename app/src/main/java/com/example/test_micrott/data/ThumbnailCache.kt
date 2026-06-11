package com.example.test_micrott.data

import android.graphics.Bitmap
import android.util.LruCache

/**
 * 缩略图三级缓存管理器。
 *
 * L1 — 内存 LruCache (16MB)，最快，存活于进程内
 * L2 — 磁盘缓存 DiskThumbnailCache (50MB)，跨进程存活
 * L3 — 原始文件 (MediaStore)，最慢但无损
 *
 * 查询链路：get() → L1 hit → 返回
 *                 → L1 miss → L2 hit → 回填 L1 → 返回
 *                 → L1+2 miss → 返回 null（调用方去 L3 解码）
 *
 * 写入链路：put() → 写入 L1 + 异步写入 L2
 *
 * Key: URI.toString()
 */
object ThumbnailCache {

    private val maxSizeBytes = 16 * 1024 * 1024 // 16MB

    private val cache = object : LruCache<String, Bitmap>(maxSizeBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    /**
     * 三级缓存查找：L1 内存 → L2 磁盘。
     */
    fun get(key: String): Bitmap? {
        // L1
        cache.get(key)?.let { return it }
        // L2
        val diskBitmap = DiskThumbnailCache.get(key)
        if (diskBitmap != null) {
            cache.put(key, diskBitmap) // 回填 L1
            return diskBitmap
        }
        return null
    }

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)          // L1
        DiskThumbnailCache.put(key, bitmap) // L2（同步写入，文件小）
    }

    /**
     * 移除单个条目（L1 + L2）。
     */
    fun remove(key: String) {
        cache.remove(key)
        DiskThumbnailCache.remove(key)
    }

    /**
     * 清空所有缓存条目（L1 + L2）。
     * 仅在 Activity/Fragment 销毁或内存警告时调用。
     */
    fun evictAll() {
        cache.evictAll()
        DiskThumbnailCache.evictAll()
    }
}
