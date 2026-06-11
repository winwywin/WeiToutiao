package com.example.test_micrott.viewmodels

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateHandle
import com.example.test_micrott.models.PublishIntent
import com.example.test_micrott.models.PublishState
import com.example.test_micrott.models.SpanDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import com.example.test_micrott.models.UploadStatus
import com.example.test_micrott.data.ImageCompressor
import com.example.test_micrott.di.App
import com.example.test_micrott.repository.DraftRepository
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job

/**
 * 核心调度大脑 - PublishViewModel (MVI-MVVM 融合架构核心)
 * 职责：
 * 1. 唯一持有并驱动全场唯一的 PublishState。
 * 2. 接收来自 View 层的单向 Intent 指令，进行闭环业务演算。
 *
 * Day 6 升级：接入 SavedStateHandle，进程销毁后状态100%恢复。
 * Day 17 升级：继承 AndroidViewModel（需要 Context 获取 cacheDir 压缩图片）。
 *             handleClickPublish 改为分步发布：压缩 → 上传 → 完成。
 */
class PublishViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application), DefaultLifecycleObserver {

    companion object {
        // SavedStateHandle 存储键
        private const val KEY_TEXT = "publish_text"
        private const val KEY_IMAGES = "publish_images"
        private const val KEY_LOADING = "publish_loading"
        private const val KEY_BUTTON_ENABLED = "publish_button_enabled"
        private const val KEY_FORMAT_SPANS = "publish_format_spans"
        private const val KEY_EDITOR_TOUCHED = "publish_editor_touched"
    }

    // 🛡️ 防护隔离防线：对内私有可变，严禁外部直接 `.value = ...` 篡改账本
    // Day 6 升级：初始化时从 SavedStateHandle 恢复（进程销毁后重建场景）
    private val _state = MutableStateFlow(restoreState())

    // 📺 对外只读暴露：View 层（Activity）只能单向 collect 监听这个流
    val state: StateFlow<PublishState> = _state.asStateFlow()

    // 话题选择器显隐：独立于主状态，SavedStateHandle 状态持久化
    val showTopicPicker: StateFlow<Boolean> =
        savedStateHandle.getStateFlow("showTopicPicker", false)

    private val tag = "MVI_FRAMEWORK"

    // Day 22+：草稿管理器（通过 DI 容器获取，避免 ViewModel 直接 new Data 层实现）
    private val draftManager: DraftRepository = (getApplication<Application>() as App).container.draftRepository

    /**
     * 退出行为的显式标记。
     * 用户在退出弹窗中选择了保存/不保存后设置，onPause 据此决定跳过防抖临时保存。
     */
    private enum class ExitAction { Save, Discard }
    private var explicitExitAction: ExitAction? = null

    /**
     * 标记：即将启动 App 内部 Activity（草稿箱/大图预览），
     * onStop 检测到此标记时不将临时草稿升级为永久（用户还在 App 里）。
     */
    private var launchingInternalActivity = false

    /**
     * 防抖保存 Job，用于在 onResume 时等待 onPause 的保存协程完成后再删除，
     * 消除 saveTemporaryDraft 与 deleteAllTemporaryDrafts 的竞态条件。
     */
    private var saveJob: Job? = null

    /**
     * 当前编辑器内容来源的草稿 id。
     * 从草稿箱恢复后设为该草稿 id，退出保存时传给 saveDraft 做 UPDATE 而非 INSERT，
     * 避免草稿箱中出现重复记录。全新编写时为 null。
     */
    private var restoredDraftId: Long? = null

    init {
        // 不再在 init 检查草稿 — 用户通过标题栏「草稿箱」按钮主动进入草稿列表页
    }

    // ========================================================================
    // Day 6 新增：SavedStateHandle 状态恢复
    // ========================================================================

    /**
     * 从 SavedStateHandle 恢复上次的 UI 状态。
     */
    private fun restoreState(): PublishState {
        val savedText = savedStateHandle.get<String>(KEY_TEXT) ?: ""
        val savedImages = savedStateHandle.get<ArrayList<Uri>>(KEY_IMAGES) ?: emptyList()
        val savedLoading = savedStateHandle.get<Boolean>(KEY_LOADING) ?: false
        val savedButtonEnabled = savedStateHandle.get<Boolean>(KEY_BUTTON_ENABLED) ?: false
        val savedFormatSpans = SpanDescriptor.deserializeList(
            savedStateHandle.get<ArrayList<String>>(KEY_FORMAT_SPANS)
        )
        val savedEditorTouched = savedStateHandle.get<Boolean>(KEY_EDITOR_TOUCHED) ?: false

        if (savedText.isNotEmpty() || savedImages.isNotEmpty()) {
            Log.d(tag, "🔄 [ViewModel] SavedStateHandle 恢复 -> text=${savedText.length}字, images=${savedImages.size}张")
        }

        return PublishState(
            text = savedText,
            selectedImages = savedImages,
            isLoading = savedLoading,
            isPublishButtonEnabled = savedButtonEnabled,
            formatSpanDescriptors = savedFormatSpans,
            isEditorTouched = savedEditorTouched,
        )
    }

    /**
     * 每次状态变更后，将关键字段同步写入 SavedStateHandle。
     */
    private fun persistState(state: PublishState) {
        savedStateHandle[KEY_TEXT] = state.text
        savedStateHandle[KEY_IMAGES] = ArrayList(state.selectedImages)
        savedStateHandle[KEY_LOADING] = state.isLoading
        savedStateHandle[KEY_BUTTON_ENABLED] = state.isPublishButtonEnabled
        savedStateHandle[KEY_FORMAT_SPANS] = SpanDescriptor.serializeList(state.formatSpanDescriptors)
        savedStateHandle[KEY_EDITOR_TOUCHED] = state.isEditorTouched
    }

    // ========================================================================
    // MVI 入口
    // ========================================================================

    /**
     * View 层向 ViewModel 打小报告的唯一物理入口
     */
    fun sendIntent(intent: PublishIntent) {
        Log.d(tag, "🧠 [ViewModel] 成功拦截到用户意图: $intent")

        // 利用 sealed class 的完备性强制分支检查，漏写任何一个意图直接编译报错
        when (intent) {
            // Text
            is PublishIntent.Text.TextChanged -> handleTextChanged(intent.text)
            is PublishIntent.Text.InsertTopic -> handleInsertTopic(intent.topicText)
            is PublishIntent.Text.InsertMention -> handleInsertMention(intent.mentionText)
            is PublishIntent.Text.SaveFormattingSpans -> handleSaveFormatting(intent.descriptors)
            is PublishIntent.Text.SelectTopic -> handleSelectTopic(intent.topicName)
            is PublishIntent.Text.EditorTouched -> handleEditorTouched()

            // Image
            is PublishIntent.Image.ImagesPicked -> handleImagesPicked(intent.uris)
            is PublishIntent.Image.RemoveImage -> handleRemoveImage(intent.index)
            is PublishIntent.Image.ReorderImages -> handleReorderImages(intent.uris)

            // Publish
            is PublishIntent.Publish.ClickPublish -> handleClickPublish()
            is PublishIntent.Publish.DismissSuccess -> handleDismissSuccess()

            // Draft
            is PublishIntent.Draft.OpenDraftBox -> handleOpenDraftBox()
            is PublishIntent.Draft.RestoreDraft -> handleRestoreDraft(intent.id)
            is PublishIntent.Draft.ConfirmSaveAndExit -> handleConfirmSaveAndExit()
            is PublishIntent.Draft.ConfirmDiscardAndExit -> handleConfirmDiscardAndExit()

            // Internal
            is PublishIntent.Internal.LaunchInternalActivity -> handleLaunchInternalActivity()
            is PublishIntent.Internal.ShowTopicPicker -> handleShowTopicPicker()
            is PublishIntent.Internal.HideTopicPicker -> handleHideTopicPicker()
        }
    }

    // ========================================================================
    // Intent 处理器
    // ========================================================================

    /**
     * 处理用户打字意图
     *
     * Day 8 优化：【修复打字卡顿】TextChanged 不再调用 persistState。
     * 每键写入 SavedStateHandle（Bundle 序列化）是高频打字的性能杀手。
     */
    private fun handleTextChanged(newText: String) {
        if (_state.value.text == newText) return

        val charCount = newText.length
        val isExceeded = charCount > PublishState.MAX_CHAR_LIMIT
        // 超限时不启用发布按钮（即使有图片）
        val hasContent = !isExceeded && (newText.trim().isNotEmpty() || _state.value.selectedImages.isNotEmpty())
        val isButtonEnabled = hasContent

        val newState = _state.value.copy(
            text = newText,
            isPublishButtonEnabled = isButtonEnabled,
            charCount = charCount,
            isCharLimitExceeded = isExceeded,
            isEditorTouched = true,
        )
        _state.value = newState

        Log.d(tag, "📺 [ViewModel] 状态增量演算完成 [TextChanged] -> charCount=$charCount, exceeded=$isExceeded")
    }

    /**
     * 处理照片选择器带回图片的意图
     *
     * Day 11 升级：自定义相册返回的是完整选中列表（含旧图+新图，可能已取消部分旧图）。
     * 因此直接替换整个列表，不再做追加+去重逻辑。
     */
    private fun handleImagesPicked(uris: List<Uri>) {
        // 微信/头条硬约束：最多支持 9 张图
        val truncated = uris.size - 9
        val safetyImages = if (uris.size > 9) {
            Log.w(tag, "⚠️ [ViewModel] 超出 9 张上限，已截断最后 $truncated 张")
            uris.subList(0, 9)
        } else {
            uris
        }

        if (safetyImages == _state.value.selectedImages) return

        val isButtonEnabled = _state.value.text.trim().isNotEmpty() || safetyImages.isNotEmpty()

        val newState = _state.value.copy(
            selectedImages = safetyImages,
            isPublishButtonEnabled = isButtonEnabled,
            isEditorTouched = true,
        )
        _state.value = newState
        persistState(newState)

        Log.d(tag, "📺 [ViewModel] 状态增量演算完成 [ImagesPicked] -> 当前共 ${safetyImages.size} 张")
    }

    /**
     * 处理九宫格点击小红叉删图意图
     */
    private fun handleRemoveImage(index: Int) {
        val currentImages = _state.value.selectedImages.toMutableList()
        if (index in currentImages.indices) {
            currentImages.removeAt(index)

            val isButtonEnabled = _state.value.text.trim().isNotEmpty() || currentImages.isNotEmpty()

            val newState = _state.value.copy(
                selectedImages = currentImages,
                isPublishButtonEnabled = isButtonEnabled,
            )
            _state.value = newState
            persistState(newState)

            Log.d(tag, "📺 [ViewModel] 状态增量演算完成 [RemoveImage] -> 删除了索引 $index 的图片")
        }
    }

    /**
     * 处理点击发布按钮意图
     *
     * Day 17 重构：分步发布流程
     *   Step 1 — 压缩：逐张图片调用 ImageCompressor 解码 + JPEG 压缩，
     *            解码目标尺寸 1080px（长边），JPEG 质量 [DraftManager.THUMBNAIL_JPEG_QUALITY]，
     *            进度推进 0→50%，输出 UploadStatus.Compressing(current, total)
     *   Step 2 — 上传：模拟逐张上传（delay 600ms/张），
     *            进度推进 50→100%，输出 UploadStatus.Uploading(current, total)
     *   Step 3 — 完成：重置 State，清理临时文件
     *
     * 注意：UploadStatus 不含字符串，由 View 层用 getString(R.string.xxx, args) 渲染，
     * 满足「不在 ViewModel 里拼接 UI 字符串」的最佳实践。
     * 临时压缩文件存放于 cacheDir/compress/，完成后统一清理。
     */
    private fun handleClickPublish() {
        if (_state.value.isLoading) {
            Log.d(tag, "⛔ [ViewModel] 发布中，忽略重复点击")
            return
        }

        val images = _state.value.selectedImages
        val totalImages = images.size

        val loadingState = _state.value.copy(
            isLoading = true,
            isPublishButtonEnabled = false,
            uploadProgress = 0,
            uploadStatus = UploadStatus.Preparing,
        )
        _state.value = loadingState
        persistState(loadingState)

        Log.d(tag, "📺 [ViewModel] 状态增量演算完成 [ClickPublish] -> 进入 Loading 发布中状态，共 $totalImages 张图片")

        viewModelScope.launch {
            // ================================================================
            // Step 1: 图片压缩（IO 密集型，派发到 IO 线程池）
            // ================================================================
            val compressedFiles = mutableListOf<File>()
            val ctx = getApplication<Application>()
            val compressDir = File(ctx.cacheDir, "compress").also { it.mkdirs() }

            if (totalImages > 0) {
                images.forEachIndexed { index, uri ->
                    // 更新进度：压缩阶段占 0→50%
                    val compressProgress = ((index.toFloat() / totalImages) * 50).toInt()
                    _state.value = _state.value.copy(
                        uploadProgress = compressProgress,
                        uploadStatus = UploadStatus.Compressing(
                            current = index + 1,
                            total = totalImages
                        )
                    )

                    val copiedFile = withContext(Dispatchers.IO) {
                        try {
                            // 解码下采样 Bitmap（长边 ≤ 1080px）+ JPEG 压缩输出
                            val bitmap = ImageCompressor.decodeSampledBitmap(ctx, uri, 1080, 1080)
                            if (bitmap != null) {
                                val outFile = File(compressDir, "img_${System.currentTimeMillis()}_$index.jpg")
                                ImageCompressor.compressToFile(bitmap, outFile)
                                bitmap.recycle()

                                // 记录原始 vs 压缩后大小，用于性能对比
                                val originalSize = try {
                                    ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                                } catch (_: Exception) { -1L }
                                val compressedSize = outFile.length()
                                val ratio = if (originalSize > 0) originalSize / compressedSize else -1L
                                Log.i(tag, "📐 [ViewModel] 图片压缩 ${index + 1}/$totalImages: " +
                                    "原始=${if (originalSize > 0) "${originalSize / 1024}KB" else "?"} → " +
                                    "压缩=${compressedSize / 1024}KB (${ratio}x)")

                                if (outFile.exists() && outFile.length() > 0) outFile else null
                            } else {
                                Log.w(tag, "⚠️ [ViewModel] 图片解码失败 index=$index")
                                null
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "⚠️ [ViewModel] 图片压缩失败 index=$index: ${e.message}")
                            null
                        }
                    }
                    copiedFile?.let { compressedFiles.add(it) }
                    Log.d(tag, "📋 [ViewModel] 图片压缩完成 ${index + 1}/$totalImages，文件=${copiedFile?.name}，大小=${copiedFile?.length()?.div(1024)}KB")
                }
            }

            // ================================================================
            // Step 2: 模拟上传（每张 600ms，占进度 50→100%）
            // ================================================================
            val filesToUpload = if (compressedFiles.isNotEmpty()) compressedFiles else
                List(maxOf(totalImages, 1)) { null } // 无图片时至少跑一次模拟

            filesToUpload.forEachIndexed { index, file ->
                val uploadProgress = 50 + ((index.toFloat() / filesToUpload.size) * 50).toInt()
                _state.value = _state.value.copy(
                    uploadProgress = uploadProgress,
                    uploadStatus = if (totalImages > 0)
                        UploadStatus.Uploading(current = index + 1, total = filesToUpload.size)
                    else
                        UploadStatus.Publishing
                )

                delay(600) // 模拟单张上传耗时
                Log.d(tag, "📤 [ViewModel] 上传完成 ${index + 1}/${filesToUpload.size}，文件=${file?.name ?: "（无图）"}")
            }

            // ================================================================
            // Step 3: 清理临时文件 + 进入发布成功状态
            // ================================================================
            withContext(Dispatchers.IO) {
                compressedFiles.forEach { it.delete() }
                Log.d(tag, "🗑️ [ViewModel] 清理临时压缩文件 ${compressedFiles.size} 个")
            }

            // 保存发布结果（供成功页展示），然后切换到成功状态
            val publishText = _state.value.text
            val publishImageCount = _state.value.selectedImages.size

            val successState = PublishState(
                publishSuccess = true,
                publishResultText = publishText,
                publishResultImageCount = publishImageCount,
            )
            _state.value = successState

            Log.d(tag, "✅ [ViewModel] publish done: textLen=${publishText.length}, images=$publishImageCount")
        }
    }

    /**
     * 用户关闭发布成功页 → 重置为编辑模式（空表单）。
     * 如果是从草稿恢复后发布的，删除原草稿（已发布，不再需要）。
     */
    private fun handleDismissSuccess() {
        val draftIdToDelete = restoredDraftId
        restoredDraftId = null

        val resetState = PublishState()
        _state.value = resetState
        persistState(resetState)

        // 发布成功后删除原草稿（已消费，避免草稿箱残留重复项）
        if (draftIdToDelete != null) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    draftManager.deleteDraft(draftIdToDelete)
                }
                Log.d(tag, "🗑️ [ViewModel] 已发布，删除原草稿 id=$draftIdToDelete")
            }
        }

        Log.d(tag, "📝 [ViewModel] 成功页已关闭，表单已重置")
    }

    /**
     * 处理插入话题标签意图。
     *
     * View 层 insertTopicIntoEditor() 已直接操作 Editable（保留已有富文本格式），
     * doAfterTextChanged 在 replace() 期间同步触发 TextChanged → state.text 已精确。
     *
     * 此处仅做字数/按钮状态更新 + 持久化，**不修改 state.text**。
     * 旧代码的"追加估算"假设话题总是插在末尾，Day 24 改为任意位置插入后不再成立。
     */
    private fun handleInsertTopic(@Suppress("UNUSED_PARAMETER") topicText: String) {
        val charCount = _state.value.text.length
        val isExceeded = charCount > PublishState.MAX_CHAR_LIMIT

        val newState = _state.value.copy(
            isPublishButtonEnabled = !isExceeded,
            charCount = charCount,
            isCharLimitExceeded = isExceeded,
        )
        _state.value = newState
        persistState(newState)
        Log.d(tag, "📺 [ViewModel] InsertTopic → charCount=$charCount (文本由 TextChanged 精确同步)")
    }

    /**
     * 处理插入 @提及 意图。
     *
     * View 层 insertMentionIntoEditor() 已直接操作 Editable，
     * doAfterTextChanged 同步触发 TextChanged → state.text 已精确。
     *
     * 此处仅做字数/按钮状态更新 + 持久化，**不修改 state.text**。
     */
    private fun handleInsertMention(@Suppress("UNUSED_PARAMETER") mentionText: String) {
        val charCount = _state.value.text.length
        val isExceeded = charCount > PublishState.MAX_CHAR_LIMIT

        val newState = _state.value.copy(
            isPublishButtonEnabled = !isExceeded,
            charCount = charCount,
            isCharLimitExceeded = isExceeded,
        )
        _state.value = newState
        persistState(newState)
        Log.d(tag, "📺 [ViewModel] InsertMention → charCount=$charCount (文本由 TextChanged 精确同步)")
    }

    /**
     * 处理保存格式化 Span 描述符意图
     *
     * 格式化操作是纯 View 层行为，此处仅接收 Span 描述符并持久化到 SavedStateHandle。
     */
    private fun handleSaveFormatting(descriptors: List<SpanDescriptor>) {
        val newState = _state.value.copy(formatSpanDescriptors = descriptors)
        _state.value = newState
        persistState(newState)
        Log.d(tag, "🎨 [ViewModel] 格式化 Span 已保存: ${descriptors.size} 个")
    }

    /**
     * 拖拽松手后提交完整最终顺序（替代已废弃的逐帧 MoveImage）。
     * 由 NineGridLayout.onReorder 回传完整列表，原子提交避免双写。
     */
    private fun handleReorderImages(uris: List<Uri>) {
        if (uris.size != _state.value.selectedImages.size) {
            Log.w(tag, "⚠️ [ReorderImages] 数量不匹配，忽略")
            return
        }
        val newState = _state.value.copy(selectedImages = uris)
        _state.value = newState
        persistState(newState)
        Log.d(tag, "📺 [ViewModel] 状态增量演算完成 [ReorderImages] → ${uris.size} 张")
    }

    // ========================================================================
    // Day 22+：双层草稿机制（主动保存 + 防抖保存）
    // ========================================================================

    /**
     * 编辑器被触碰（选图/点输入框/打字）→ 切换标题栏按钮状态。
     */
    private fun handleEditorTouched() {
        if (_state.value.isEditorTouched) return
        _state.value = _state.value.copy(isEditorTouched = true)
        Log.d(tag, "✋ [ViewModel] 编辑器被触碰")
    }

    /**
     * 用户点击草稿箱按钮 → 设置内部跳转标记，通知 View 层跳转。
     *
     * 草稿保存由 onPause 统一负责（始终保存），此处仅设置标记防止 onStop 误升级。
     */
    private fun handleOpenDraftBox() {
        launchingInternalActivity = true
        Log.d(tag, "📋 [ViewModel] 草稿箱按钮 → 内部跳转标记已设置（保存由 onPause 负责）")
    }

    /**
     * 从草稿箱恢复指定草稿到编辑器。
     */
    private fun handleRestoreDraft(draftId: Long) {
        viewModelScope.launch {
            val draft = draftManager.getDraft(draftId)
            if (draft == null) {
                Log.w(tag, "⚠️ [ViewModel] 草稿 id=$draftId 加载失败")
                return@launch
            }

            val isButtonEnabled = draft.text.trim().isNotEmpty() || draft.images.isNotEmpty()
            val charCount = draft.text.length
            val isExceeded = charCount > PublishState.MAX_CHAR_LIMIT

            val restoredState = _state.value.copy(
                text = draft.text,
                selectedImages = draft.images,
                formatSpanDescriptors = draft.formatSpans,
                isPublishButtonEnabled = isButtonEnabled && !isExceeded,
                isEditorTouched = true,
                charCount = charCount,
                isCharLimitExceeded = isExceeded,
            )
            _state.value = restoredState
            persistState(restoredState)
            restoredDraftId = draftId

            Log.d(tag, "📋 [ViewModel] 草稿 id=$draftId 已恢复: text=${draft.text.length}字, images=${draft.images.size}张")
        }
    }

    /**
     * 退出弹窗 — 用户点击「保存」。
     * 异步存永久草稿，保存完成后才设置 explicitExitAction 和 shouldFinish，
     * 确保 finish() 在草稿落盘之后执行，避免 viewModelScope 被取消导致保存中断。
     */
    private fun handleConfirmSaveAndExit() {
        val s = _state.value
        if (s.text.isBlank() && s.selectedImages.isEmpty()) {
            explicitExitAction = ExitAction.Discard // 空内容等同不保存
            return
        }
        viewModelScope.launch {
            val id = draftManager.saveDraft(
                s.text, s.selectedImages, s.formatSpanDescriptors,
                updateDraftId = restoredDraftId
            )
            val action = if (restoredDraftId != null) "更新" else "新建"
            Log.d(tag, "💾 [ViewModel] 用户主动保存草稿 id=$id ($action)")
            restoredDraftId = null
            // 保存落盘完成后，才设置退出标记 + 通知 View 层 finish()
            explicitExitAction = ExitAction.Save
            _state.value = _state.value.copy(shouldFinish = true)
        }
    }

    /**
     * 退出弹窗 — 用户点击「不保存」。
     * 设置标记，onPause 跳过防抖临时保存，直接退出。
     */
    private fun handleConfirmDiscardAndExit() {
        explicitExitAction = ExitAction.Discard
        restoredDraftId = null  // 清空：用户选择不保存，不应再更新原草稿
        Log.d(tag, "🗑️ [ViewModel] 用户选择不保存草稿")
    }

    // ========================================================================
    // DefaultLifecycleObserver：生命周期驱动（不再依赖 Activity 发 Intent）
    // ========================================================================

    /**
     * onPause → 保存草稿。
     * 如果用户刚通过退出弹窗明确选择了保存/不保存，则跳过。
     *
     * 分支策略：
     * - restoredDraftId != null（已加载过草稿）→ 直接 UPDATE 原草稿，避免重复
     * - restoredDraftId == null（全新编写）→ 保存临时草稿（防抖机制）
     *
     * 统一保存点：无论是因为跳转内部 Activity、按 Home 键、还是接电话，
     * 只要当前有内容且非显式退出，就落盘。
     * 启动异步保存 Job 并记录引用，onStop/onResume 通过 join() 等待其完成。
     */
    override fun onPause(owner: LifecycleOwner) {
        // 用户通过退出弹窗做了明确选择 → 不重复保存
        if (explicitExitAction != null) {
            Log.d(tag, "💾 [ViewModel] onPause 跳过（显式退出: $explicitExitAction），清除标记")
            explicitExitAction = null
            return
        }

        val s = _state.value
        if (s.text.isBlank() && s.selectedImages.isEmpty()) return
        if (s.publishSuccess) return

        saveJob = viewModelScope.launch {
            if (restoredDraftId != null) {
                // 已加载草稿 → 直接更新原草稿，不创建临时副本，避免重复
                draftManager.saveDraft(
                    s.text, s.selectedImages, s.formatSpanDescriptors,
                    updateDraftId = restoredDraftId
                )
                Log.d(tag, "💾 [ViewModel] onPause → 已更新原草稿 id=$restoredDraftId (覆盖式保存)")
            } else {
                // 全新编写 → 保存临时草稿（防抖机制）
                draftManager.saveTemporaryDraft(s.text, s.selectedImages, s.formatSpanDescriptors)
                Log.d(tag, "💾 [ViewModel] onPause → 临时草稿已保存 (internalActivity=$launchingInternalActivity)")
            }
        }
    }

    /**
     * onResume → 删除所有临时草稿（用户回来了，数据没丢）。
     * 如果 onPause 的保存协程尚未完成，等待其结束再删除，
     * 消除竞态条件。
     */
    override fun onResume(owner: LifecycleOwner) {
        viewModelScope.launch {
            saveJob?.join()  // 等待 onPause 保存完成
            draftManager.deleteAllTemporaryDrafts()
            Log.d(tag, "🗑️ [ViewModel] onResume → 临时草稿已清理")
        }
    }

    /**
     * onStop → 将所有临时草稿升级为永久草稿（用户真的离开了）。
     * 但如果是因为启动内部 Activity（草稿箱/大图预览），则跳过升级。
     *
     * 当 restoredDraftId != null 时，onPause 已直接更新原草稿，
     * 没有创建临时草稿，因此无需升级（调用也是 no-op，但跳过可减少无用 IO）。
     */
    override fun onStop(owner: LifecycleOwner) {
        if (launchingInternalActivity) {
            launchingInternalActivity = false
            Log.d(tag, "📌 [ViewModel] onStop 跳过升级（启动内部 Activity）")
            return
        }
        // 已加载草稿时，onPause 已直接更新原草稿，无临时草稿需要升级
        if (restoredDraftId != null) {
            Log.d(tag, "📌 [ViewModel] onStop 跳过升级（已加载草稿，onPause 已直接更新原草稿）")
            return
        }
        viewModelScope.launch {
            saveJob?.join()  // 等待 onPause 保存完成，消除 save-vs-promote 竞态
            draftManager.markAllTemporaryPermanent()
            Log.d(tag, "📌 [ViewModel] onStop → 临时草稿已升级为永久 (saveJob waited)")
        }
    }

    /**
     * 即将启动 App 内部 Activity，设置标记防止 onStop 误升级临时草稿。
     */
    private fun handleLaunchInternalActivity() {
        launchingInternalActivity = true
    }

    // ========================================================================
    // Day 21+：话题选择器
    // ========================================================================

    private fun handleShowTopicPicker() {
        // 每次打开话题选择器时 shuffle 话题列表，模拟"刷新"效果
        val shuffled = _state.value.hotTopics.shuffled()
        _state.value = _state.value.copy(hotTopics = shuffled)
        savedStateHandle["showTopicPicker"] = true
        Log.d(tag, "📋 [ViewModel] 话题选择器已打开: ${shuffled.size} 个话题")
    }

    private fun handleHideTopicPicker() {
        savedStateHandle["showTopicPicker"] = false
        Log.d(tag, "📋 [ViewModel] 话题选择器已关闭")
    }

    /**
     * 用户选中某个话题 → 关闭选择器。
     *
     * View 层 insertTopicIntoEditor() 已直接操作 Editable，
     * doAfterTextChanged 同步触发 TextChanged → state.text 已精确。
     *
     * 此处仅关闭选择器 + 字数/按钮状态更新 + 持久化，**不修改 state.text**。
     */
    private fun handleSelectTopic(@Suppress("UNUSED_PARAMETER") topicName: String) {
        val charCount = _state.value.text.length
        val isExceeded = charCount > PublishState.MAX_CHAR_LIMIT

        _state.value = _state.value.copy(
            isPublishButtonEnabled = !isExceeded,
            charCount = charCount,
            isCharLimitExceeded = isExceeded,
        )
        savedStateHandle["showTopicPicker"] = false
        persistState(_state.value)
        Log.d(tag, "📋 [ViewModel] SelectTopic → charCount=$charCount (文本由 TextChanged 精确同步)")
    }
}
