package com.example.test_micrott.util

import android.graphics.Bitmap
import android.util.LruCache

/**
 * 缩略图内存缓存，供 ImageGridAdapter 和 GalleryPickerAdapter 共用。
 *
 * 容量限制：
 *   - 单张 300×300 RGBA 缩略图 ≈ 360KB
 *   - 4MB ≈ 11 张，覆盖 9 宫格 + 滚动缓冲
 *
 * Key: URI.toString()
 */
object ThumbnailCache {

    private val maxSizeBytes = 4 * 1024 * 1024 // 4MB

    private val cache = object : LruCache<String, Bitmap>(maxSizeBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            // byteCount 返回 Bitmap 实际占用的 native 内存字节数
            return value.byteCount
        }
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    /**
     * 清空所有缓存条目。
     * 当图片列表发生外部变更时（updateData 接收到新 URI 列表）调用，
     * 避免旧缩略图占用缓存空间。
     */
    fun evictAll() {
        cache.evictAll()
    }
}
