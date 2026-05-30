package com.example.test_micrott.view

// ==========================================
// 1. Android 系统与 Jetpack 官方核心依赖库导入区
// ==========================================
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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

    // Day 7 升级：记录本次启动相册时的剩余名额，回调中截断
    private var pendingImageSlots = 9

    // Day 8 新增：追踪上一次 loading 状态，用于检测"发布完成"瞬间显示 Toast
    private var wasLoading = false

    // Day 8 新增：缓存上次图片列表引用，避免每帧打字触发 notifyDataSetChanged
    private var lastImageList: List<Uri> = emptyList()

    // Day 9 升级：动态上限 PhotoPicker。每次 launch(pendingImageSlots) 传入剩余名额
    private val pickMultipleMedia = registerForActivityResult(
        PickMultipleVisualMediaDynamic()
    ) { uris ->
        if (uris.isNotEmpty()) {
            Log.d(tag, "📷 [View] PhotoPicker 返回 ${uris.size} 张，当前剩余名额 $pendingImageSlots")
            viewModel.sendIntent(PublishIntent.ImagesPicked(uris))
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
     * Day 7 新增：ItemTouchHelper 实现长按拖拽排序
     * 仅允许图片格子被拖拽，加号格子不可拖拽
     */
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
                // 加号格子不可参与拖拽
                if (viewHolder is ImageGridAdapter.AddViewHolder ||
                    target is ImageGridAdapter.AddViewHolder) {
                    return false
                }
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                imageGridAdapter.onItemMove(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // 不使用滑动删除
            }

            override fun isLongPressDragEnabled(): Boolean = true
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.gridImageContainer)
    }

    /**
     * Day 9 升级：启动相册前计算剩余名额，动态传给 PickMultipleVisualMediaDynamic。
     * 系统相册 UI 会显示正确的可选上限（如已有 2 张 → 显示"最多选择 7 张"）。
     */
    private fun tryPickPhotos() {
        val currentCount = imageGridAdapter.getImages().size
        pendingImageSlots = 9 - currentCount
        if (pendingImageSlots <= 0) {
            Log.d(tag, "📷 [View] 已达 9 张上限，阻止相册启动")
            return
        }
        Log.d(tag, "📷 [View] 启动相册，剩余名额 $pendingImageSlots")
        pickMultipleMedia.launch(pendingImageSlots)
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
            insertTopicIntoEditor(" #请输入话题# ")
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

    private fun insertTopicIntoEditor(topicText: String = " #请输入话题# ") {
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
                        if (start == end && start > spanStart && start < spanEnd) {
                            binding.ktg.setSelection(
                                if (start < (spanStart + spanEnd) / 2) spanStart else spanEnd
                            )
                            break
                        }
                        if (start != end) {
                            var newStart = start; var newEnd = end
                            if (start > spanStart && start < spanEnd) newStart = spanStart
                            if (end > spanStart && end < spanEnd) newEnd = spanEnd
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
