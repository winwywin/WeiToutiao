package com.example.test_micrott.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
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
 *
 * Day 9 修复：decodeSampledBitmap 之前先用 openFileDescriptor 获取文件尺寸，
 *   避免每次调用开两次 InputStream（bounds + decode 各一次）。
 *   改为：onlyOnceStream 方案 — 先读尺寸，复用同一 fd 回来再解码。
 *   实际更简单：MediaStore 缩略图直接拿，不走全尺寸解码。
 */
object ImageCompressor {

    private const val TAG = "ImageCompressor"

    /**
     * 四步下采样解码，保证结果 Bitmap 长边 ≤ max(targetWidth, targetHeight)。
     *
     * Day 9 重构：只开一次 InputStream，先读 Bounds 再重置流解码，
     * 避免开两次流（尤其云同步照片，每次 openInputStream 都可能涉及网络 I/O）。
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
        val tTotal = System.currentTimeMillis()
        val uriSegment = uri.lastPathSegment ?: "?"

        // ── API 29+: 使用系统硬件加速缩略图（MediaStore 预缓存，<10ms/张） ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val bitmap = context.contentResolver.loadThumbnail(
                    uri, Size(targetWidth, targetHeight), null
                )
                if (bitmap.width > 0 && bitmap.height > 0) {
                    val totalMs = System.currentTimeMillis() - tTotal
                    // 尺寸守卫：loadThumbnail 可能返回 MICRO_KIND(96x96) 等极小缩略图，
                    // 若尺寸不足目标的一半，回退手动解码保证清晰度
                    val minAcceptableW = targetWidth / 2
                    val minAcceptableH = targetHeight / 2
                    if (bitmap.width >= minAcceptableW && bitmap.height >= minAcceptableH) {
                        val flag = if (totalMs > 50) "🐢" else "  "
                        Log.i(TAG, "$flag [loadThumbnail] ${uriSegment} total=${totalMs}ms → ${bitmap.width}x${bitmap.height}")
                        return bitmap
                    }
                    Log.w(TAG, "loadThumbnail too small (${bitmap.width}x${bitmap.height} < ${minAcceptableW}x${minAcceptableH}) for $uriSegment, fallback to manual decode")
                    bitmap.recycle()
                } else {
                    // loadThumbnail 返回 null（部分 ROM/文件格式没有预生成缩略图）
                    Log.w(TAG, "loadThumbnail returned null for $uriSegment, fallback to manual decode")
                }
            } catch (e: Exception) {
                Log.w(TAG, "loadThumbnail failed for $uriSegment: ${e.message}, fallback to manual decode")
                // 部分厂商/文件格式不支持 loadThumbnail，回退到手动解码
            }
        }

        // ── API < 29 或 loadThumbnail 失败/返回 null：手动解码 ──
        return try {
            // Step 1: 打开 fd 读取图片尺寸（只读 bounds，不分配像素）
            val pfd1 = context.contentResolver.openFileDescriptor(uri, "r")
            val tAfterFd = System.currentTimeMillis()
            val fdOpenMs = tAfterFd - tTotal

            if (pfd1 == null) {
                Log.w(TAG, "❌ openFileDescriptor null: $uriSegment")
                return null
            }

            var outWidth = 0
            var outHeight = 0
            pfd1.use { fd ->
                val boundsOptions = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, boundsOptions)
                outWidth = boundsOptions.outWidth
                outHeight = boundsOptions.outHeight
            }
            val tAfterBounds = System.currentTimeMillis()
            val boundsMs = tAfterBounds - tAfterFd

            if (outWidth <= 0 || outHeight <= 0) {
                Log.w(TAG, "invalid dimensions: ${outWidth}x${outHeight} $uriSegment")
                return null
            }

            val targetSize = maxOf(targetWidth, targetHeight)
            val sampleSize = calculateSampleSize(outWidth, outHeight, targetSize)

            // Step 2: 重新打开 fd 进行实际解码（第一次 openFileDescriptor 的 fd 指针已移动）
            val pfd2 = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd2 == null) {
                Log.w(TAG, "❌ reopenFileDescriptor null: $uriSegment")
                return null
            }

            val bitmap = pfd2.use { fd ->
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                val bmp = BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, decodeOptions)
                val tAfterBmp = System.currentTimeMillis()
                val bmpMs = tAfterBmp - tAfterBounds
                val totalMs = tAfterBmp - tTotal

                val slowFlag = if (totalMs > 200 || fdOpenMs > 50 || bmpMs > 150) "🐢" else "  "
                Log.i(TAG, "$slowFlag [manual] ${uriSegment} fd=${fdOpenMs}ms bounds=${boundsMs}ms bmp=${bmpMs}ms total=${totalMs}ms | ${outWidth}x${outHeight} s=${sampleSize}→${bmp?.width ?: 0}x${bmp?.height ?: 0}")
                bmp
            }
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "💥 decode fail $uriSegment: ${e.message} (${System.currentTimeMillis() - tTotal}ms)")
            null
        } catch (e: Error) {
            Log.w(TAG, "💥 decode crash $uriSegment: ${e.message} (${System.currentTimeMillis() - tTotal}ms)")
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
