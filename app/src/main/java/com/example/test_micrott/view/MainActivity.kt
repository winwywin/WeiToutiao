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
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: PublishViewModel by viewModels()
    private val tag = "MVI_FRAMEWORK"

    // Day 5 新增：九宫格适配器
    private lateinit var imageGridAdapter: ImageGridAdapter

    // Day 5 新增：PhotoPicker 注册
    private val pickMultipleMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            Log.d(tag, "📷 [View] PhotoPicker 选中 ${uris.size} 张图片")
            // MVI 规范：View 层不直接修改数据，发送 ImagesPicked Intent 给 ViewModel
            viewModel.sendIntent(PublishIntent.ImagesPicked(uris))
        } else {
            Log.d(tag, "用户取消了相册选择")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initRecyclerView()       // 初始化九宫格
        initIntentEmitters()    // 架设事件发射器
        observeUiState()         // 监听状态流
        setupTopicTokenGuard()    // 启动话题守卫
    }

    /**
     * 初始化九宫格 RecyclerView
     */
    private fun initRecyclerView() {
        binding.gridImageContainer.layoutManager = GridLayoutManager(this, 3)
        imageGridAdapter = ImageGridAdapter()
        imageGridAdapter.setListeners(
            onAddClickListener = {
                // 点击加号，唤起 PhotoPicker
                pickMultipleMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onDeleteClickListener = { position ->
                // 点击删除，发送 RemoveImage Intent
                viewModel.sendIntent(PublishIntent.RemoveImage(position))
            }
        )
        binding.gridImageContainer.adapter = imageGridAdapter
    }

    /**
     * 【战术模块一：事件发射器架设（View -> ViewModel）】
     */
    private fun initIntentEmitters() {
        // 1. 拦截打字动作
        binding.ktg.doAfterTextChanged { text ->
            viewModel.sendIntent(PublishIntent.TextChanged(text.toString()))
        }

        // 2. 拦截发布按钮
        binding.btnPublish.setOnClickListener {
            viewModel.sendIntent(PublishIntent.ClickPublish)
        }

        // 3. Day 5 新增：拦截底部工具栏按钮
        binding.barTopic.setOnClickListener {
            insertTopicIntoEditor(" #请输入话题# ")
        }

        binding.barPhoto.setOnClickListener {
            pickMultipleMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        // 提及和表情暂时只做 UI 占位
        binding.barMention.setOnClickListener {
            Log.d(tag, "📝 [View] 提及按钮被点击（待实现）")
        }

        binding.barEmoji.setOnClickListener {
            Log.d(tag, "📝 [View] 表情按钮被点击（待实现）")
        }
    }

    /**
     * 【战术模块二：只读状态流监听（ViewModel -> View）】
     */
    private fun observeUiState() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                render(state)
            }
        }
    }

    /**
     * 【战术模块三：机械化增量渲染引擎】
     */
    private fun render(state: PublishState) {
        Log.d(tag, "📺 [View] 收到新 State 账本，开始执行全量机械化渲染: $state")

        // 1. 刷新发布按钮
        binding.btnPublish.isEnabled = state.isPublishButtonEnabled
        if (state.isPublishButtonEnabled) {
            binding.btnPublish.setBackgroundColor(Color.parseColor("#F85149"))
        } else {
            binding.btnPublish.setBackgroundColor(Color.parseColor("#A8A8A8"))
        }

        // 2. 刷新 Loading 遮罩
        binding.progressBarOverlay.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        // 3. 刷新输入框文本（防光标回弹）+ Day 6：旋转后话题 Span 重新着色
        if (binding.ktg.text.toString() != state.text) {
            binding.ktg.setText(state.text)
            reapplyTopicSpans()          // 扫描 #...# 重新着色
            binding.ktg.setSelection(state.text.length)
        }

        // 4. Day 5 新增：刷新九宫格
        if (state.selectedImages.isEmpty()) {
            binding.gridImageContainer.visibility = View.GONE
        } else {
            binding.gridImageContainer.visibility = View.VISIBLE
            imageGridAdapter.updateData(state.selectedImages)
        }
    }

    /**
     * 富文本核心算法：在当前光标处无错位插入高亮变色文本
     */
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
            0,
            topicText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        editable.replace(start, end, spannableStringBuilder)
        val newCursorPosition = start + topicText.length
        binding.ktg.setSelection(newCursorPosition)

        // 同步通知 ViewModel 文本已变更
        viewModel.sendIntent(PublishIntent.InsertTopic(topicText))
    }

    /**
     * Day 6 新增：旋转屏 / 进程重建后，扫描文本中的 #...# 模式重新着色
     *
     * 原理：EditText 在旋转后会自动恢复 plain text，但自定义 Spannable
     * （ForegroundColorSpan）不会随系统 Bundle 保留，需要手动重建。
     * 此方法利用正则匹配所有 #...# 话题标签，重新施加蓝色高亮。
     */
    private fun reapplyTopicSpans() {
        val editable = binding.ktg.text ?: return
        val text = editable.toString()
        if (text.isBlank()) return

        // 清除旧的话题 Span（防止叠加）
        val oldSpans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        oldSpans.forEach { editable.removeSpan(it) }

        // 重新扫描 #...# 模式
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

    /**
     * 话题 Token 守卫引擎（三重防线）
     */
    private fun setupTopicTokenGuard() {
        // A. 拦截退格键
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
                            val spanStart = editable.getSpanStart(span)
                            editable.delete(spanStart, spanEnd)
                            return@setOnKeyListener true
                        }
                    }
                }
            }
            false
        }

        // B. 拦截光标触摸（磁吸边界）
        binding.ktg.setOnClickListener {
            val position = binding.ktg.selectionStart
            val editable = binding.ktg.text ?: return@setOnClickListener
            val spans = editable.getSpans(position, position, ForegroundColorSpan::class.java)
            for (span in spans) {
                val start = editable.getSpanStart(span)
                val end = editable.getSpanEnd(span)
                if (position > start && position < end) {
                    if (position < (start + end) / 2) {
                        binding.ktg.setSelection(start)
                    } else {
                        binding.ktg.setSelection(end)
                    }
                    break
                }
            }
        }

        // C. AccessibilityDelegate 终极劫持
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
                            if (start < (spanStart + spanEnd) / 2) {
                                binding.ktg.setSelection(spanStart)
                            } else {
                                binding.ktg.setSelection(spanEnd)
                            }
                            break
                        }

                        if (start != end) {
                            var newStart = start
                            var newEnd = end
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
