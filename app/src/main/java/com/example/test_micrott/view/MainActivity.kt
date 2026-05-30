package com.example.test_micrott.view

// ==========================================
// 1. Android 系统与 Jetpack 官方核心依赖库导入区
// ==========================================

import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

// ==========================================
// 2. 本地项目业务组件导入
// ==========================================
import com.example.test_micrott.databinding.ActivityMainBinding
import com.example.test_micrott.model.PublishIntent
import com.example.test_micrott.model.PublishState
import com.example.test_micrott.viewmodels.PublishViewModel

/**
 * 【提线木偶层 - MainActivity】
 * Day 5：MVC 沙盒 PhotoPicker + 话题插入 + 九宫格逻辑迁移至 MVI
 * Day 6：SavedStateHandle 旋转屏恢复 + 话题 Span 自动重新着色
 * Day 7：空状态加号可见 + 动态相册上限 + 拖拽排序
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: PublishViewModel by viewModels()
    private val tag = "MVI_FRAMEWORK"

    private lateinit var imageGridAdapter: ImageGridAdapter

    // Day 8 新增：追踪上一次 loading 状态，用于检测"发布完成"瞬间显示 Toast
    private var wasLoading = false

    // Day 8 新增：缓存上次图片列表引用，避免每帧打字触发 notifyDataSetChanged
    private var lastImageList: List<Uri> = emptyList()

    // Day 11 升级：自定义相册选择器，支持已选照片跨会话勾选标记
    private val pickMultipleMedia = registerForActivityResult(
        GalleryPickerContract()
    ) { resultUris ->
        if (resultUris != null && resultUris.isNotEmpty()) {
            Log.d(tag, "📷 [View] 自定义相册返回 ${resultUris.size} 张")
            viewModel.sendIntent(PublishIntent.ImagesPicked(resultUris))
        } else {
            Log.d(tag, "用户取消了相册选择")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initRecyclerView()
        initDragSupport()         // Day 7: 拖拽排序
        initIntentEmitters()
        observeUiState()
        setupTopicTokenGuard()
    }

    // ========================================================================
    // 初始化
    // ========================================================================

    private fun initRecyclerView() {
        binding.gridImageContainer.layoutManager = GridLayoutManager(this, 3)
        imageGridAdapter = ImageGridAdapter()
        imageGridAdapter.setListeners(
            onAddClickListener = {
                tryPickPhotos()
            },
            onDeleteClickListener = { position ->
                viewModel.sendIntent(PublishIntent.RemoveImage(position))
            },
            onMoveListener = { from, to ->
                viewModel.sendIntent(PublishIntent.MoveImage(from, to))
            }
        )
        binding.gridImageContainer.adapter = imageGridAdapter
    }

    /**
     * Day 11 重构：松手吸附式拖拽排序
     *
     * 旧行为（Day 7）：拖动过程中经过某个位置时立即交换 — 用户体验差。
     * 新行为：长按提起 → 自由拖动（不交换）→ 松手后自动吸附到最近网格位置。
     *
     * 实现：onMove 始终返回 false，禁止实时交换；clearView 时用
     *       findChildViewUnder 找到距离松手位置最近的格子，做单次移动。
     */
    private var dragStartPosition = RecyclerView.NO_POSITION

    private fun initDragSupport() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0  // 不处理滑动删除
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                // 加号格子 / 任何格子：拖动过程中均不换位
                if (viewHolder is ImageGridAdapter.AddViewHolder ||
                    target is ImageGridAdapter.AddViewHolder) {
                    return false
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // 不使用滑动删除
            }

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(
                viewHolder: RecyclerView.ViewHolder?,
                actionState: Int
            ) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    dragStartPosition = viewHolder?.adapterPosition
                        ?: RecyclerView.NO_POSITION
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)

                val from = dragStartPosition
                dragStartPosition = RecyclerView.NO_POSITION
                if (from == RecyclerView.NO_POSITION) return

                // 计算拖拽项松手时的中心点（包含拖拽位移 translationX/Y）
                val itemView = viewHolder.itemView
                val centerX = itemView.left + itemView.translationX +
                        itemView.width / 2f
                val centerY = itemView.top + itemView.translationY +
                        itemView.height / 2f

                val imageCount = imageGridAdapter.getImages().size
                val spanCount = 3
                var targetPos = RecyclerView.NO_POSITION

                // 先尝试找中心点正下方格子
                val childUnder = recyclerView.findChildViewUnder(centerX, centerY)
                if (childUnder != null) {
                    targetPos = recyclerView.getChildAdapterPosition(childUnder)
                }

                // 拖到空白区域/无 childView → 根据坐标手动算网格位置
                if (targetPos == RecyclerView.NO_POSITION) {
                    val cellW = (recyclerView.width - recyclerView.paddingLeft -
                            recyclerView.paddingRight).toFloat() / spanCount
                    val cellH = itemView.height.toFloat()
                    val col = ((centerX - recyclerView.paddingLeft) / cellW)
                        .toInt().coerceIn(0, spanCount - 1)
                    val row = ((centerY - recyclerView.paddingTop) / cellH)
                        .toInt().coerceAtLeast(0)
                    targetPos = (row * spanCount + col).coerceIn(0, imageCount - 1)
                }

                // 过滤加号位置：不允许吸附到加号
                if (targetPos >= imageCount) {
                    targetPos = imageCount - 1
                }

                if (targetPos != from && targetPos >= 0) {
                    imageGridAdapter.moveSingleItem(from, targetPos)
                }
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.gridImageContainer)
    }

    /**
     * Day 11 升级：启动自定义相册选择器。
     * 传入已选照片的 MediaStore._ID 列表，相册内会显示勾选标记。
     */
    private fun tryPickPhotos() {
        val currentImages = imageGridAdapter.getImages()
        val maxSlots = 9 - currentImages.size
        if (maxSlots <= 0) {
            Log.d(tag, "📷 [View] 已达 9 张上限，阻止相册启动")
            return
        }
        val preSelectedIds = extractMediaIds(currentImages)
        Log.d(tag, "📷 [View] 启动自定义相册，剩余名额 $maxSlots，已选 ${preSelectedIds.size} 张")
        pickMultipleMedia.launch(PickConfig(maxSelectable = maxSlots, preSelectedIds = preSelectedIds))
    }

    /**
     * 从 URI 列表中提取 MediaStore.Images.Media._ID
     */
    private fun extractMediaIds(uris: List<Uri>): List<Long> {
        return uris.mapNotNull { uri ->
            try {
                uri.lastPathSegment?.toLong()
            } catch (_: NumberFormatException) {
                // uri 不是标准 MediaStore 格式时，通过 contentResolver 查询
                val cursor = contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Images.Media._ID),
                    null, null, null
                )
                cursor?.use {
                    if (it.moveToFirst()) it.getLong(0) else null
                }
            }
        }
    }

    // ========================================================================
    // MVI 三件套
    // ========================================================================

    private fun initIntentEmitters() {
        binding.ktg.doAfterTextChanged { text ->
            viewModel.sendIntent(PublishIntent.TextChanged(text.toString()))
        }

        binding.btnPublish.setOnClickListener {
            viewModel.sendIntent(PublishIntent.ClickPublish)
        }

        // Day 9 新增：左上角"取消"按钮
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.barTopic.setOnClickListener {
            insertTopicIntoEditor()
        }

        binding.barPhoto.setOnClickListener {
            tryPickPhotos()   // Day 7: 改为动态剩余名额
        }

        binding.barMention.setOnClickListener {
            Log.d(tag, "📝 [View] 提及按钮被点击（待实现）")
        }

        binding.barEmoji.setOnClickListener {
            Log.d(tag, "📝 [View] 表情按钮被点击（待实现）")
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                render(state)
            }
        }
    }

    // ========================================================================
    // 渲染引擎
    // ========================================================================

    /**
     * Day 8 重构：render() 性能优化 + loading 守卫
     *
     * 修复两个关键 bug：
     * 1. 打字卡顿：跳过不必要的 updateData（用 === 引用比较），去除冗余操作
     * 2. Loading 永不消失：loading 状态下禁用所有交互控件
     */
    private fun render(state: PublishState) {
        Log.d(tag, "📺 [View] 收到新 State 账本，开始渲染: isLoading=${state.isLoading}, textLen=${state.text.length}, images=${state.selectedImages.size}")

        val loading = state.isLoading

        // ================================================================
        // 1. Loading 遮罩
        // ================================================================
        binding.progressBarOverlay.visibility = if (loading) View.VISIBLE else View.GONE

        // ================================================================
        // 2. Loading 守卫：禁用所有可交互控件，防止发布中继续编辑
        // ================================================================
        binding.ktg.isEnabled = !loading
        binding.btnPublish.isEnabled = state.isPublishButtonEnabled && !loading
        binding.barTopic.isEnabled = !loading
        binding.barMention.isEnabled = !loading
        binding.barEmoji.isEnabled = !loading

        val isFull = state.selectedImages.size >= 9
        binding.barPhoto.isEnabled = !loading && !isFull

        // ================================================================
        // 3. 发布按钮颜色
        // ================================================================
        binding.btnPublish.setBackgroundColor(
            if (state.isPublishButtonEnabled && !loading) "#F85149".toColorInt()
            else "#A8A8A8".toColorInt()
        )

        // ================================================================
        // 4. 底部照片按钮 alpha（满 9 张或 loading 时变灰）
        // ================================================================
        binding.barPhoto.alpha = if (isFull || loading) 0.35f else 1.0f

        // ================================================================
        // 5. 输入框文本：仅在外部变更（SavedState 恢复 / 发布重置）时回写
        //    正常打字时 EditText.text == state.text，此分支不触发，避免光标跳动
        // ================================================================
        if (binding.ktg.text.toString() != state.text) {
            binding.ktg.setText(state.text)
            reapplyTopicSpans()
            binding.ktg.setSelection(state.text.length)
        }

        // ================================================================
        // 6. 九宫格：仅图片列表引用变化时才 updateData，避免每帧打字触发
        //    notifyDataSetChanged（PublishState.copy 对未改字段保持原引用）
        // ================================================================
        if (state.selectedImages !== lastImageList) {
            imageGridAdapter.updateData(state.selectedImages)
            lastImageList = state.selectedImages
        }

        // ================================================================
        // 7. 发布完成检测：loading 从 true→false + 表单已清空 → 弹 Toast
        // ================================================================
        if (wasLoading && !loading && state.text.isEmpty() && state.selectedImages.isEmpty()) {
            Toast.makeText(this, "发布成功", Toast.LENGTH_SHORT).show()
        }
        wasLoading = loading
    }

    // ========================================================================
    // 话题插入
    // ========================================================================

    private fun insertTopicIntoEditor() {
        val topicText = " #请输入话题# "
        val editable = binding.ktg.text ?: return
        var start = binding.ktg.selectionStart
        var end = binding.ktg.selectionEnd

        if (start < 0) {
            start = editable.length
            end = editable.length
        }

        val spannableStringBuilder = SpannableStringBuilder(topicText)
        spannableStringBuilder.setSpan(
            ForegroundColorSpan("#2A62FF".toColorInt()),
            0, topicText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        editable.replace(start, end, spannableStringBuilder)
        binding.ktg.setSelection(start + topicText.length)

        viewModel.sendIntent(PublishIntent.InsertTopic(topicText))
    }

    private fun reapplyTopicSpans() {
        val editable = binding.ktg.text ?: return
        val text = editable.toString()
        if (text.isBlank()) return

        val oldSpans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        oldSpans.forEach { editable.removeSpan(it) }

        val pattern = Regex("#[^#]*#")
        pattern.findAll(text).forEach { match ->
            editable.setSpan(
                ForegroundColorSpan("#2A62FF".toColorInt()),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (pattern.containsMatchIn(text)) {
            Log.d(tag, "🎨 [View] 旋转恢复：话题 Span 已重新着色")
        }
    }

    // ========================================================================
    // 话题 Token 守卫
    // ========================================================================

    private fun setupTopicTokenGuard() {
        binding.ktg.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                val start = binding.ktg.selectionStart
                val end = binding.ktg.selectionEnd
                if (start == end) {
                    val editable = binding.ktg.text
                    val spans = editable.getSpans(start, start, ForegroundColorSpan::class.java)
                    for (span in spans) {
                        val spanEnd = editable.getSpanEnd(span)
                        if (start == spanEnd) {
                            editable.delete(editable.getSpanStart(span), spanEnd)
                            return@setOnKeyListener true
                        }
                    }
                }
            }
            false
        }

        binding.ktg.setOnClickListener {
            val position = binding.ktg.selectionStart
            val editable = binding.ktg.text ?: return@setOnClickListener
            val spans = editable.getSpans(position, position, ForegroundColorSpan::class.java)
            for (span in spans) {
                val start = editable.getSpanStart(span)
                val end = editable.getSpanEnd(span)
                if (position in (start + 1)..<end) {
                    binding.ktg.setSelection(if (position < (start + end) / 2) start else end)
                    break
                }
            }
        }

        binding.ktg.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                super.sendAccessibilityEvent(host, eventType)
                if (eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                    val start = binding.ktg.selectionStart
                    val end = binding.ktg.selectionEnd
                    val editable = binding.ktg.text ?: return
                    val spans = editable.getSpans(start, end, ForegroundColorSpan::class.java)
                    for (span in spans) {
                        val spanStart = editable.getSpanStart(span)
                        val spanEnd = editable.getSpanEnd(span)
                        if (start == end && start in (spanStart+1)..<spanEnd) {
                            binding.ktg.setSelection(
                                if (start < (spanStart + spanEnd) / 2) spanStart else spanEnd
                            )
                            break
                        }
                        if (start != end) {
                            var newStart = start; var newEnd = end
                            if (start in (spanStart+1)..<spanEnd) newStart = spanStart
                            if (end in (spanStart+1)..<spanEnd) newEnd = spanEnd
                            if (newStart != start || newEnd != end) {
                                binding.ktg.setSelection(newStart, newEnd)
                                break
                            }
                        }
                    }
                }
            }
        }
    }
}
