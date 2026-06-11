package com.example.test_micrott.views

import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.test_micrott.R
import android.util.Log
import com.example.test_micrott.data.ImageCompressor
import com.example.test_micrott.data.ThumbnailCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

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
    private val onPreviewClick: (Int) -> Unit = {},
) : RecyclerView.Adapter<GalleryPickerAdapter.PhotoViewHolder>() {

    companion object {
        private const val TAG = "GalleryAdapter"

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<GalleryPhoto>() {
            override fun areItemsTheSame(oldItem: GalleryPhoto, newItem: GalleryPhoto): Boolean =
                oldItem.mediaId == newItem.mediaId
            override fun areContentsTheSame(oldItem: GalleryPhoto, newItem: GalleryPhoto): Boolean =
                oldItem.isSelected == newItem.isSelected && oldItem.uri == newItem.uri
        }

        // ── 调试：全局队列统计 ──
        private val totalSubmitted = AtomicInteger(0)
        private val totalCompleted = AtomicInteger(0)
        private val sequence = AtomicInteger(0)
    }

    /** 限制同时解码的图片数量，防止 IO 线程池被 500 张缩略图同时打满 */
    private val decodeSemaphore = Semaphore(4)

    private val differ = AsyncListDiffer(this, DIFF_CALLBACK)

    fun submitList(newItems: List<GalleryPhoto>) {
        differ.submitList(newItems.toList())
    }

    fun getItem(position: Int): GalleryPhoto = differ.currentList[position]

    override fun getItemCount(): Int = differ.currentList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photo = getItem(position)

        // Day 8: 异步加载缩略图，复用 ImageCompressor + ThumbnailCache
        loadThumbnailAsync(holder, photo)

        // 选中状态：右下角勾选图标始终显示（选中=实心，未选中=空心圆）
        val isSelected = photo.isSelected
        holder.overlay.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.checkMark.visibility = View.VISIBLE
        holder.checkMark.setImageResource(
            if (isSelected) R.drawable.ic_check_circle
            else R.drawable.ic_check_circle_empty
        )

        holder.itemView.setOnClickListener {
            onPreviewClick(position)
        }
        holder.checkMark.setOnClickListener {
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

        val seq = sequence.incrementAndGet()
        val uriSegment = photo.uri.lastPathSegment ?: "?"
        totalSubmitted.incrementAndGet()
        val t0 = System.currentTimeMillis()

        holder.loadJob = scope.launch {
            // ── S1: 等待 Semaphore 许可（排队时间） ──
            val bitmap = decodeSemaphore.withPermit {
                val tAfterSem = System.currentTimeMillis()
                val semWait = tAfterSem - t0
                // ── S2+S3+S4: 实际 IO + 解码（见 ImageCompressor 内部日志） ──
                val result = withContext(Dispatchers.IO) {
                    ImageCompressor.decodeSampledBitmap(
                        holder.itemView.context,
                        photo.uri,
                        400, 400
                    )
                }
                val ioTime = System.currentTimeMillis() - tAfterSem
                val total = System.currentTimeMillis() - t0

                val done = totalCompleted.incrementAndGet()
                // 每 50 张输出一次队列快照
                if (done % 50 == 0 || semWait > 200 || ioTime > 300) {
                    val queued = totalSubmitted.get() - done
                    Log.w(TAG, "⏱️ [seq=$seq] semWait=${semWait}ms ioTime=${ioTime}ms total=${total}ms " +
                            "| 队列: 完成=$done 排队中≈$queued | uri=$uriSegment")
                }
                result
            }

            // 总耗时（包含 semWait + ioTime）
            val elapsed = System.currentTimeMillis() - t0
            if (bitmap != null) {
                ThumbnailCache.put(cacheKey, bitmap)
                if (holder.adapterPosition != RecyclerView.NO_POSITION) {
                    holder.imageView.setImageBitmap(bitmap)
                }
            } else {
                Log.e(TAG, "❌ 解码失败: uri=$uriSegment (耗时 ${elapsed}ms)")
            }

            // ── 最后一张输出总结 ──
            val done = totalCompleted.get()
            val submitted = totalSubmitted.get()
            if (done >= submitted && submitted > 0) {
                Log.w(TAG, "════════════════════════════════")
                Log.w(TAG, "📊 相册解码总结: 共 $submitted 张全部完成, 并发限制=${decodeSemaphore.availablePermits + 4}槽")
                Log.w(TAG, "════════════════════════════════")
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
