package com.example.test_micrott.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 自研图片压缩工具，专为微头条发布器设计。
 *
 * 替代 setImageURI() 的全分辨率解码，采用 BitmapFactory 下采样策略：
 *   1. inJustDecodeBounds=true  → 只读尺寸，不分配像素内存
 *   2. calculateSampleSize()    → 计算 2 的幂次采样率
 *   3. inJustDecodeBounds=false → 正式解码缩略图
 *
 * 验收：9 张 4000×3000 原图 → 堆内存增量 ≤ 30MB，无 OOM。
 */
object ImageCompressor {

    private const val TAG = "ImageCompressor"

    /**
     * 四步下采样解码，保证结果 Bitmap 长边 ≤ max(targetWidth, targetHeight)。
     *
     * @param context      用于 contentResolver.openInputStream
     * @param uri          图片 URI（支持 content:// 和 file://）
     * @param targetWidth  目标宽度（像素），默认 400
     * @param targetHeight 目标高度（像素），默认 400
     * @return 下采样后的 Bitmap；解析失败返回 null
     */
    fun decodeSampledBitmap(
        context: Context,
        uri: Uri,
        targetWidth: Int = 400,
        targetHeight: Int = 400
    ): Bitmap? {
        // Step 1: 只读尺寸，不分配内存
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
        } catch (e: Exception) {
            Log.w(TAG, "decodeBounds failed for $uri: ${e.message}")
            return null
        }

        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            Log.w(TAG, "Invalid dimensions for $uri: ${boundsOptions.outWidth}x${boundsOptions.outHeight}")
            return null
        }

        // Step 2: 计算采样率
        val targetSize = maxOf(targetWidth, targetHeight)
        val sampleSize = calculateSampleSize(
            boundsOptions.outWidth, boundsOptions.outHeight, targetSize
        )

        // Step 3 & 4: 正式解码
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }

        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (e: Exception) {
            Log.w(TAG, "decode failed for $uri: ${e.message}")
            null
        }
    }

    /**
     * 计算 2 的幂次下采样率。
     * 保证采样后长边 ≤ targetSize。
     *
     * 例：4000px 原图 targetSize=400 → sampleSize=8 → 结果 500px
     */
    fun calculateSampleSize(width: Int, height: Int, targetSize: Int): Int {
        if (targetSize <= 0) return 1
        var sampleSize = 1
        val maxDimension = maxOf(width, height)
        while (maxDimension / sampleSize > targetSize) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * 将 Bitmap 以 JPEG 格式压缩写入文件（供后续上传使用）。
     *
     * @param bitmap  源 Bitmap
     * @param output  目标文件
     * @param quality JPEG 压缩质量 1-100，默认 80
     * @return 写入的文件
     */
    fun compressToFile(bitmap: Bitmap, output: File, quality: Int = 80): File {
        FileOutputStream(output).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
        }
        return output
    }
}
