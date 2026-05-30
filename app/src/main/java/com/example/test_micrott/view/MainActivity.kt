package com.example.test_micrott.view

// ==========================================
// 1. Android 系统与 Jetpack 官方核心依赖库导入区
// ==========================================
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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

    // PhotoPicker 注册（max 9 由系统护栏兜底，实际由 pendingImageSlots 截断）
    private val pickMultipleMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val limited = if (uris.size > pendingImageSlots) uris.take(pendingImageSlots) else uris
            Log.d(tag, "📷 [View] PhotoPicker 返回 ${uris.size} 张，当前剩余名额 $pendingImageSlots，实际接收 ${limited.size} 张")
            viewModel.sendIntent(PublishIntent.ImagesPicked(limited))
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
     * Day 7 新增：启动相册前计算剩余名额，满 9 张不启动
     */
    private fun tryPickPhotos() {
        val currentCount = imageGridAdapter.getImages().size
        pendingImageSlots = 9 - currentCount
        if (pendingImageSlots <= 0) {
            Log.d(tag, "📷 [View] 已达 9 张上限，阻止相册启动")
            return
        }
        pickMultipleMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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

    private fun render(state: PublishState) {
        Log.d(tag, "📺 [View] 收到新 State 账本，开始执行全量机械化渲染: $state")

        // 1. 发布按钮
        binding.btnPublish.isEnabled = state.isPublishButtonEnabled
        binding.btnPublish.setBackgroundColor(
            if (state.isPublishButtonEnabled) Color.parseColor("#F85149")
            else Color.parseColor("#A8A8A8")
        )

        // 2. Loading 遮罩
        binding.progressBarOverlay.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        // 3. 输入框文本 + Span 恢复
        if (binding.ktg.text.toString() != state.text) {
            binding.ktg.setText(state.text)
            reapplyTopicSpans()
            binding.ktg.setSelection(state.text.length)
        }

        // 4. 九宫格 — Day 7 修复：空状态始终可见（显示加号按钮）
        binding.gridImageContainer.visibility = View.VISIBLE
        imageGridAdapter.updateData(state.selectedImages)

        // 5. 底部照片按钮 — Day 7 新增：满 9 张禁用
        val isFull = state.selectedImages.size >= 9
        binding.barPhoto.isEnabled = !isFull
        binding.barPhoto.alpha = if (isFull) 0.35f else 1.0f
    }

    // ========================================================================
    // 话题插入
    // ========================================================================

    private fun insertTopicIntoEditor(topicText: String) {
        val editable = binding.ktg.text ?: return
        var start = binding.ktg.selectionStart
        var end = binding.ktg.selectionEnd

        if (start < 0) {
            start = editable.length
            end = editable.length
        }

        val spannableStringBuilder = SpannableStringBuilder(topicText)
        spannableStringBuilder.setSpan(
            ForegroundColorSpan(Color.parseColor("#2A62FF")),
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
                ForegroundColorSpan(Color.parseColor("#2A62FF")),
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
                if (position > start && position < end) {
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
