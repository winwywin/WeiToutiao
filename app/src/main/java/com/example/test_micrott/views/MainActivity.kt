package com.example.test_micrott.views

// ==========================================
// 1. Android 系统与 Jetpack 官方核心依赖库导入区
// ==========================================

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ==========================================
// 2. 本地项目业务组件导入
// ==========================================
import com.example.test_micrott.R
import com.example.test_micrott.data.ImageCompressor
import com.example.test_micrott.databinding.ActivityMainBinding
import com.example.test_micrott.models.PublishIntent
import com.example.test_micrott.models.PublishState
import com.example.test_micrott.viewmodels.PublishViewModel

/**
 * 【提线木偶层 - MainActivity】
 * Day 5：MVC 沙盒 PhotoPicker + 话题插入 + 九宫格逻辑迁移至 MVI
 * Day 6：SavedStateHandle 状态恢复 + 话题 Span 自动重新着色
 * Day 7：空状态加号可见 + 动态相册上限 + 拖拽排序
 * Day 25+：架构拆分 — FormattingToolbarDelegate / SpanWatcher 合并为 RichEditText 自定义控件
 * Day 28：RichEditText 封装 — 所有富文本逻辑聚拢到单个自定义 View，MainActivity 从 ~690 行再精简
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: PublishViewModel by viewModels()
    private val tag = "MVI_FRAMEWORK"

    // ========================================================================
    // Day 28：RichEditText 封装
    // ========================================================================
    private lateinit var richEditText: RichEditText

    /** 话题选择器 BottomSheet */
    private lateinit var topicPickerSheet: TopicPickerSheet

    /** @提及用户选择器 */
    private lateinit var mentionPicker: MentionPickerHelper

    // ========================================================================
    // A9 自定义九宫格 ViewGroup
    // ========================================================================
    private lateinit var nineGridLayout: NineGridLayout

    private var lastImageList: List<Uri> = emptyList()

    // ========================================================================
    // Activity Result Launchers
    // ========================================================================

    private val pickMultipleMedia = registerForActivityResult(
        GalleryPickerContract()
    ) { resultUris ->
        if (!resultUris.isNullOrEmpty()) {
            Log.d(tag, "📷 [View] 自定义相册返回 ${resultUris.size} 张")
            viewModel.sendIntent(PublishIntent.Image.ImagesPicked(resultUris))
        } else {
            Log.d(tag, "用户取消了相册选择")
        }
    }

    private val draftListLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val draftId = result.data?.getLongExtra(
                DraftListActivity.EXTRA_RESTORED_DRAFT_ID, -1L
            ) ?: -1L
            if (draftId > 0) {
                Log.d(tag, "📋 [View] 草稿箱返回，恢复草稿 id=$draftId")
                viewModel.sendIntent(PublishIntent.Draft.RestoreDraft(draftId))
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d(tag, "📷 [View] 照片权限已授予，启动自定义相册")
            launchGalleryPicker()
        } else {
            Log.d(tag, "📷 [View] 用户拒绝照片权限")
            android.widget.Toast.makeText(this, "需要照片权限才能选择图片", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val photoPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    // ========================================================================
    // 生命周期
    // ========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initRichEdit()
        initDelegates()
        initRecyclerView()
        initIntentEmitters()
        observeUiState()
        setupBackPressedDispatcher()
        lifecycle.addObserver(viewModel)
    }

    // ========================================================================
    // Day 28：RichEditText 初始化 — 所有富文本逻辑聚拢到自定义 View
    // ========================================================================

    private fun initRichEdit() {
        richEditText = binding.ktg as RichEditText

        // 文本内容变化 → 通知 ViewModel
        richEditText.onTextContentChanged = { text ->
            viewModel.sendIntent(PublishIntent.Text.TextChanged(text))
        }

        // 格式化 Span 变化 → 通知 ViewModel（程序化变更时自动跳过）
        richEditText.onSpansChanged = { descriptors ->
            viewModel.sendIntent(PublishIntent.Text.SaveFormattingSpans(descriptors))
        }

        // 按钮状态回调 → 更新 B/I/A 按钮颜色
        richEditText.onButtonStatesChanged = { isBold, isItalic, activeColor ->
            binding.barBold.setTextColor(
                if (isBold) "#2A62FF".toColorInt() else "#555555".toColorInt()
            )
            binding.barItalic.setTextColor(
                if (isItalic) "#2A62FF".toColorInt() else "#555555".toColorInt()
            )
            binding.barColor.setTextColor(activeColor ?: "#555555".toColorInt())
        }
    }

    private fun initDelegates() {
        // ── 话题选择器 ──
        topicPickerSheet = TopicPickerSheet(
            this,
            onTopicPicked = { topicName ->
                richEditText.insertTopic(topicName)
                viewModel.sendIntent(PublishIntent.Text.SelectTopic(topicName))
            },
            onDismissed = {
                viewModel.sendIntent(PublishIntent.Internal.HideTopicPicker)
            }
        )

        // ── @提及选择器 ──
        mentionPicker = MentionPickerHelper(this) { userName ->
            richEditText.insertMention(userName)
        }
    }

    // ========================================================================
    // 初始化：九宫格
    // ========================================================================

    private fun initRecyclerView() {
        nineGridLayout = findViewById(R.id.grid_image_container)
        nineGridLayout.thumbnailLoader = NineGridLayout.ThumbnailLoader { uri, targetW, targetH, onLoaded ->
            lifecycleScope.launch(Dispatchers.IO) {
                val bitmap = ImageCompressor.decodeSampledBitmap(this@MainActivity, uri, targetW, targetH)
                withContext(Dispatchers.Main) { onLoaded(bitmap) }
            }
        }
        nineGridLayout.onAddClick = { tryPickPhotos() }
        nineGridLayout.onDeleteClick = { index -> viewModel.sendIntent(PublishIntent.Image.RemoveImage(index)) }
        nineGridLayout.onImageClick = { index -> launchImagePreview(index) }

        nineGridLayout.setImages(emptyList())

        nineGridLayout.onReorder = { reordered ->
            viewModel.sendIntent(PublishIntent.Image.ReorderImages(reordered))
        }
    }

    // ========================================================================
    // 初始化：照片选择
    // ========================================================================

    private fun tryPickPhotos() {
        if (checkSelfPermission(photoPermission) == PackageManager.PERMISSION_GRANTED) {
            launchGalleryPicker()
        } else {
            Log.d(tag, "📷 [View] 照片权限未授予，请求中...")
            requestPermissionLauncher.launch(photoPermission)
        }
    }

    private fun launchGalleryPicker() {
        val currentImages = nineGridLayout.getImages()
        val maxSlots = 9 - currentImages.size
        if (maxSlots <= 0) {
            Log.d(tag, "📷 [View] 已达 9 张上限，阻止相册启动")
            return
        }
        val preSelectedIds = extractMediaIds(currentImages)
        Log.d(tag, "📷 [View] 启动自定义相册，剩余名额 $maxSlots，已选 ${preSelectedIds.size} 张")
        viewModel.sendIntent(PublishIntent.Internal.LaunchInternalActivity)
        pickMultipleMedia.launch(PickConfig(maxSelectable = maxSlots, preSelectedIds = preSelectedIds))
    }

    private fun extractMediaIds(uris: List<Uri>): List<Long> {
        return uris.mapNotNull { uri ->
            try {
                uri.lastPathSegment?.toLong()
            } catch (_: NumberFormatException) {
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

    private fun launchImagePreview(position: Int) {
        val uris = nineGridLayout.getImages()
        if (uris.isEmpty()) return
        val safePos = position.coerceIn(0, uris.size - 1)

        val uriStrings = ArrayList<String>(uris.size)
        uris.forEach { uriStrings.add(it.toString()) }

        viewModel.sendIntent(PublishIntent.Internal.LaunchInternalActivity)
        val intent = Intent(this, ImagePreviewActivity::class.java).apply {
            putStringArrayListExtra(ImagePreviewActivity.EXTRA_URI_LIST, uriStrings)
            putExtra(ImagePreviewActivity.EXTRA_POSITION, safePos)
        }
        startActivity(intent)
        Log.d(tag, "🔍 [View] 启动大图预览: 位置=$safePos, 总数=${uris.size}")
    }

    // ========================================================================
    // MVI 三件套：Intent 发射器
    // ========================================================================

    private fun initIntentEmitters() {
        // 焦点变化：控制格式化工具栏显隐 + 发布按钮切换
        richEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                viewModel.sendIntent(PublishIntent.Text.EditorTouched)
                binding.formattingToolbarContainer.visibility = View.VISIBLE
                richEditText.notifyButtonStates()
            } else {
                if (richEditText.text.isNullOrBlank()) {
                    binding.formattingToolbarContainer.visibility = View.GONE
                }
            }
        }

        // B / I 格式化按钮 → 委托给 RichEditText
        binding.barBold.setOnClickListener { richEditText.toggleBold() }
        binding.barItalic.setOnClickListener { richEditText.toggleItalic() }

        // A 颜色选择器
        binding.barColor.setOnClickListener {
            ColorPickerPopup(this) { color ->
                richEditText.applyColor(color)
            }.show(binding.barColor)
        }

        binding.btnPublish.setOnClickListener {
            viewModel.sendIntent(PublishIntent.Publish.ClickPublish)
        }

        binding.btnBack.setOnClickListener {
            showExitConfirmDialog()
        }

        binding.btnDraftBox.setOnClickListener {
            viewModel.sendIntent(PublishIntent.Internal.LaunchInternalActivity)
            val intent = Intent(this, DraftListActivity::class.java)
            draftListLauncher.launch(intent)
        }

        binding.barTopic.setOnClickListener {
            viewModel.sendIntent(PublishIntent.Internal.ShowTopicPicker)
        }

        binding.barPhoto.setOnClickListener {
            tryPickPhotos()
        }

        binding.barMention.setOnClickListener {
            mentionPicker.show()
        }

        binding.barEmoji.setOnClickListener {
            EmojiPickerDialog { emoji -> richEditText.insertEmoji(emoji) }
                .show(supportFragmentManager, "EmojiPicker")
        }

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
        lifecycleScope.launch {
            viewModel.showTopicPicker.collect { show ->
                if (show) {
                    topicPickerSheet.show(viewModel.state.value.hotTopics)
                } else {
                    topicPickerSheet.dismiss()
                }
            }
        }
    }

    // ========================================================================
    // 渲染引擎
    // ========================================================================

    private fun render(state: PublishState) {
        Log.d(tag, "📺 [View] 收到新 State 账本，开始渲染: isLoading=${state.isLoading}, textLen=${state.text.length}, images=${state.selectedImages.size}")

        // -1. 退出信号
        if (state.shouldFinish) {
            finish()
            return
        }

        // 0. 发布成功页（最高优先级）
        if (state.publishSuccess) {
            renderSuccessPage(state)
            return
        }

        val loading = state.isLoading

        // 1. Loading 遮罩
        binding.progressBarOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
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

        // 2. Loading 守卫
        richEditText.isEnabled = !loading
        binding.btnPublish.isEnabled = state.isPublishButtonEnabled && !loading
        binding.btnDraftBox.isEnabled = !loading
        binding.barTopic.isEnabled = !loading
        binding.barMention.isEnabled = !loading
        binding.barEmoji.isEnabled = !loading
        binding.barBold.isEnabled = !loading
        binding.barItalic.isEnabled = !loading
        binding.barColor.isEnabled = !loading

        val isFull = state.selectedImages.size >= 9
        binding.barPhoto.isEnabled = !loading && !isFull

        // 3. 发布按钮/草稿箱按钮可见性
        val hasContent = state.text.trim().isNotEmpty() || state.selectedImages.isNotEmpty()
        if (state.isEditorTouched || hasContent) {
            binding.btnPublish.visibility = View.VISIBLE
            binding.btnDraftBox.visibility = View.GONE
        } else {
            binding.btnPublish.visibility = View.GONE
            binding.btnDraftBox.visibility = View.VISIBLE
        }

        // 4. 发布按钮颜色
        binding.btnPublish.setBackgroundColor(
            if (state.isPublishButtonEnabled && !loading) "#F85149".toColorInt()
            else "#A8A8A8".toColorInt()
        )

        // 5. 底部照片按钮 alpha
        binding.barPhoto.alpha = if (isFull || loading) 0.35f else 1.0f

        // 6. 输入框文本：仅在外部变更时回写，正常打字不触发
        if (richEditText.text.toString() != state.text) {
            richEditText.setTextProgrammatic(state.text)
            richEditText.reapplyProtectedSpans()
            richEditText.reapplyFormattingSpans(state.formatSpanDescriptors)
            richEditText.notifySpansChanged()
            richEditText.setSelection(state.text.length)
        }

        // 6b. 字数统计
        binding.tvCharCount.text = getString(
            R.string.wtt_char_count_format,
            state.charCount,
            state.maxCharLimit
        )
        if (state.isCharLimitExceeded) {
            binding.tvCharCount.setTextColor("#F85149".toColorInt())
        } else {
            binding.tvCharCount.setTextColor("#999999".toColorInt())
        }

        // 7. 九宫格
        if (state.selectedImages != lastImageList) {
            nineGridLayout.setImages(state.selectedImages, maxCount = 9)
            lastImageList = state.selectedImages
        }
    }

    // ========================================================================
    // 发布成功页
    // ========================================================================

    private fun renderSuccessPage(state: PublishState) {
        binding.mai.visibility = View.GONE
        binding.kta.visibility = View.GONE
        binding.formattingToolbarContainer.visibility = View.GONE
        binding.bottomToolbarContainer.visibility = View.GONE
        binding.progressBarOverlay.visibility = View.GONE

        binding.successOverlay.visibility = View.VISIBLE

        binding.tvSuccessText.text = state.publishResultText.ifBlank {
            getString(R.string.wtt_success_text_empty)
        }

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

    private fun dismissSuccessPage() {
        binding.successOverlay.visibility = View.GONE
        binding.mai.visibility = View.VISIBLE
        binding.kta.visibility = View.VISIBLE
        binding.bottomToolbarContainer.visibility = View.VISIBLE
        viewModel.sendIntent(PublishIntent.Publish.DismissSuccess)
    }

    // ========================================================================
    // 返回键 / 退出确认
    // ========================================================================

    private fun setupBackPressedDispatcher() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.state.value.hasContent) {
                    showExitConfirmDialog()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showExitConfirmDialog() {
        if (!viewModel.state.value.hasContent) {
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("是否保存草稿？")
            .setMessage("当前内容尚未发布，是否保存为草稿？")
            .setPositiveButton("保存") { _, _ ->
                viewModel.sendIntent(PublishIntent.Draft.ConfirmSaveAndExit)
            }
            .setNegativeButton("不保存") { _, _ ->
                viewModel.sendIntent(PublishIntent.Draft.ConfirmDiscardAndExit)
                finish()
            }
            .setNeutralButton("取消", null)
            .show()
    }
}
