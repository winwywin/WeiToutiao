package com.example.test_micrott.view

import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.test_micrott.R
import com.example.test_micrott.util.ImageCompressor
import com.example.test_micrott.util.ThumbnailCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自定义相册网格适配器。
 *
 * Day 8 重构：删除内联 loadThumbnail/calculateSampleSize（~50 行），
 * 改为复用 ImageCompressor.decodeSampledBitmap + ThumbnailCache，
 * 缩略图加载改为异步（后台线程 + post 回 UI）。
 */
class GalleryPickerAdapter(
    private val scope: CoroutineScope,
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

        // Day 8: 异步加载缩略图，复用 ImageCompressor + ThumbnailCache
        loadThumbnailAsync(holder, photo)

        // 选中状态
        val isSelected = photo.isSelected
        holder.overlay.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.checkMark.visibility = if (isSelected) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            onToggle(position)
        }
    }

    /**
     * Day 8 重构：协程异步加载缩略图，替代 Thread{}.start()
     *
     * 修复：滚动时每绑定一个 ViewHolder 就创建一个新线程，
     * 500 张照片快速滚动会堆积几十个并发线程 → OOM/ANR
     *
     * 新流程：
     *   1. 查 ThumbnailCache → 命中直接 setImageBitmap
     *   2. 未命中 → 设灰色占位 → scope.launch(IO) 解码
     *   3. ViewHolder 回收时 cancel() → 不会错位
     */
    private fun loadThumbnailAsync(holder: PhotoViewHolder, photo: GalleryPhoto) {
        holder.loadJob?.cancel()

        val cacheKey = photo.uri.toString()
        val cached = ThumbnailCache.get(cacheKey)
        if (cached != null) {
            holder.imageView.setImageBitmap(cached)
            return
        }

        holder.imageView.setImageDrawable(ColorDrawable(0xFFE0E0E0.toInt()))

        holder.loadJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                ImageCompressor.decodeSampledBitmap(
                    holder.itemView.context,
                    photo.uri,
                    256, 256
                )
            }
            if (bitmap != null) {
                ThumbnailCache.put(cacheKey, bitmap)
                if (holder.adapterPosition != RecyclerView.NO_POSITION) {
                    holder.imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    override fun onViewRecycled(holder: PhotoViewHolder) {
        super.onViewRecycled(holder)
        holder.loadJob?.cancel()
        holder.imageView.setImageDrawable(null)
    }

    class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.iv_photo)
        val overlay: View = itemView.findViewById(R.id.view_selected_overlay)
        val checkMark: ImageView = itemView.findViewById(R.id.iv_check)
        var loadJob: Job? = null
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
