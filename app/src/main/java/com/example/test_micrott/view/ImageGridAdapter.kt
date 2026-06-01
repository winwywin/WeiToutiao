package com.example.test_micrott.view

import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.test_micrott.R
import com.example.test_micrott.util.ImageCompressor
import com.example.test_micrott.util.ThumbnailCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * MVI 主项目九宫格适配器
 * 职责：纯渲染，不持有业务状态，数据由 PublishState.selectedImages 驱动
 *
 * Day 8 升级：
 *   - 异步下采样缩略图：主线程 setImageURI → IO 协程 decodeSampledBitmap
 *   - ThumbnailCache：LruCache 避免重复解码
 *   - DiffUtil：增量刷新替代 notifyDataSetChanged
 *   - ViewHolder 回收：取消旧加载任务 + 清理 Drawable
 */
class ImageGridAdapter(
    private val scope: CoroutineScope,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_IMAGE = 0
        const val TYPE_ADD_BUTTON = 1
    }

    private val maxImageCount = 9

    /** 限制同时解码的图片数量，防止 9 张图同时并发导致 IO 线程池饱和/ANR */
    private val decodeSemaphore = Semaphore(3)

    private var mSelectedImages = ArrayList<Uri>()
    private var mAddListener: (() -> Unit)? = null
    private var mDeleteListener: ((Int) -> Unit)? = null
    private var mOnMoveListener: ((Int, Int) -> Unit)? = null
    private var mOnImageClickListener: ((Int) -> Unit)? = null

    fun setListeners(
        onAddClickListener: () -> Unit,
        onDeleteClickListener: (Int) -> Unit,
        onMoveListener: (Int, Int) -> Unit,
        onImageClickListener: (Int) -> Unit = {},
    ) {
        this.mAddListener = onAddClickListener
        this.mDeleteListener = onDeleteClickListener
        this.mOnMoveListener = onMoveListener
        this.mOnImageClickListener = onImageClickListener
    }

    fun updateData(images: List<Uri>) {
        val oldList = mSelectedImages
        val newList = ArrayList(images)

        // 移除不再存在的缓存条目，避免无条件 evictAll()
        val newKeys = newList.map { it.toString() }.toHashSet()
        oldList.forEach { uri ->
            val key = uri.toString()
            if (key !in newKeys) {
                ThumbnailCache.remove(key)
            }
        }

        mSelectedImages = newList
        // 改用 notifyDataSetChanged，避免 DiffUtil 与「加号按钮」的竞态导致 ViewHolder 位置错乱
        notifyDataSetChanged()
    }

    fun getImages(): List<Uri> = mSelectedImages

    /**
     * 拖拽过程中实时预览换位：单次 swap + notifyItemMoved，不通知 ViewModel。
     *
     * ItemTouchHelper 逐相邻位置回调 onMove（例如 2→3, 3→4, 4→5），
     * 因此每次只交换相邻两个数据项，notifyItemMoved(from, to) 精确匹配单步移动。
     * 松手后由 clearView 收集最终顺序，一次性提交通知 ViewModel。
     */
    fun previewOnItemMove(fromPosition: Int, toPosition: Int) {
        if ((fromPosition !in mSelectedImages.indices) || (toPosition !in mSelectedImages.indices)) return
        if (fromPosition == toPosition) return

        Collections.swap(mSelectedImages, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
    }

    /**
     * 拖拽排序：交换数据源中的两个位置，并通知回调 → ViewModel
     */
    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if ((fromPosition !in mSelectedImages.indices) || (toPosition !in mSelectedImages.indices)) return
        if (fromPosition == toPosition) return

        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(mSelectedImages, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(mSelectedImages, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        mOnMoveListener?.invoke(fromPosition, toPosition)
    }

    /**
     * 单次移动（松手吸附用）：removeAt + add，一条 notify + 一次回调
     */
    fun moveSingleItem(from: Int, to: Int) {
        if ((from !in mSelectedImages.indices) || (to !in mSelectedImages.indices)) return
        if (from == to) return

        val moved = mSelectedImages.removeAt(from)
        mSelectedImages.add(to, moved)
        notifyItemMoved(from, to)
        mOnMoveListener?.invoke(from, to)
    }

    override fun getItemCount(): Int {
        return if (mSelectedImages.size < maxImageCount) mSelectedImages.size + 1 else maxImageCount
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == mSelectedImages.size && mSelectedImages.size < maxImageCount) {
            TYPE_ADD_BUTTON
        } else {
            TYPE_IMAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_ADD_BUTTON) {
            AddViewHolder(inflater.inflate(R.layout.item_publish_add, parent, false))
        } else {
            ImageViewHolder(inflater.inflate(R.layout.item_publish_image, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AddViewHolder) {
            holder.itemView.setOnClickListener { mAddListener?.invoke() }
        } else if (holder is ImageViewHolder) {
            bindImageHolder(holder, position)
        }
    }

    /**
     * Day 8 异步缩略图加载流程：
     *   1. 取消该 ViewHolder 的旧加载任务（防止快速滚动错图）
     *   2. 检查 ThumbnailCache → 命中直接 setImageBitmap
     *   3. 未命中 → 设灰色占位 → launch coroutine {
     *        IO: ImageCompressor.decodeSampledBitmap()
     *        → ThumbnailCache.put()
     *        → 校验 absoluteAdapterPosition == position
     *        → Main: setImageBitmap
     *      }
     */
    private fun bindImageHolder(holder: ImageViewHolder, position: Int) {
        // 取消旧任务：ViewHolder 复用后旧 job 还在跑，直接取消避免错图
        holder.loadJob?.cancel()

        val uri = mSelectedImages[position]
        val cacheKey = uri.toString()

        // 先查缓存
        val cached = ThumbnailCache.get(cacheKey)
        if (cached != null) {
            holder.imageView.setImageBitmap(cached)
        } else {
            // 设灰色占位
            holder.imageView.setImageDrawable(ColorDrawable(0xFFE8E8E8.toInt()))

            holder.loadJob = scope.launch {
                val bitmap = decodeSemaphore.withPermit {
                    withContext(Dispatchers.IO) {
                        ImageCompressor.decodeSampledBitmap(
                            holder.itemView.context, uri,
                            targetWidth = 800, targetHeight = 800
                        )
                    }
                }
                if (bitmap != null) {
                    ThumbnailCache.put(cacheKey, bitmap)
                    // 校验：异步完成时检查 ViewHolder 是否仍对应同一位置
                    if (holder.adapterPosition == position) {
                        holder.imageView.setImageBitmap(bitmap)
                    }
                }
            }
        }

        holder.btnDelete.setOnClickListener { mDeleteListener?.invoke(position) }
        holder.imageView.setOnClickListener { mOnImageClickListener?.invoke(position) }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ImageViewHolder) {
            // 回收时取消旧任务 + 清理 Drawable，防止 LeakCanary 报警
            holder.loadJob?.cancel()
            holder.imageView.setImageDrawable(null)
        }
    }

    // ========================================================================
    // DiffUtil
    // ========================================================================

    private class DiffCallback(
        private val oldList: List<Uri>,
        private val newList: List<Uri>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldList[oldItemPosition] == newList[newItemPosition]
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            true // Uri 内容不变，差异由增删位置体现
    }

    // ========================================================================
    // ViewHolder
    // ========================================================================

    class AddViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.iv_thumnail)
        val btnDelete: View = itemView.findViewById(R.id.view_delete_fork)
        var loadJob: Job? = null
    }
}
