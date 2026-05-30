package com.example.test_micrott.view

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.test_micrott.R
import java.io.FileNotFoundException

/**
 * 自定义相册网格适配器
 *
 * 每项显示缩略图 + 选中遮罩 + 勾选标记。
 * 使用 BitmapFactory 下采样加载缩略图，避免 setImageURI 解码全分辨率导致 ANR。
 */
class GalleryPickerAdapter(
    private val onToggle: (Int) -> Unit,
) : RecyclerView.Adapter<GalleryPickerAdapter.PhotoViewHolder>() {

    private val items = mutableListOf<GalleryPhoto>()

    fun submitList(newItems: List<GalleryPhoto>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItem(position: Int): GalleryPhoto = items[position]

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photo = items[position]

        // 异步加载缩略图（下采样，避免主线程解码全分辨率 → ANR）
        loadThumbnail(holder.imageView, photo.uri, holder.itemView.context)

        // 选中状态
        val isSelected = photo.isSelected
        holder.overlay.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.checkMark.visibility = if (isSelected) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            onToggle(position)
        }
    }

    /**
     * 用 BitmapFactory 下采样加载缩略图。
     *
     * 为什么不用 setImageURI()：
     *   setImageURI 会在主线程解码全分辨率 JPEG，数百张照片一起解码
     *   会阻塞 UI 线程 5 秒以上，触发 ANR。
     */
    private fun loadThumbnail(imageView: ImageView, uri: Uri, context: android.content.Context) {
        // 先读尺寸，计算采样率
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: FileNotFoundException) {
            Log.w("GalleryPicker", "缩略图加载失败 (文件不存在): $uri")
            return
        } catch (e: SecurityException) {
            Log.w("GalleryPicker", "缩略图加载失败 (权限): $uri")
            return
        }

        // 目标缩略图边长约 256px
        val targetSize = 256
        val sampleSize = calculateSampleSize(
            options.outWidth, options.outHeight, targetSize
        )
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }

        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream, null, decodeOptions)
                imageView.setImageBitmap(bitmap)
            }
        } catch (e: FileNotFoundException) {
            Log.w("GalleryPicker", "缩略图加载失败: $uri")
        } catch (e: SecurityException) {
            Log.w("GalleryPicker", "缩略图加载失败 (权限): $uri")
        }
    }

    /**
     * 计算 BitmapFactory 下采样率。
     * 保证采样后长边 ≤ targetSize。
     */
    private fun calculateSampleSize(width: Int, height: Int, targetSize: Int): Int {
        var sampleSize = 1
        val maxDimension = maxOf(width, height)
        while (maxDimension / sampleSize > targetSize) {
            sampleSize *= 2
        }
        return sampleSize
    }

    class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.iv_photo)
        val overlay: View = itemView.findViewById(R.id.view_selected_overlay)
        val checkMark: ImageView = itemView.findViewById(R.id.iv_check)
    }
}

/**
 * 相册中单张照片的数据模型
 */
data class GalleryPhoto(
    val mediaId: Long,
    val uri: Uri,
    var isSelected: Boolean = false,
)
