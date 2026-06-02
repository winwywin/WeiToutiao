package com.example.test_micrott.views

// ==========================================
// 1. Android 系统与 Jetpack 官方核心依赖库导入区
// ==========================================

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

// ==========================================
// 2. 本地项目业务组件导入
// ==========================================
import com.example.test_micrott.R
import com.example.test_micrott.databinding.ActivityMainBinding
import com.example.test_micrott.models.PublishIntent
import com.example.test_micrott.models.PublishState
import com.example.test_micrott.models.SpanDescriptor
import com.example.test_micrott.models.SpanType
import com.example.test_micrott.models.TopicItem
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

    // Day 8 新增：缓存上次图片列表引用，避免每帧打字触发 notifyDataSetChanged
    private var lastImageList: List<Uri> = emptyList()

    // Day 16 新增：格式化工具栏状态
    private var isFormattingToolbarVisible = false

    // Day 16 type-ahead：无选区时点击格式按钮 → 激活待定格式 → 后续输入自动带格式（类似 Word）
    private var pendingBoldActive = false
    private var pendingItalicActive = false
    private var pendingColor: Int? = null

    // 防止 TextWatcher 在程序化文本变更时误触发待定格式应用
    private var isProgrammaticChange = false

    // Day 11 升级：自定义相册选择器，支持已选照片跨会话勾选标记
    private val pickMultipleMedia = registerForActivityResult(
        GalleryPickerContract()
    ) { resultUris ->
        if (!resultUris.isNullOrEmpty()) {
            Log.d(tag, "📷 [View] 自定义相册返回 ${resultUris.size} 张")
            viewModel.sendIntent(PublishIntent.ImagesPicked(resultUris))
        } else {
            Log.d(tag, "用户取消了相册选择")
        }
    }

    // Day 22+：草稿箱列表页 — 返回时恢复选中草稿
    private val draftListLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val draftId = result.data?.getLongExtra(
                DraftListActivity.EXTRA_RESTORED_DRAFT_ID, -1L
            ) ?: -1L
            if (draftId > 0) {
                Log.d(tag, "📋 [View] 草稿箱返回，恢复草稿 id=$draftId")
                viewModel.sendIntent(PublishIntent.RestoreDraft(draftId))
            }
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
        setupBackPressedDispatcher()  // Day 22++: 返回键拦截改用 OnBackPressedDispatcher
    }

    // ========================================================================
    // 初始化
    // ========================================================================

    private fun initRecyclerView() {
        binding.gridImageContainer.layoutManager = GridLayoutManager(this, 3)
        imageGridAdapter = ImageGridAdapter(lifecycleScope)
        imageGridAdapter.setListeners(
            onAddClickListener = {
                tryPickPhotos()
            },
            onDeleteClickListener = { position ->
                viewModel.sendIntent(PublishIntent.RemoveImage(position))
            },
            onMoveListener = { from, to ->
                viewModel.sendIntent(PublishIntent.MoveImage(from, to))
            },
            onImageClickListener = { position ->
                launchImagePreview(position)
            }
        )
        binding.gridImageContainer.adapter = imageGridAdapter
    }

    /**
     * Day 11 → Day 31 重构：实时占位预览式拖拽排序
     *
     * 旧行为（Day 11）：onMove 返回 false，松手后一次性计算目标位置并交换。
     * 新行为：拖动过程中 onMove 返回 true + notifyItemMoved，
     *        其他图片实时让出空位（类似手机桌面图标拖动），
     *        但只有松手才提交最终顺序到 ViewModel。
     *
     * 实现：onMove → previewOnItemMove（仅本地交换 + 动画，不通知 VM）
     *       clearView → 收集最终顺序 → ReorderImages 通知 VM
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
                // 加号格子不允许拖入
                if (viewHolder is ImageGridAdapter.AddViewHolder ||
                    target is ImageGridAdapter.AddViewHolder) {
                    return false
                }
                // Day 31：实时预览换位，仅本地交换 + 动画，不通知 ViewModel
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                imageGridAdapter.previewOnItemMove(from, to)
                return true
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

                // Day 31 修复：拖拽过程中已在本地完成所有交换（previewOnItemMove），
                // adapter 的数据顺序就是最终顺序。松手时：
                //   1. 先同步 lastImageList，让 render() 跳过 updateData（防止 notifyDataSetChanged 打断动画）
                //   2. 再通知 ViewModel 更新，保持状态一致
                val finalOrder = ArrayList(imageGridAdapter.getImages())
                lastImageList = finalOrder          // ← 防止 render() 回调覆盖 adapter 的正确顺序
                viewModel.sendIntent(PublishIntent.ReorderImages(finalOrder))
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
        viewModel.notifyInternalActivityLaunch()
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

    /**
     * Day 9: 从九宫格点击图片 → 启动全屏预览
     */
    private fun launchImagePreview(position: Int) {
        val uris = imageGridAdapter.getImages()
        if (uris.isEmpty()) return
        val safePos = position.coerceIn(0, uris.size - 1)

        val uriStrings = ArrayList<String>(uris.size)
        uris.forEach { uriStrings.add(it.toString()) }

        viewModel.notifyInternalActivityLaunch()
        val intent = Intent(this, ImagePreviewActivity::class.java).apply {
            putStringArrayListExtra(ImagePreviewActivity.EXTRA_URI_LIST, uriStrings)
            putExtra(ImagePreviewActivity.EXTRA_POSITION, safePos)
        }
        startActivity(intent)
        Log.d(tag, "🔍 [View] 启动大图预览: 位置=$safePos, 总数=${uris.size}")
    }

    // ========================================================================
    // MVI 三件套
    // ========================================================================

    private fun initIntentEmitters() {
        binding.ktg.doAfterTextChanged { text ->
            viewModel.sendIntent(PublishIntent.TextChanged(text.toString()))
        }

        // Day 22+：EditText 获得焦点 → 编辑器被触碰，切换按钮状态
        binding.ktg.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                viewModel.sendIntent(PublishIntent.EditorTouched)
            }
        }

        binding.btnPublish.setOnClickListener {
            viewModel.sendIntent(PublishIntent.ClickPublish)
        }

        // Day 9 新增：左上角"取消"按钮 → 弹出退出确认框
        binding.btnBack.setOnClickListener {
            showExitConfirmDialog()
        }

        binding.btnDraftBox.setOnClickListener {
            viewModel.notifyInternalActivityLaunch()
            val intent = Intent(this, DraftListActivity::class.java)
            draftListLauncher.launch(intent)
        }

        binding.barTopic.setOnClickListener {
            viewModel.sendIntent(PublishIntent.ShowTopicPicker)
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

        // Day 17+: 发布成功页按钮
        binding.btnSuccessContinue.setOnClickListener {
            dismissSuccessPage()
        }
        binding.btnSuccessBack.setOnClickListener {
            dismissSuccessPage()
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

        // ================================================================
        // 0. 发布成功页（最高优先级，覆盖一切）
        // ================================================================
        if (state.publishSuccess) {
            renderSuccessPage(state)
            return
        }

        // ================================================================
        // 0b. 草稿恢复弹框（已移除 — 改用草稿箱列表页 DraftListActivity）
        // ================================================================

        // ================================================================
        // 0c. 话题选择器 BottomSheet（状态驱动）
        // ================================================================
        if (state.showTopicPicker) {
            showTopicBottomSheet(state.hotTopics)
        } else {
            dismissTopicBottomSheet()
        }

        val loading = state.isLoading

        // ================================================================
        // 1. Loading 遮罩（Day 17 升级：水平进度条 + 步骤文字）
        // ================================================================
        binding.progressBarOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            // 使用 getString() + UploadStatus 结构体渲染本地化文字，
            // 避免直接拼接字符串（符合「Use resource strings with placeholders」要求）
            binding.tvUploadStatus.text = when (val status = state.uploadStatus) {
                is com.example.test_micrott.models.UploadStatus.Compressing ->
                    getString(R.string.wtt_upload_compress, status.current, status.total)
                is com.example.test_micrott.models.UploadStatus.Uploading ->
                    getString(R.string.wtt_upload_send, status.current, status.total)
                is com.example.test_micrott.models.UploadStatus.Publishing,
                is com.example.test_micrott.models.UploadStatus.Preparing ->
                    getString(R.string.wtt_status_publishing)
                else -> getString(R.string.wtt_status_publishing)
            }
            binding.pbUploadProgress.progress = state.uploadProgress
            binding.tvUploadPercent.text = getString(R.string.wtt_upload_percent, state.uploadProgress)
        }

        // ================================================================
        // 2. Loading 守卫：禁用所有可交互控件，防止发布中继续编辑
        // ================================================================
        binding.ktg.isEnabled = !loading
        binding.btnPublish.isEnabled = state.isPublishButtonEnabled && !loading
        binding.btnDraftBox.isEnabled = !loading
        binding.barTopic.isEnabled = !loading
        binding.barMention.isEnabled = !loading
        binding.barEmoji.isEnabled = !loading
        binding.barBold.isEnabled = !loading      // Day 16
        binding.barItalic.isEnabled = !loading    // Day 16
        binding.barColor.isEnabled = !loading     // Day 16

        val isFull = state.selectedImages.size >= 9
        binding.barPhoto.isEnabled = !loading && !isFull

        // ================================================================
        // 3. 发布按钮/草稿箱按钮可见性（三态切换）
        // ================================================================
        val hasContent = state.text.trim().isNotEmpty() || state.selectedImages.isNotEmpty()
        if (state.isEditorTouched || hasContent) {
            // 状态 2/3：显示发布按钮，隐藏草稿箱
            binding.btnPublish.visibility = View.VISIBLE
            binding.btnDraftBox.visibility = View.GONE
        } else {
            // 状态 1：显示草稿箱，隐藏发布按钮
            binding.btnPublish.visibility = View.GONE
            binding.btnDraftBox.visibility = View.VISIBLE
        }

        // ================================================================
        // 4. 发布按钮颜色
        // ================================================================
        binding.btnPublish.setBackgroundColor(
            if (state.isPublishButtonEnabled && !loading) "#F85149".toColorInt()
            else "#A8A8A8".toColorInt()
        )

        // ================================================================
        // 5. 底部照片按钮 alpha（满 9 张或 loading 时变灰）
        // ================================================================
        binding.barPhoto.alpha = if (isFull || loading) 0.35f else 1.0f

        // ================================================================
        // 6. 输入框文本：仅在外部变更（SavedState 恢复 / 发布重置）时回写
        //    正常打字时 EditText.text == state.text，此分支不触发，避免光标跳动
        // ================================================================
        if (binding.ktg.text.toString() != state.text) {
            isProgrammaticChange = true
            binding.ktg.setText(state.text)
            isProgrammaticChange = false
            reapplyProtectedSpans()
            reapplyFormattingSpans(state.formatSpanDescriptors)  // Day 16: 旋转恢复格式 Span
            binding.ktg.setSelection(state.text.length)
        }

        // ================================================================
        // 6b. 字数统计显示
        // ================================================================
        binding.tvCharCount.text = getString(
            R.string.wtt_char_count_format,
            state.charCount,
            state.maxCharLimit
        )
        if (state.isCharLimitExceeded) {
            binding.tvCharCount.setTextColor("#F85149".toColorInt())   // 红色警告
        } else {
            binding.tvCharCount.setTextColor("#999999".toColorInt())   // 正常灰色
        }

        // ================================================================
        // 7. 九宫格：仅图片列表内容变化时才 updateData，避免每帧打字触发
        //    notifyDataSetChanged，以及拖拽松手后 ViewModel 回写相同顺序
        //    导致 notifyDataSetChanged 打断 ItemTouchHelper 松手动画。
        //    用内容比较（==）而非引用比较（===）。
        // ================================================================
        if (state.selectedImages != lastImageList) {
            imageGridAdapter.updateData(state.selectedImages)
            lastImageList = state.selectedImages
        }
    }

    // ========================================================================
    // 发布成功页
    // ========================================================================

    /**
     * 渲染发布成功页，展示刚发布的内容摘要。
     * 此方法在 render() 入口被 state.publishSuccess 拦截调用，
     * 此时正常编辑 UI 全部隐藏，仅显示成功页。
     */
    private fun renderSuccessPage(state: PublishState) {
        // 隐藏正常编辑 UI
        binding.mai.visibility = View.GONE           // 顶部标题栏
        binding.kta.visibility = View.GONE           // 内容滚动区
        binding.formattingToolbarContainer.visibility = View.GONE
        binding.bottomToolbarContainer.visibility = View.GONE
        binding.progressBarOverlay.visibility = View.GONE

        // 显示成功页
        binding.successOverlay.visibility = View.VISIBLE

        // 发布文本摘要
        if (state.publishResultText.isNotBlank()) {
            binding.tvSuccessText.text = state.publishResultText
        } else {
            binding.tvSuccessText.text = getString(
                if (state.publishResultImageCount > 0) R.string.wtt_success_text_empty
                else R.string.wtt_success_text_empty
            )
        }

        // 图片数量
        if (state.publishResultImageCount > 0) {
            binding.tvSuccessImages.text = getString(
                R.string.wtt_success_images_fmt,
                state.publishResultImageCount
            )
            binding.tvSuccessImages.visibility = View.VISIBLE
        } else {
            binding.tvSuccessImages.text = getString(R.string.wtt_success_images_empty)
            binding.tvSuccessImages.visibility = View.VISIBLE
        }

        Log.d(tag, "✅ [View] 发布成功页已渲染")
    }

    /**
     * 用户点击"继续发布"或"返回"关闭成功页。
     * 通知 ViewModel 重置表单，恢复编辑模式。
     */
    private fun dismissSuccessPage() {
        binding.successOverlay.visibility = View.GONE
        // 恢复正常编辑 UI
        binding.mai.visibility = View.VISIBLE
        binding.kta.visibility = View.VISIBLE
        binding.bottomToolbarContainer.visibility = View.VISIBLE
        // 格式化工具栏根据文本内容决定显示
        viewModel.sendIntent(PublishIntent.DismissSuccess)
    }

    // ========================================================================
    // Day 22+：草稿恢复 — 已移至 DraftListActivity
    // ========================================================================

    // ========================================================================
    // Day 22+：生命周期回调 — 双层草稿机制
    // ========================================================================

    override fun onPause() {
        super.onPause()
        viewModel.onActivityPause()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onActivityResume()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onActivityStop()
    }

    /**
     * 系统返回键/手势 → 弹出退出确认框（有内容时）。
     * 迁移到 AndroidX OnBackPressedDispatcher，替代已废弃的 onBackPressed()。
     */
    private fun setupBackPressedDispatcher() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.hasContent()) {
                    showExitConfirmDialog()
                } else {
                    // 无内容 → 释放拦截，走系统默认返回
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * 退出确认弹窗：「保存」「不保存」「取消」三选一。
     *
     * - 保存：通知 ViewModel 存永久草稿 → finish()
     * - 不保存：通知 ViewModel 跳过防抖 → finish()
     * - 取消：关闭弹窗，留在当前页面
     */
    private fun showExitConfirmDialog() {
        if (!viewModel.hasContent()) {
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("是否保存草稿？")
            .setMessage("当前内容尚未发布，是否保存为草稿？")
            .setPositiveButton("保存") { _, _ ->
                viewModel.sendIntent(PublishIntent.ConfirmSaveAndExit)
                finish()
            }
            .setNegativeButton("不保存") { _, _ ->
                viewModel.sendIntent(PublishIntent.ConfirmDiscardAndExit)
                finish()
            }
            .setNeutralButton("取消", null)
            .show()
    }

    // ========================================================================
    // Day 21+：话题 BottomSheet 选择器
    // ========================================================================

    /** 当前展示的话题 BottomSheet 引用（用于 render 中关闭） */
    private var topicBottomSheet: BottomSheetDialog? = null

    /**
     * 展示话题选择器 BottomSheet。
     * 包含热门话题标签（蓝色边框圆角 chip）+ 底部自定义输入框。
     *
     * 从 render() 中由 showTopicPicker 状态驱动调用。
     */
    private fun showTopicBottomSheet(topics: List<TopicItem>) {
        if (topicBottomSheet?.isShowing == true) return // 已展示，不重复弹

        val bottomSheet = BottomSheetDialog(this)
        topicBottomSheet = bottomSheet

        // 构建内容布局
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(20), dp2px(16), dp2px(20), dp2px(24))
        }

        // 标题
        val titleView = TextView(this).apply {
            text = "热门话题"
            setTextColor("#1A1A1A".toColorInt())
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp2px(12))
        }
        container.addView(titleView)

        // 话题标签容器（采用 LinearLayout 嵌套实现流式布局，避免引入 FlexboxLayout 依赖）
        val chipsRow = buildTopicChipsRow(topics, bottomSheet)
        container.addView(chipsRow)

        // 分隔线
        val divider = View(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2px(1)
            )
            setBackgroundColor("#E5E5E5".toColorInt())
        }
        container.addView(divider)

        // 自定义输入区域
        val customLabel = TextView(this).apply {
            text = "或输入自定义话题"
            setTextColor("#999999".toColorInt())
            textSize = 13f
            setPadding(0, dp2px(12), 0, dp2px(8))
        }
        container.addView(customLabel)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val inputField = EditText(this).apply {
            hint = "输入话题名称"
            setHintTextColor("#CCCCCC".toColorInt())
            setTextColor("#1A1A1A".toColorInt())
            textSize = 14f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(8).toFloat()
                setStroke(dp2px(1), "#E5E5E5".toColorInt())
            }
            setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(inputField)

        val confirmBtn = TextView(this).apply {
            text = "插入"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp2px(16), dp2px(10), dp2px(16), dp2px(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(8).toFloat()
                setColor("#2A62FF".toColorInt())
            }
            val btnLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp2px(10), 0, 0, 0)
            }
            layoutParams = btnLp

            setOnClickListener {
                val customTopic = inputField.text.toString().trim()
                if (customTopic.isNotEmpty()) {
                    viewModel.sendIntent(PublishIntent.SelectTopic(customTopic))
                    bottomSheet.dismiss()
                }
            }
        }
        inputRow.addView(confirmBtn)
        container.addView(inputRow)

        bottomSheet.setContentView(container)
        bottomSheet.setOnDismissListener {
            topicBottomSheet = null
            viewModel.sendIntent(PublishIntent.HideTopicPicker)
        }
        bottomSheet.show()
    }

    /** 关闭话题 BottomSheet（render 中状态驱动） */
    private fun dismissTopicBottomSheet() {
        topicBottomSheet?.dismiss()
        topicBottomSheet = null
    }

    /** dp → px 工具方法 */
    private fun dp2px(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    /**
     * 构建话题标签流式布局（3 列）。
     * 使用嵌套 LinearLayout 手动分行，避免引入 Flexbox 依赖。
     */
    private fun buildTopicChipsRow(topics: List<TopicItem>, bottomSheet: BottomSheetDialog): LinearLayout {
        val chipHeight = dp2px(36)
        val chipPaddingH = dp2px(14)
        val chipPaddingV = dp2px(8)
        val chipMargin = dp2px(8)
        val maxRowWidth = dp2px(280) // BottomSheet 可用宽度近似

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        var currentRow: LinearLayout? = null
        var currentRowWidth = 0

        topics.forEach { topic ->
            val chip = TextView(this).apply {
                text = topic.displayText
                setTextColor("#2A62FF".toColorInt())
                textSize = 13f
                setPadding(chipPaddingH, chipPaddingV, chipPaddingH, chipPaddingV)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp2px(18).toFloat()
                    setStroke(dp2px(1), "#2A62FF".toColorInt())
                    setColor(Color.TRANSPARENT)
                }
                setOnClickListener {
                    viewModel.sendIntent(PublishIntent.SelectTopic(topic.name))
                    bottomSheet.dismiss()
                }

                // 估算 chip 宽度：文字宽度 + 左右内边距
                val paint = this.paint
                val textWidth = paint.measureText(topic.displayText).toInt()
                val chipWidth = textWidth + chipPaddingH * 2 + chipMargin
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    chipHeight
                )
                tag = chipWidth // 用 tag 临时存储宽度，供分行判断
            }

            val estimatedWidth = chip.tag as Int
            if (currentRow == null || currentRowWidth + estimatedWidth > maxRowWidth) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, 0, 0, chipMargin)
                }
                rootLayout.addView(currentRow)
                currentRowWidth = 0
            }

            val chipLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                chipHeight
            ).apply {
                setMargins(0, 0, chipMargin, 0)
            }
            currentRow.addView(chip, chipLp)
            currentRowWidth += estimatedWidth
        }

        return rootLayout
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

        isProgrammaticChange = true
        editable.replace(start, end, spannableStringBuilder)
        isProgrammaticChange = false
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

        isProgrammaticChange = true
        editable.replace(start, end, emoji)
        isProgrammaticChange = false
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
    @SuppressLint("ClickableViewAccessibility")
    private fun initFormattingToolbar() {
        binding.barBold.setOnClickListener { toggleBold() }
        binding.barItalic.setOnClickListener { toggleItalic() }
        binding.barColor.setOnClickListener { showColorPicker() }

        // 触摸 EditText 时更新按钮状态（光标位置变化）
        val touchListener = View.OnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                updateFormattingButtonStates(
                    binding.ktg.selectionStart,
                    binding.ktg.selectionEnd
                )
                view.performClick()
            }
            false
        }
        binding.ktg.setOnTouchListener(touchListener)

        // 有文本时显示工具栏，无文本时隐藏
        binding.ktg.doAfterTextChanged { text ->
            val hasText = !text.isNullOrBlank()
            if (hasText && !isFormattingToolbarVisible) {
                showFormattingToolbar()
            } else if (!hasText && isFormattingToolbarVisible) {
                hideFormattingToolbar()
            }
        }

        // Day 16 type-ahead：监听输入，自动应用待定格式
        binding.ktg.addTextChangedListener(TypeAheadTextWatcher())
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
     * 待定格式（type-ahead）优先级高于光标位置检测。
     */
    private fun updateFormattingButtonStates(selStart: Int, selEnd: Int) {
        val editable = binding.ktg.text ?: return

        // B 按钮：待定粗体 或 光标位置有粗体 Span
        val isBold = pendingBoldActive || run {
            val checkStart = if (selStart == selEnd) selStart else selStart
            val checkEnd = if (selStart == selEnd) {
                (selEnd + 1).coerceAtMost(editable.length)
            } else selEnd
            if (checkStart < 0 || checkStart >= editable.length) false
            else {
                editable.getSpans(checkStart, checkEnd, StyleSpan::class.java)
                    .any { it.style == Typeface.BOLD }
            }
        }

        // I 按钮：待定斜体 或 光标位置有斜体 Span
        val isItalic = pendingItalicActive || run {
            val checkStart = if (selStart == selEnd) selStart else selStart
            val checkEnd = if (selStart == selEnd) {
                (selEnd + 1).coerceAtMost(editable.length)
            } else selEnd
            if (checkStart < 0 || checkStart >= editable.length) false
            else {
                editable.getSpans(checkStart, checkEnd, StyleSpan::class.java)
                    .any { it.style == Typeface.ITALIC }
            }
        }

        binding.barBold.setTextColor(
            if (isBold) "#2A62FF".toColorInt() else "#555555".toColorInt()
        )
        binding.barItalic.setTextColor(
            if (isItalic) "#2A62FF".toColorInt() else "#555555".toColorInt()
        )

        // A 按钮：待定颜色 或 光标位置有颜色 Span（排除话题/提及蓝色）
        val topicMentionColor = "#2A62FF".toColorInt()
        val activeColor = pendingColor ?: run {
            val checkStart = if (selStart == selEnd) selStart else selStart
            val checkEnd = if (selStart == selEnd) {
                (selEnd + 1).coerceAtMost(editable.length)
            } else selEnd
            if (checkStart < 0 || checkStart >= editable.length) null
            else {
                editable.getSpans(checkStart, checkEnd, ForegroundColorSpan::class.java)
                    .firstOrNull { it.foregroundColor != topicMentionColor }
                    ?.foregroundColor
            }
        }
        binding.barColor.setTextColor(
            activeColor ?: "#555555".toColorInt()
        )
    }

    /**
     * 切换粗体格式。
     *
     * - 有选区：对选区文字应用/移除粗体（原有逻辑）
     * - 无选区：切换待定粗体状态，后续输入自动带粗体（类似 Word type-ahead）
     */
    private fun toggleBold() {
        val editable = binding.ktg.text ?: return
        val selStart = binding.ktg.selectionStart
        val selEnd = binding.ktg.selectionEnd

        // ================================================================
        // Type-Ahead 模式：无选区 → 切换待定格式
        // ================================================================
        if (selStart < 0 || selStart == selEnd) {
            pendingBoldActive = !pendingBoldActive
            updateFormattingButtonStates(selStart, selEnd)
            return
        }

        // ================================================================
        // 选区模式：对选中文字应用/移除粗体
        // ================================================================
        clearPendingFormats()

        val existingBoldSpans = editable.getSpans(selStart, selEnd, StyleSpan::class.java)
            .filter { it.style == Typeface.BOLD }

        if (existingBoldSpans.isNotEmpty()) {
            existingBoldSpans.forEach { span ->
                removeSpanFromSelection(editable, span, selStart, selEnd) {
                    StyleSpan(Typeface.BOLD)
                }
            }
        } else {
            editable.setSpan(
                StyleSpan(Typeface.BOLD), selStart, selEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        updateFormattingButtonStates(selStart, selEnd)
        saveCurrentFormattingState()
    }

    /**
     * 切换斜体格式。
     *
     * - 有选区：对选区文字应用/移除斜体
     * - 无选区：切换待定斜体状态，后续输入自动带斜体
     */
    private fun toggleItalic() {
        val editable = binding.ktg.text ?: return
        val selStart = binding.ktg.selectionStart
        val selEnd = binding.ktg.selectionEnd

        // Type-Ahead 模式
        if (selStart < 0 || selStart == selEnd) {
            pendingItalicActive = !pendingItalicActive
            updateFormattingButtonStates(selStart, selEnd)
            return
        }

        // 选区模式
        clearPendingFormats()

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
     * 弹出颜色选择器。
     *
     * - 有选区：选色后直接应用到选区文字
     * - 无选区：选色后激活待定颜色，后续输入自动带该颜色（类似 Word type-ahead）
     */
    private fun showColorPicker() {
        if (binding.ktg.text.isNullOrEmpty()) return
        val selStart = binding.ktg.selectionStart
        val selEnd = binding.ktg.selectionEnd

        val hasSelection = selStart >= 0 && selStart != selEnd

        ColorPickerPopup(this) { color ->
            if (hasSelection) {
                // 选区模式：直接应用颜色
                clearPendingFormats()
                applyTextColor(color, selStart, selEnd)
            } else {
                // Type-Ahead 模式：激活待定颜色
                pendingColor = color
                updateFormattingButtonStates(selStart, selEnd)
                Log.d(tag, "🎨 [View] 待定文字颜色已激活: #${Integer.toHexString(color)}")
            }
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
     * 处理 span 与选区 [selStart, selEnd) 的重叠：
     * - 完全在选区内 → 移除
     * - 选区在 span 内部 → 拆分为左右两段
     * - 左重叠 → 收缩 span 右端
     * - 右重叠 → 收缩 span 左端
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

    // ========================================================================
    // Day 16: Type-Ahead 格式化 — TextWatcher 自动应用待定格式到新输入文字
    // ========================================================================

    /**
     * 监听 EditText 文本变更，当有待定格式（pendingBold/pendingItalic/pendingColor）
     * 且用户正在输入新字符时，自动将待定 Span 应用到新输入的字符上。
     *
     * 类似 Word：先点 B 再打字 → 打出的是粗体。
     */
    private inner class TypeAheadTextWatcher : android.text.TextWatcher {
        private var insertStart = 0
        private var insertLen = 0

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (isProgrammaticChange) return
            // start: 变更起始位置（即光标/选区起点）
            // after: 将要新增的字符数（纯输入时 count=0, after>0）
            insertStart = start
            insertLen = after
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            // 不需要
        }

        override fun afterTextChanged(s: android.text.Editable?) {
            if (isProgrammaticChange) return
            if (s == null || insertLen <= 0) return
            if (!hasPendingFormat()) return

            val end = (insertStart + insertLen).coerceAtMost(s.length)
            if (insertStart >= end) return

            applyPendingSpansToRange(s, insertStart, end)

            // 插入完成后不清除待定格式（用户可能继续打字），
            // 但需要刷新按钮状态以反映当前光标位置
            updateFormattingButtonStates(
                binding.ktg.selectionStart,
                binding.ktg.selectionEnd
            )
        }
    }

    /** 是否有任何待定格式处于激活状态 */
    private fun hasPendingFormat(): Boolean {
        return pendingBoldActive || pendingItalicActive || pendingColor != null
    }

    /**
     * 将当前所有待定格式的 Span 应用到 Editable 的 [start, end) 范围。
     * 跳过话题/提及的 #2A62FF 保护色区域。
     */
    private fun applyPendingSpansToRange(editable: android.text.Editable, start: Int, end: Int) {
        if (pendingBoldActive) {
            // 先清除该范围内已有的粗体 Span（避免叠加）
            val existing = editable.getSpans(start, end, StyleSpan::class.java)
                .filter { it.style == Typeface.BOLD }
            existing.forEach { editable.removeSpan(it) }
            editable.setSpan(
                StyleSpan(Typeface.BOLD), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (pendingItalicActive) {
            val existing = editable.getSpans(start, end, StyleSpan::class.java)
                .filter { it.style == Typeface.ITALIC }
            existing.forEach { editable.removeSpan(it) }
            editable.setSpan(
                StyleSpan(Typeface.ITALIC), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        pendingColor?.let { color ->
            val topicMentionColor = "#2A62FF".toColorInt()
            // 清除该范围内已有的非保护色 Span
            val existing = editable.getSpans(start, end, ForegroundColorSpan::class.java)
                .filter { it.foregroundColor != topicMentionColor }
            existing.forEach { editable.removeSpan(it) }
            editable.setSpan(
                ForegroundColorSpan(color), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** 清除所有待定格式（用户显式选中文本应用格式时调用） */
    private fun clearPendingFormats() {
        pendingBoldActive = false
        pendingItalicActive = false
        pendingColor = null
    }
}
