package com.example.test_micrott.data

import android.graphics.Bitmap
import android.util.LruCache

/**
 * 缩略图内存缓存，供 ImageGridAdapter 和 GalleryPickerAdapter 共用。
 *
 * 容量限制：
 *   - 单张 800×800 RGBA 缩略图 ≈ 2.56MB
 *   - 16MB ≈ 6 张，覆盖 9 宫格常用缓存
 *
 * Key: URI.toString()
 */
object ThumbnailCache {

    private val maxSizeBytes = 16 * 1024 * 1024 // 16MB

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
     * 移除单个条目。
     * updateData 时对已不存在的 URI 精准移除，
     * 避免无条件 evictAll() 导致还在用的缩略图被清掉。
     */
    fun remove(key: String) {
        cache.remove(key)
    }

    /**
     * 清空所有缓存条目。
     * 仅在 Activity/Fragment 销毁或内存警告时调用。
     */
    fun evictAll() {
        cache.evictAll()
    }
}
