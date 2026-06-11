package com.example.test_micrott.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.example.test_micrott.R
import com.example.test_micrott.data.ThumbnailCache
import kotlin.math.sqrt

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
 * nineGrid.thumbnailLoader = NineGridLayout.ThumbnailLoader { uri, w, h, cb ->
 *     lifecycleScope.launch(Dispatchers.IO) { cb(decodeBitmap ...) }
 * }
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

    /**
     * 异步加载缩略图回调接口。
     *
     * View 不直接依赖协程，而是通过此回调委托外部（如 Activity）执行异步 IO 任务。
     * 加载完成后回调 [onLoaded]，若加载过程中 View 已被重建，回调应安全地 no-op。
     *
     * ## 实现示例（在 Activity 中）
     * ```kotlin
     * nineGrid.thumbnailLoader = NineGridLayout.ThumbnailLoader { uri, targetW, targetH, onLoaded ->
     *     lifecycleScope.launch(Dispatchers.IO) {
     *         val bitmap = ImageCompressor.decodeSampledBitmap(context, uri, targetW, targetH)
     *         withContext(Dispatchers.Main) { onLoaded(bitmap) }
     *     }
     * }
     * ```
     */
    fun interface ThumbnailLoader {
        fun load(uri: Uri, targetWidth: Int, targetHeight: Int, onLoaded: (Bitmap?) -> Unit)
    }

    /** 异步缩略图加载器（替代旧的 scope 属性，防泄漏 + 去协程依赖） */
    var thumbnailLoader: ThumbnailLoader? = null

    /** 最多显示的图片数（不含加号按钮） */
    var maxCount: Int = 9

    /** 相邻格之间的间隔（px） */
    var gap: Int = dpToPx(4)

    var onAddClick: (() -> Unit)? = null
    var onDeleteClick: ((index: Int) -> Unit)? = null
    var onImageClick: ((index: Int) -> Unit)? = null

    /** 拖拽排序完成回调：from → to（已废弃，改用 onReorder） */
    @Deprecated("拖拽松手后用 onReorder 传递最终列表，避免双写")
    var onDragSwap: ((Int, Int) -> Unit)? = null

    /** 拖拽松手后回传完整最终顺序（替代逐帧 MoveImage） */
    var onReorder: ((List<Uri>) -> Unit)? = null

    // ── 拖拽状态 ──────────────────────────────────────────────────────────

    /** 启动拖拽的最小移动距离（px），低于此值视为点击 */
    private val dragThresholdPx = 12f

    /** 是否处于拖拽模式 */
    private var isDragging = false

    /** 是否已完成拖拽首次初始化（elevation 设置） */
    private var dragInitialized = false

    /** 被拖拽的图片 index（对应 images 列表） */
    private var dragImageIndex = -1

    /** 拖拽起始槽位（= dragImageIndex，拖拽前顺序未变） */
    private var dragSourceSlot = -1

    /** 虚槽映射：imageIndex → 当前显示槽位。拖拽中非 drag 图片由此决定 translation */
    private val virtualSlots = mutableMapOf<Int, Int>()

    /** 触摸点在 child 内的偏移 —— 始终设为 child 中心，使图片中心对齐手指 */
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f

    /** 被拖 child 的 onLayout 基准位置（用于计算 translation） */
    private var dragChildBaseLeft = 0
    private var dragChildBaseTop = 0

    // ── onInterceptTouchEvent 暂存 ─────────────────────────────────────

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var dragCandidateIndex = -1

    // ── 内部状态 ──────────────────────────────────────────────────────────

    private val images = mutableListOf<Uri>()

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

        thumbnailLoader?.load(uri, targetWidth = 400, targetHeight = 400) { bitmap ->
            if (bitmap != null) {
                ThumbnailCache.put(cacheKey, bitmap)
                // 安全检查：View 可能已被重建，确保 uri 仍是当前位置的图片
                if (images.getOrNull(index) == uri) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Accessibility — 满足 setOnTouchListener 的 performClick 合约
    // ══════════════════════════════════════════════════════════════════════

    override fun performClick(): Boolean {
        super.performClick()
        return true
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
     * 固定 3 列方形布局，所有格子（含加号）等宽等高。
     */
    private fun getColumnCount(totalCount: Int): Int = 3

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

    // ══════════════════════════════════════════════════════════════════════
    // 拖拽排序 — onInterceptTouchEvent + onTouchEvent
    //
    // 行为对齐旧版 RecyclerView + ItemTouchHelper：
    //   - 拖拽过程中只平移被拖图片跟随手指，其他图片不动
    //   - 松手时计算最终槽位 → 一次性 swap 数据 → rebuildChildren()
    // ══════════════════════════════════════════════════════════════════════

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 只有 1 张图时不需拖拽排序
        if (images.size <= 1) return false

        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = System.currentTimeMillis()
                dragCandidateIndex = indexAtPoint(ev.x, ev.y)
                isDragging = false
                dragInitialized = false

                if (dragCandidateIndex < 0 || dragCandidateIndex >= images.size) {
                    dragCandidateIndex = -1
                }
                Log.v("NineGrid", "DOWN: candidate=$dragCandidateIndex finger=(${ev.x},${ev.y}) layoutSize=${width}x${height}")
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragCandidateIndex < 0) return false

                val dx = ev.x - downX
                val dy = ev.y - downY
                val distance = sqrt(dx * dx + dy * dy)
                val elapsed = System.currentTimeMillis() - downTime

                if (distance > dragThresholdPx || (elapsed > 400 && distance > 4f)) {
                    isDragging = true
                    initDrag(dragCandidateIndex, ev)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    Log.d("NineGrid", "拖拽启动: imgIdx=$dragImageIndex finger=(${ev.x},${ev.y})")
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragCandidateIndex = -1
                isDragging = false
                dragInitialized = false
                return false
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDragging) return false

        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                applyDragTranslation(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                finishDrag(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelDrag()
                return true
            }
        }
        return isDragging
    }

    // ══════════════════════════════════════════════════════════════════════
    // 拖拽子方法
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 初始化拖拽：记录起点和触摸偏移，立即提升 elevation + bringToFront。
     */
    private fun initDrag(childIndex: Int, event: MotionEvent) {
        dragImageIndex = childIndex
        dragSourceSlot = childIndex
        dragInitialized = true

        // 初始化虚槽映射：所有图片各自在自己原位
        virtualSlots.clear()
        for (i in images.indices) {
            virtualSlots[i] = i
        }

        val child = getChildAt(childIndex)
        dragChildBaseLeft = child.left
        dragChildBaseTop = child.top
        // 始终用 child 中心对齐手指（长按瞬间图片跳到手指中心）
        touchOffsetX = child.width / 2f
        touchOffsetY = child.height / 2f

        Log.d("NineGrid", "=== initDrag === imgIdx=$childIndex " +
            "childRect=(${child.left},${child.top},${child.right},${child.bottom}) " +
            "childSize=${child.width}x${child.height} " +
            "fingerRaw=(${event.rawX},${event.rawY}) " +
            "fingerLocal=(${event.x},${event.y}) " +
            "touchOffset=($touchOffsetX,$touchOffsetY) " +
            "basePos=($dragChildBaseLeft,$dragChildBaseTop) " +
            "expectedCenter=(${dragChildBaseLeft + touchOffsetX},${dragChildBaseTop + touchOffsetY})")

        // 浮起效果：只用 elevation，不调 bringToFront()
        // bringToFront() 会改变 child 在内部数组的序号，导致 onLayout 把它布局到错误位置
        child.translationZ = 8f
        child.alpha = 0.92f

        // 立刻平移使图片中心对齐手指，不等下一次 MOVE
        applyDragTranslation(event.x, event.y)
    }

    /**
     * 平移 child 使其中心始终对准手指位置。
     * touchOffset = child 中心，所以 child 视觉中心 = 手指坐标。
     */
    private fun applyDragTranslation(fingerX: Float, fingerY: Float) {
        val child = findChildByImageIndex(dragImageIndex) ?: return
        val tx = fingerX - touchOffsetX - dragChildBaseLeft
        val ty = fingerY - touchOffsetY - dragChildBaseTop
        child.translationX = tx
        child.translationY = ty

        // 检测手指跨越槽位 → 更新虚槽映射 + 即时设置其他图片 translation（预览动画）
        val slot = getSlotAtPoint(fingerX, fingerY).coerceIn(0, images.size - 1)
        if (slot != virtualSlots[dragImageIndex]) {
            updateVirtualSlots(slot)
        }

        Log.v("NineGrid", "drag: finger=($fingerX,$fingerY) " +
            "slot=$slot translation=($tx,$ty) " +
            "virtualSlots=$virtualSlots")
    }

    /**
     * 更新虚槽映射 + 即时设置其他图片 translation，产生"预览动画"效果。
     *
     * 原理：维护 virtualSlots[imageIndex] 表，拖动图片跨越槽位时：
     *   - 拖动图片的虚槽 = 手指所在槽
     *   - 中间图片的虚槽依次偏移（向拖动方向挤一格）
     *   - 然后对每张非拖图片：translation = getSlotRect(虚槽) - getSlotRect(原始位置)
     *
     * 用即时设置（不用动画）避免多次跨槽时 translation 累积。
     */
    private fun updateVirtualSlots(newDragSlot: Int) {
        val oldDragSlot = virtualSlots[dragImageIndex] ?: return
        if (newDragSlot == oldDragSlot) return

        virtualSlots[dragImageIndex] = newDragSlot

        if (newDragSlot > oldDragSlot) {
            // 拖向更大槽位：中间图片向前挤一格
            for (i in images.indices) {
                if (i == dragImageIndex) continue
                val s = virtualSlots[i]!!
                if (s in (oldDragSlot + 1)..newDragSlot) {
                    virtualSlots[i] = s - 1
                }
            }
        } else {
            // 拖向更小槽位：中间图片向后挤一格
            for (i in images.indices) {
                if (i == dragImageIndex) continue
                val s = virtualSlots[i]!!
                if (s in newDragSlot until oldDragSlot) {
                    virtualSlots[i] = s + 1
                }
            }
        }

        // 即时应用 translation（不播放动画），松手时再统一做归位动画
        for (i in images.indices) {
            if (i == dragImageIndex) continue
            val child = findChildByImageIndex(i) ?: continue
            val visualSlot = virtualSlots[i]!!
            val originalRect = getSlotRect(i)
            val visualRect = getSlotRect(visualSlot)

            child.animate().cancel()
            child.translationX = (visualRect.left - originalRect.left).toFloat()
            child.translationY = (visualRect.top - originalRect.top).toFloat()
        }

        Log.d("NineGrid", "updateVirtualSlots: dragSlot $oldDragSlot→$newDragSlot, map=$virtualSlots")
    }

    /**
     * 松手：被拖图片动画归位 → 提交数据。
     *
     * 非拖图片已在 updateVirtualSlots 中即时设置到正确位置，松手时只需把
     * 拖拽图片从手指位置动画移到最终槽位。
     */
    private fun finishDrag(fingerX: Float, fingerY: Float) {
        parent?.requestDisallowInterceptTouchEvent(false)

        val finalSlot = virtualSlots[dragImageIndex]!!
            .coerceIn(0, maxOf(images.size - 1, 0))

        Log.d("NineGrid", "=== finishDrag === finger=($fingerX,$fingerY) " +
            "finalSlot=$finalSlot sourceSlot=$dragSourceSlot " +
            "virtualSlots=$virtualSlots")

        val child = findChildByImageIndex(dragImageIndex)
        if (child != null) {
            val targetRect = getSlotRect(finalSlot)
            val baseRect = getSlotRect(dragImageIndex)

            child.animate()
                .translationX((targetRect.left - baseRect.left).toFloat())
                .translationY((targetRect.top - baseRect.top).toFloat())
                .alpha(1f)
                .setDuration(200)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        commitDragResult(finalSlot)
                    }
                })
                .start()
        } else {
            commitDragResult(finalSlot)
        }
    }

    /**
     * 取消拖拽：所有图片动画回到原位。
     */
    private fun cancelDrag() {
        parent?.requestDisallowInterceptTouchEvent(false)

        for (i in 0 until images.size) {
            val child = findChildByImageIndex(i) ?: continue
            child.animate().cancel()
            child.animate()
                .translationX(0f)
                .translationY(0f)
                .alpha(1f)
                .setDuration(150)
                .start()
        }

        // 动画结束后重置 Z
        findChildByImageIndex(dragImageIndex)?.let { child ->
            child.animate().setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    resetAllTranslations()
                }
            })
        }

        virtualSlots.clear()
        isDragging = false
        dragInitialized = false
        dragImageIndex = -1
        dragSourceSlot = -1
    }

    /**
     * 提交拖拽结果：list.removeAt(dragSourceSlot).add(dropSlot) → rebuildChildren → 回传最终列表。
     * 不调 resetAllTranslations —— 旧 view 会被 rebuildChildren 移除，避免视觉闪烁。
     *
     * 通过 onReorder 回传完整最终列表（而非逐帧 from→to），避免 ViewModel 双写。
     */
    private fun commitDragResult(dropSlot: Int) {
        val finalImages = images.toMutableList()
        val draggedUri = finalImages.removeAt(dragSourceSlot)
        finalImages.add(dropSlot, draggedUri)

        images.clear()
        images.addAll(finalImages)
        rebuildChildren()

        if (dragSourceSlot != dropSlot) {
            onReorder?.invoke(images.toList())
        }

        virtualSlots.clear()
        isDragging = false
        dragInitialized = false
        dragImageIndex = -1
        dragSourceSlot = -1
    }

    // ══════════════════════════════════════════════════════════════════════
    // 拖拽辅助
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 根据触摸坐标计算所在槽位（纯几何计算，不受 child translation 影响）。
     */
    private fun getSlotAtPoint(x: Float, y: Float): Int {
        val cellSize = getCellSize()
        if (cellSize <= 0) return -1
        val pl = paddingLeft; val pt = paddingTop
        val totalCount = maxOf(childCount, 1)
        val cols = getColumnCount(totalCount)

        val col = ((x - pl) / (cellSize + gap)).toInt()
        val row = ((y - pt) / (cellSize + gap)).toInt()
        if (col < 0 || col >= cols || row < 0) return -1

        val slot = row * cols + col
        if (slot >= totalCount) return -1
        if (slot >= images.size) return -1
        return slot
    }

    /**
     * 当前格子尺寸（px）。所有格子等宽等高，由 onMeasure 确定。
     */
    private fun getCellSize(): Int {
        if (width <= 0) return 0
        val contentWidth = width - paddingLeft - paddingRight
        val totalCount = maxOf(childCount, 1)
        val cols = getColumnCount(totalCount)
        return (contentWidth - gap * (cols - 1)) / cols
    }

    /**
     * 计算槽位的像素矩形（与 onLayout 公式一致）。
     */
    private fun getSlotRect(slot: Int): Rect {
        val pl = paddingLeft; val pt = paddingTop
        val cellSize = getCellSize()
        val totalCount = maxOf(childCount, 1)
        val cols = getColumnCount(totalCount)

        val col = slot % cols
        val row = slot / cols
        val left = pl + col * (cellSize + gap)
        val top = pt + row * (cellSize + gap)
        return Rect(left, top, left + cellSize, top + cellSize)
    }

    /**
     * 通过 imageIndex 查找对应的 child View。
     * 因为 bringToFront() 会改变 child 序号，不能直接用 imageIndex 索引。
     */
    private fun findChildByImageIndex(imageIndex: Int): View? {
        val tag = "image_$imageIndex"
        for (i in 0 until childCount) {
            if (getChildAt(i).tag == tag) return getChildAt(i)
        }
        return null
    }

    /**
     * 重置所有 child 的 translation 和 Z 值。
     */
    private fun resetAllTranslations() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.animate().cancel()
            child.translationX = 0f
            child.translationY = 0f
            child.translationZ = 0f
        }
    }
}
