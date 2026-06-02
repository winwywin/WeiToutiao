package com.example.test_micrott.views

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.example.test_micrott.R
import com.example.test_micrott.data.ImageCompressor
import com.example.test_micrott.data.ThumbnailCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * A9 自定义九宫格 ViewGroup — NineGridLayout
 *
 * 实现思路：完全自定义 onMeasure / onLayout，不依赖 RecyclerView 或 GridLayoutManager。
 *
 * ## 布局规则（与头条微头条保持一致）
 * - 0 张：显示单个"加号"按钮（整行宽度 / 3 正方形）
 * - 1 张：全宽正方形（75% 父宽）
 * - 2 张：2 列等宽，正方形
 * - 3 张：3 列等宽，正方形（单行）
 * - 4 张：2 × 2 方格
 * - 5 张：上 2 下 3（或 3 + 2，此处取 2 + 3）
 * - 6 张：2 × 3（2 行，每行 3 列）
 * - 7 张：3 + 4（或 3 + 3 + 1）此处 3 列 x 3 行，最后行仅 1 个
 * - 8 张：3 × 3，最后行 2 个
 * - 9 张：3 × 3 方格
 *
 * 实际上统一按 **最多 3 列** 自动换行布局（类似九宫格 GridLayout），但行高由列数决定（正方形格）。
 *
 * ## 使用方式（XML）
 * ```xml
 * <com.example.test_micrott.views.NineGridLayout
 *     android:id="@+id/nine_grid"
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     android:padding="12dp" />
 * ```
 *
 * ## 使用方式（代码）
 * ```kotlin
 * nineGrid.scope = lifecycleScope
 * nineGrid.setImages(state.selectedImages, maxCount = 9)
 * nineGrid.onAddClick = { viewModel.sendIntent(PublishIntent.AddPhotoClick) }
 * nineGrid.onDeleteClick = { index -> viewModel.sendIntent(PublishIntent.DeletePhoto(index)) }
 * nineGrid.onImageClick = { index -> launchPreview(index) }
 * ```
 */
class NineGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    // ── 公开接口 ─────────────────────────────────────────────────────────

    /** 协程作用域，用于异步加载缩略图 */
    var scope: CoroutineScope? = null

    /** 最多显示的图片数（不含加号按钮） */
    var maxCount: Int = 9

    /** 相邻格之间的间隔（px） */
    var gap: Int = dpToPx(4)

    var onAddClick: (() -> Unit)? = null
    var onDeleteClick: ((index: Int) -> Unit)? = null
    var onImageClick: ((index: Int) -> Unit)? = null

    // ── 内部状态 ──────────────────────────────────────────────────────────

    private val images = mutableListOf<Uri>()

    /** 加载任务 Map：itemIndex → Job */
    private val loadJobs = mutableMapOf<Int, Job>()

    private val decodeSemaphore = Semaphore(3)

    // ── 公开数据驱动 API ──────────────────────────────────────────────────

    /**
     * 更新图片列表。传入新列表时会做精准差量更新，避免全部重建。
     */
    fun setImages(newImages: List<Uri>, maxCount: Int = this.maxCount) {
        this.maxCount = maxCount

        // 精准差量：清理移除图片的缓存
        val newKeys = newImages.map { it.toString() }.toHashSet()
        images.forEach { uri ->
            if (uri.toString() !in newKeys) ThumbnailCache.remove(uri.toString())
        }

        images.clear()
        images.addAll(newImages)
        rebuildChildren()
    }

    fun getImages(): List<Uri> = images.toList()

    // ══════════════════════════════════════════════════════════════════════
    // 子 View 重建
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 根据当前 images 列表重建所有子 View。
     * 总格数 = images.size + 1（加号），但如果 images.size == maxCount，不显示加号。
     */
    private fun rebuildChildren() {
        // 取消所有旧任务
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()

        removeAllViews()

        val imageCount = images.size
        val showAdd = imageCount < maxCount

        // 先添加图片 View
        for (i in images.indices) {
            addView(createImageView(i))
        }
        // 再添加加号
        if (showAdd) {
            addView(createAddView())
        }

        requestLayout()
        invalidate()
    }

    // ── 子 View 工厂 ──────────────────────────────────────────────────────

    private fun createImageView(index: Int): View {
        // 复用现有 item_publish_image.xml 布局，保证视觉一致性
        val itemView = android.view.LayoutInflater.from(context)
            .inflate(R.layout.item_publish_image, this, false)
        itemView.tag = "image_$index"

        val imageView = itemView.findViewById<ImageView>(R.id.iv_thumnail)
        val deleteBtn = itemView.findViewById<View>(R.id.view_delete_fork)

        deleteBtn.setOnClickListener { onDeleteClick?.invoke(index) }
        itemView.setOnClickListener { onImageClick?.invoke(index) }

        // 异步加载缩略图
        loadThumbnail(index, imageView)

        return itemView
    }

    private fun createAddView(): View {
        val v = android.view.LayoutInflater.from(context)
            .inflate(R.layout.item_publish_add, this, false)
        v.tag = "add_button"
        v.setOnClickListener { onAddClick?.invoke() }
        return v
    }

    private fun loadThumbnail(index: Int, imageView: ImageView) {
        val uri = images.getOrNull(index) ?: return
        val cacheKey = uri.toString()
        val cached = ThumbnailCache.get(cacheKey)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        val job = scope?.launch {
            val bitmap = decodeSemaphore.withPermit {
                withContext(Dispatchers.IO) {
                    ImageCompressor.decodeSampledBitmap(
                        context, uri, targetWidth = 400, targetHeight = 400
                    )
                }
            }
            if (bitmap != null) {
                ThumbnailCache.put(cacheKey, bitmap)
                // 如果 View 还在
                if (images.getOrNull(index) == uri) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
        if (job != null) loadJobs[index] = job
    }

    // ══════════════════════════════════════════════════════════════════════
    // onMeasure — 核心：计算每个格的尺寸 + 整体高度
    // ══════════════════════════════════════════════════════════════════════

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val pl = paddingLeft; val pr = paddingRight
        val pt = paddingTop; val pb = paddingBottom
        val contentWidth = parentWidth - pl - pr

        val totalCount = childCount
        if (totalCount == 0) {
            setMeasuredDimension(parentWidth, pt + pb)
            return
        }

        val cols = getColumnCount(totalCount)
        val cellSize = (contentWidth - gap * (cols - 1)) / cols

        // 计算行数
        val rows = Math.ceil(totalCount.toDouble() / cols).toInt()
        val totalHeight = pt + pb + rows * cellSize + (rows - 1) * gap

        // 强制每个 child 测量为 cellSize × cellSize
        val cellSpec = MeasureSpec.makeMeasureSpec(cellSize, MeasureSpec.EXACTLY)
        for (i in 0 until totalCount) {
            getChildAt(i).measure(cellSpec, cellSpec)
        }

        setMeasuredDimension(parentWidth, totalHeight)
    }

    // ══════════════════════════════════════════════════════════════════════
    // onLayout — 核心：按行列计算每个 child 的位置
    // ══════════════════════════════════════════════════════════════════════

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pl = paddingLeft; val pt = paddingTop
        val contentWidth = (r - l) - pl - paddingRight

        val totalCount = childCount
        if (totalCount == 0) return

        val cols = getColumnCount(totalCount)
        val cellSize = (contentWidth - gap * (cols - 1)) / cols

        for (i in 0 until totalCount) {
            val child = getChildAt(i)
            val col = i % cols
            val row = i / cols

            val left = pl + col * (cellSize + gap)
            val top = pt + row * (cellSize + gap)
            child.layout(left, top, left + cellSize, top + cellSize)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 根据总格子数决定列数：
     * - 1 张：1 列（大图模式）
     * - 2 张：2 列
     * - 其余：3 列
     */
    private fun getColumnCount(totalCount: Int): Int = when (totalCount) {
        1 -> 1
        2 -> 2
        else -> 3
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 拖拽排序辅助：交换两个图片位置并刷新 UI（不通知 ViewModel，用于拖拽预览）
     */
    fun previewSwap(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in images.indices || toIndex !in images.indices) return
        val tmp = images[fromIndex]
        images[fromIndex] = images[toIndex]
        images[toIndex] = tmp
        rebuildChildren()
    }

    /**
     * 拖拽排序最终提交：交换并通知外部
     */
    fun commitSwap(fromIndex: Int, toIndex: Int, onMoveListener: (Int, Int) -> Unit) {
        previewSwap(fromIndex, toIndex)
        onMoveListener(fromIndex, toIndex)
    }

    /**
     * 根据触摸坐标获取对应格子的 index（用于拖拽 HitTest）
     */
    fun indexAtPoint(x: Float, y: Float): Int {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                return i
            }
        }
        return -1
    }
}
