package com.example.test_micrott.view

// ==========================================
// 1. Android 系统与 Jetpack 官方核心依赖库导入区
// ==========================================

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
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
import com.example.test_micrott.model.SpanDescriptor
import com.example.test_micrott.model.SpanType
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

    // Day 16 新增：格式化工具栏状态
    private var isFormattingToolbarVisible = false

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

    // Day 14：运行时权限请求（读照片权限）
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d(tag, "📷 [View] 照片权限已授予，启动自定义相册")
            launchGalleryPicker()
        } else {
            Log.d(tag, "📷 [View] 用户拒绝照片权限")
            Toast.makeText(this, "需要照片权限才能选择图片", Toast.LENGTH_SHORT).show()
        }
    }

    /** 根据 API 级别返回正确的照片读权限 */
    private val photoPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
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
        initFormattingToolbar()   // Day 16: 富文本格式化工具栏
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
     * Day 14 追加：先检查运行时权限。
     * 传入已选照片的 MediaStore._ID 列表，相册内会显示勾选标记。
     */
    private fun tryPickPhotos() {
        if (checkSelfPermission(photoPermission) == PackageManager.PERMISSION_GRANTED) {
            launchGalleryPicker()
        } else {
            Log.d(tag, "📷 [View] 照片权限未授予，请求中...")
            requestPermissionLauncher.launch(photoPermission)
        }
    }

    /** 权限已授予后，真正启动 GalleryPickerActivity */
    private fun launchGalleryPicker() {
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
            showMentionPicker()
        }

        binding.barEmoji.setOnClickListener {
            EmojiPickerDialog { emoji -> insertEmojiAtCursor(emoji) }
                .show(supportFragmentManager, "EmojiPicker")
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
        binding.barBold.isEnabled = !loading      // Day 16
        binding.barItalic.isEnabled = !loading    // Day 16
        binding.barColor.isEnabled = !loading     // Day 16

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
            reapplyProtectedSpans()
            reapplyFormattingSpans(state.formatSpanDescriptors)  // Day 16: 旋转恢复格式 Span
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

    /**
     * Day 13 升级：旋转恢复时重新着色所有保护性 Span（话题 #...# + 提及 @xxx）。
     */
    private fun reapplyProtectedSpans() {
        val editable = binding.ktg.text ?: return
        val text = editable.toString()
        if (text.isBlank()) return

        val oldSpans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        oldSpans.forEach { editable.removeSpan(it) }

        val blue = "#2A62FF".toColorInt()

        // 话题 #xxx#
        val topicPattern = Regex("#[^#]*#")
        topicPattern.findAll(text).forEach { match ->
            editable.setSpan(
                ForegroundColorSpan(blue),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 提及 @xxx（以空格/标点/结尾为边界）
        val mentionPattern = Regex("@[^\\s@#]+")
        mentionPattern.findAll(text).forEach { match ->
            editable.setSpan(
                ForegroundColorSpan(blue),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        if (topicPattern.containsMatchIn(text) || mentionPattern.containsMatchIn(text)) {
            Log.d(tag, "🎨 [View] 旋转恢复：保护性 Span 已重新着色")
        }
    }

    // ========================================================================
    // @提及
    // ========================================================================

    /**
     * 弹出用户选择弹窗。
     * 使用 AlertDialog 展示模拟用户列表，点击用户名后插入 @提及。
     */
    private fun showMentionPicker() {
        val userNames = arrayOf(
            "张三", "李四", "王五", "赵六", "孙七",
            "周杰伦", "刘德华", "张学友", "郭富城", "黎明",
            "范冰冰", "李冰冰", "杨幂", "赵丽颖", "刘亦菲",
        )

        AlertDialog.Builder(this)
            .setTitle("@ 提及用户")
            .setItems(userNames) { _, which ->
                val userName = userNames[which]
                insertMentionIntoEditor(userName)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 将 @用户名 以蓝色 Span 插入 EditText 光标位置。
     * 仅 @用户名 部分设 Span（不含尾部空格），与 reapplyProtectedSpans 的 regex 保持一致。
     */
    private fun insertMentionIntoEditor(userName: String) {
        val editable = binding.ktg.text ?: return
        var start = binding.ktg.selectionStart
        var end = binding.ktg.selectionEnd

        if (start < 0) {
            start = editable.length
            end = editable.length
        }

        val mentionText = "@$userName "
        val spannableStringBuilder = SpannableStringBuilder(mentionText)
        // 只对 @用户名 部分设 Span（不含尾部空格）
        spannableStringBuilder.setSpan(
            ForegroundColorSpan("#2A62FF".toColorInt()),
            0, mentionText.length - 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        editable.replace(start, end, spannableStringBuilder)
        binding.ktg.setSelection(start + mentionText.length)

        viewModel.sendIntent(PublishIntent.InsertMention(mentionText))
        Log.d(tag, "📝 [View] 插入 @提及: $mentionText")
    }

    // ========================================================================
    // ☺ 表情
    // ========================================================================

    /**
     * 将 emoji 字符插入 EditText 当前光标位置。
     * 不设 Span（emoji 是普通字符）。
     */
    private fun insertEmojiAtCursor(emoji: String) {
        val editable = binding.ktg.text ?: return
        var start = binding.ktg.selectionStart
        var end = binding.ktg.selectionEnd

        if (start < 0) {
            start = editable.length
            end = editable.length
        }

        editable.replace(start, end, emoji)
        binding.ktg.setSelection(start + emoji.length)
        Log.d(tag, "😊 [View] 插入表情: $emoji")
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

    // ========================================================================
    // Day 16: 富文本格式化 — 粗体 / 斜体 / 文字颜色
    // ========================================================================

    /**
     * 初始化格式化工具栏：绑定 B/I/A 按钮 + 显示/隐藏逻辑。
     */
    private fun initFormattingToolbar() {
        binding.barBold.setOnClickListener { toggleBold() }
        binding.barItalic.setOnClickListener { toggleItalic() }
        binding.barColor.setOnClickListener { showColorPicker() }

        // 触摸 EditText 时更新按钮状态（光标位置变化）
        binding.ktg.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                updateFormattingButtonStates(
                    binding.ktg.selectionStart,
                    binding.ktg.selectionEnd
                )
            }
            false
        }

        // 有文本时显示工具栏，无文本时隐藏
        binding.ktg.doAfterTextChanged { text ->
            val hasText = !text.isNullOrBlank()
            if (hasText && !isFormattingToolbarVisible) {
                showFormattingToolbar()
            } else if (!hasText && isFormattingToolbarVisible) {
                hideFormattingToolbar()
            }
        }
    }

    private fun showFormattingToolbar() {
        binding.formattingToolbarContainer.visibility = View.VISIBLE
        isFormattingToolbarVisible = true
    }

    private fun hideFormattingToolbar() {
        binding.formattingToolbarContainer.visibility = View.GONE
        isFormattingToolbarVisible = false
    }

    /**
     * 根据当前光标/选区位置更新 B/I/A 按钮的高亮状态。
     */
    private fun updateFormattingButtonStates(selStart: Int, selEnd: Int) {
        val editable = binding.ktg.text ?: return

        val checkStart = if (selStart == selEnd) selStart else selStart
        val checkEnd = if (selStart == selEnd) {
            (selEnd + 1).coerceAtMost(editable.length)
        } else selEnd

        if (checkStart < 0 || checkStart >= editable.length) {
            resetFormattingButtonStates()
            return
        }

        // 检查粗体
        val styleSpans = editable.getSpans(checkStart, checkEnd, StyleSpan::class.java)
        val isBold = styleSpans.any { it.style == Typeface.BOLD }
        val isItalic = styleSpans.any { it.style == Typeface.ITALIC }

        binding.barBold.setTextColor(
            if (isBold) "#2A62FF".toColorInt() else "#555555".toColorInt()
        )
        binding.barItalic.setTextColor(
            if (isItalic) "#2A62FF".toColorInt() else "#555555".toColorInt()
        )

        // 检查文字颜色（排除话题/提及蓝色）
        val topicMentionColor = "#2A62FF".toColorInt()
        val colorSpans = editable.getSpans(checkStart, checkEnd, ForegroundColorSpan::class.java)
        val activeColorSpan = colorSpans.firstOrNull {
            it.foregroundColor != topicMentionColor
        }
        binding.barColor.setTextColor(
            activeColorSpan?.foregroundColor ?: "#555555".toColorInt()
        )
    }

    private fun resetFormattingButtonStates() {
        binding.barBold.setTextColor("#555555".toColorInt())
        binding.barItalic.setTextColor("#555555".toColorInt())
        binding.barColor.setTextColor("#555555".toColorInt())
    }

    /**
     * 切换选中文本的粗体格式。
     *
     * 处理四种情况：
     * 1. 选区完全在粗体 Span 内 → 拆分移除
     * 2. 选区无粗体 → 添加 StyleSpan(Typeface.BOLD)
     * 3. 选区部分覆盖粗体 → 拆分
     * 4. 无选区 → 不操作
     */
    private fun toggleBold() {
        val editable = binding.ktg.text ?: return
        var selStart = binding.ktg.selectionStart
        var selEnd = binding.ktg.selectionEnd

        if (selStart < 0 || selStart == selEnd) return // 需要选区

        val existingBoldSpans = editable.getSpans(selStart, selEnd, StyleSpan::class.java)
            .filter { it.style == Typeface.BOLD }

        if (existingBoldSpans.isNotEmpty()) {
            // 移除模式：处理每个重迭的粗体 Span
            existingBoldSpans.forEach { span ->
                removeSpanFromSelection(editable, span, selStart, selEnd) {
                    StyleSpan(Typeface.BOLD)
                }
            }
        } else {
            // 添加模式
            editable.setSpan(
                StyleSpan(Typeface.BOLD), selStart, selEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        updateFormattingButtonStates(selStart, selEnd)
        saveCurrentFormattingState()
    }

    /**
     * 切换选中文本的斜体格式。逻辑与 toggleBold() 一致。
     */
    private fun toggleItalic() {
        val editable = binding.ktg.text ?: return
        var selStart = binding.ktg.selectionStart
        var selEnd = binding.ktg.selectionEnd

        if (selStart < 0 || selStart == selEnd) return

        val existingItalicSpans = editable.getSpans(selStart, selEnd, StyleSpan::class.java)
            .filter { it.style == Typeface.ITALIC }

        if (existingItalicSpans.isNotEmpty()) {
            existingItalicSpans.forEach { span ->
                removeSpanFromSelection(editable, span, selStart, selEnd) {
                    StyleSpan(Typeface.ITALIC)
                }
            }
        } else {
            editable.setSpan(
                StyleSpan(Typeface.ITALIC), selStart, selEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        updateFormattingButtonStates(selStart, selEnd)
        saveCurrentFormattingState()
    }

    /**
     * 弹出颜色选择器，锚定到 A 按钮。
     */
    private fun showColorPicker() {
        val editable = binding.ktg.text ?: return
        val selStart = binding.ktg.selectionStart
        val selEnd = binding.ktg.selectionEnd
        if (selStart < 0 || selStart == selEnd) return // 需要选区

        ColorPickerPopup(this) { color ->
            applyTextColor(color, selStart, selEnd)
        }.show(binding.barColor)
    }

    /**
     * 对选区应用文字颜色（ForegroundColorSpan）。
     * 先清除选区内已有的非话题/提及颜色 Span。
     */
    private fun applyTextColor(color: Int, selStart: Int, selEnd: Int) {
        val editable = binding.ktg.text ?: return
        val topicMentionColor = "#2A62FF".toColorInt()

        // 清除选区内已有的颜色 Span（保留话题/提及的）
        val colorSpans = editable.getSpans(selStart, selEnd, ForegroundColorSpan::class.java)
        colorSpans.filter { it.foregroundColor != topicMentionColor }.forEach { span ->
            removeSpanFromSelection(editable, span, selStart, selEnd) {
                ForegroundColorSpan(span.foregroundColor)
            }
        }

        // 应用新颜色
        editable.setSpan(
            ForegroundColorSpan(color), selStart, selEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.barColor.setTextColor(color)
        saveCurrentFormattingState()
        Log.d(tag, "🎨 [View] 应用文字颜色: #${Integer.toHexString(color)}")
    }

    /**
     * 通用 Span 选区移除/拆分工具。
     *
     * 处理 span 与选区 [selStart, selEnd) 的重迭：
     * - 完全在选区内 → 移除
     * - 选区在 span 内部 → 拆分为左右两段
     * - 左重迭 → 收缩 span 右端
     * - 右重迭 → 收缩 span 左端
     */
    private fun removeSpanFromSelection(
        editable: android.text.Editable,
        span: Any,
        selStart: Int,
        selEnd: Int,
        spanFactory: () -> Any
    ) {
        val s = editable.getSpanStart(span)
        val e = editable.getSpanEnd(span)

        when {
            s >= selStart && e <= selEnd -> {
                // span 完全在选区内 → 移除
                editable.removeSpan(span)
            }
            s < selStart && e > selEnd -> {
                // 选区在 span 内部 → 拆分为两段
                editable.removeSpan(span)
                editable.setSpan(spanFactory(), s, selStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                editable.setSpan(spanFactory(), selEnd, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            s >= selStart -> {
                // span 左端在选区内，右端在选区外 → 收缩左端
                editable.removeSpan(span)
                editable.setSpan(spanFactory(), selEnd, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            else -> {
                // span 右端在选区内，左端在选区外 → 收缩右端
                editable.removeSpan(span)
                editable.setSpan(spanFactory(), s, selStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /**
     * 提取当前 EditText 中所有格式化 Span，序列化后发往 ViewModel 持久化。
     */
    private fun saveCurrentFormattingState() {
        val editable = binding.ktg.text ?: return
        val descriptors = extractFormattingSpans(editable)
        viewModel.sendIntent(PublishIntent.SaveFormattingSpans(descriptors))
    }

    /**
     * 从 Editable 中提取所有格式化 Span（粗体/斜体/颜色）。
     * 排除话题/提及的保护性 ForegroundColorSpan (#2A62FF)。
     */
    private fun extractFormattingSpans(editable: android.text.Editable): List<SpanDescriptor> {
        val descriptors = mutableListOf<SpanDescriptor>()
        val topicMentionColor = "#2A62FF".toColorInt()

        // StyleSpan (BOLD / ITALIC)
        editable.getSpans(0, editable.length, StyleSpan::class.java).forEach { span ->
            val start = editable.getSpanStart(span)
            val end = editable.getSpanEnd(span)
            when (span.style) {
                Typeface.BOLD -> descriptors.add(SpanDescriptor(start, end, SpanType.BOLD))
                Typeface.ITALIC -> descriptors.add(SpanDescriptor(start, end, SpanType.ITALIC))
                Typeface.BOLD_ITALIC -> {
                    descriptors.add(SpanDescriptor(start, end, SpanType.BOLD))
                    descriptors.add(SpanDescriptor(start, end, SpanType.ITALIC))
                }
            }
        }

        // ForegroundColorSpan（非话题/提及颜色）
        editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
            .filter { it.foregroundColor != topicMentionColor }
            .forEach { span ->
                descriptors.add(SpanDescriptor(
                    editable.getSpanStart(span),
                    editable.getSpanEnd(span),
                    SpanType.COLOR,
                    span.foregroundColor
                ))
            }

        return descriptors
    }

    /**
     * 从 SpanDescriptor 列表恢复格式化 Span 到 EditText。
     * 在 reapplyProtectedSpans() 之后调用，确保格式颜色覆盖话题/提及蓝色。
     */
    private fun reapplyFormattingSpans(descriptors: List<SpanDescriptor>) {
        val editable = binding.ktg.text ?: return
        if (descriptors.isEmpty()) return

        val topicMentionColor = "#2A62FF".toColorInt()
        val textLen = editable.length

        // 先清除旧的格式化 Span（保留话题/提及的）
        val oldStyleSpans = editable.getSpans(0, textLen, StyleSpan::class.java)
        oldStyleSpans.forEach { editable.removeSpan(it) }

        val oldColorSpans = editable.getSpans(0, textLen, ForegroundColorSpan::class.java)
        oldColorSpans.filter { it.foregroundColor != topicMentionColor }
            .forEach { editable.removeSpan(it) }

        // 从 descriptors 重新应用
        descriptors.forEach { desc ->
            val safeStart = desc.start.coerceIn(0, textLen)
            val safeEnd = desc.end.coerceIn(0, textLen)
            if (safeStart >= safeEnd) return@forEach

            when (desc.type) {
                SpanType.BOLD -> editable.setSpan(
                    StyleSpan(Typeface.BOLD), safeStart, safeEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                SpanType.ITALIC -> editable.setSpan(
                    StyleSpan(Typeface.ITALIC), safeStart, safeEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                SpanType.COLOR -> editable.setSpan(
                    ForegroundColorSpan(desc.value), safeStart, safeEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        Log.d(tag, "🎨 [View] 格式化 Span 已恢复: ${descriptors.size} 个")
    }
}
