package com.example.test_micrott.viewmodels

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.example.test_micrott.model.PublishIntent
import com.example.test_micrott.model.PublishState
import com.example.test_micrott.model.SpanDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import com.example.test_micrott.util.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 核心调度大脑 - PublishViewModel (MVI-MVVM 融合架构核心)
 * 职责：
 * 1. 唯一持有并驱动全场唯一的 PublishState。
 * 2. 接收来自 View 层的单向 Intent 指令，进行闭环业务演算。
 *
 * Day 6 升级：接入 SavedStateHandle，旋转屏幕 / 进程销毁后状态100%恢复。
 * Day 17 升级：继承 AndroidViewModel（需要 Context 获取 cacheDir 压缩图片）。
 *             handleClickPublish 改为分步发布：压缩 → 上传 → 完成。
 */
class PublishViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    companion object {
        // SavedStateHandle 存储键
        private const val KEY_TEXT = "publish_text"
        private const val KEY_IMAGES = "publish_images"
        private const val KEY_LOADING = "publish_loading"
        private const val KEY_BUTTON_ENABLED = "publish_button_enabled"
        private const val KEY_FORMAT_SPANS = "publish_format_spans"
    }

    // 🛡️ 防护隔离防线：对内私有可变，严禁外部直接 `.value = ...` 篡改账本
    // Day 6 升级：初始化时从 SavedStateHandle 恢复（旋转后重建场景）
    private val _state = MutableStateFlow(restoreState())

    // 📺 对外只读暴露：View 层（Activity）只能单向 collect 监听这个流
    val state: StateFlow<PublishState> = _state.asStateFlow()

    private val tag = "MVI_FRAMEWORK"

    // ========================================================================
    // Day 6 新增：SavedStateHandle 状态恢复
    // ========================================================================

    /**
     * 从 SavedStateHandle 恢复上次的 UI 状态。
     * 旋转屏幕 / 进程销毁后，SavedStateHandle 会自动反序列化 Bundle 中的数据。
     */
    private fun restoreState(): PublishState {
        val savedText = savedStateHandle.get<String>(KEY_TEXT) ?: ""
        val savedImages = savedStateHandle.get<ArrayList<Uri>>(KEY_IMAGES) ?: emptyList()
        val savedLoading = savedStateHandle.get<Boolean>(KEY_LOADING) ?: false
        val savedButtonEnabled = savedStateHandle.get<Boolean>(KEY_BUTTON_ENABLED) ?: false
        val savedFormatSpans = SpanDescriptor.deserializeList(
            savedStateHandle.get<ArrayList<String>>(KEY_FORMAT_SPANS)
        )

        if (savedText.isNotEmpty() || savedImages.isNotEmpty()) {
            Log.d(tag, "🔄 [ViewModel] SavedStateHandle 恢复 -> text=${savedText.length}字, images=${savedImages.size}张")
        }

        return PublishState(
            text = savedText,
            selectedImages = savedImages,
            isLoading = savedLoading,
            isPublishButtonEnabled = savedButtonEnabled,
            formatSpanDescriptors = savedFormatSpans,
        )
    }

    /**
     * 每次状态变更后，将关键字段同步写入 SavedStateHandle。
     * SavedStateHandle 内部自动处理进程死亡序列化，配置变更（旋转）时数据天然存活。
     */
    private fun persistState(state: PublishState) {
        savedStateHandle[KEY_TEXT] = state.text
        savedStateHandle[KEY_IMAGES] = ArrayList(state.selectedImages)
        savedStateHandle[KEY_LOADING] = state.isLoading
        savedStateHandle[KEY_BUTTON_ENABLED] = state.isPublishButtonEnabled
        savedStateHandle[KEY_FORMAT_SPANS] = SpanDescriptor.serializeList(state.formatSpanDescriptors)
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
            is PublishIntent.TextChanged -> handleTextChanged(intent.text)
            is PublishIntent.ImagesPicked -> handleImagesPicked(intent.uris)
            is PublishIntent.RemoveImage -> handleRemoveImage(intent.index)
            is PublishIntent.ClickPublish -> handleClickPublish()
            is PublishIntent.InsertTopic -> handleInsertTopic(intent.topicText)
            is PublishIntent.MoveImage -> handleMoveImage(intent.from, intent.to)
            is PublishIntent.InsertMention -> handleInsertMention(intent.mentionText)
            is PublishIntent.SaveFormattingSpans -> handleSaveFormatting(intent.descriptors)
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
     * 文本持久化依赖 Activity.onSaveInstanceState 即可覆盖旋转/进程死亡场景。
     */
    private fun handleTextChanged(newText: String) {
        if (_state.value.text == newText) return

        val isButtonEnabled = newText.trim().isNotEmpty() || _state.value.selectedImages.isNotEmpty()

        val newState = _state.value.copy(
            text = newText,
            isPublishButtonEnabled = isButtonEnabled,
        )
        _state.value = newState
        // ⚠️ 不再 persistState — 见上方注释

        Log.d(tag, "📺 [ViewModel] 状态增量演算完成 [TextChanged] -> 吐出新State")
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
     *   Step 1 — 压缩：逐张图片调用 ImageCompressor.compressToFile，
     *            进度推进 0→50%，状态文字 "正在压缩 x/n..."
     *   Step 2 — 上传：模拟逐张上传（delay 600ms/张），
     *            进度推进 50→100%，状态文字 "正在上传 x/n..."
     *   Step 3 — 完成：重置 State，清理临时文件
     *
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
            uploadStatusText = if (totalImages > 0) "准备发布..." else "发布中...",
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
                    val compressState = _state.value.copy(
                        uploadProgress = compressProgress,
                        uploadStatusText = "正在压缩 ${index + 1}/$totalImages..."
                    )
                    _state.value = compressState

                    val compressedFile = withContext(Dispatchers.IO) {
                        try {
                            val bitmap = ImageCompressor.decodeSampledBitmap(
                                ctx, uri,
                                targetWidth = 1920, targetHeight = 1920
                            )
                            if (bitmap != null) {
                                val outFile = File(compressDir, "img_${System.currentTimeMillis()}_$index.jpg")
                                ImageCompressor.compressToFile(bitmap, outFile, quality = 85)
                            } else null
                        } catch (e: Exception) {
                            Log.w(tag, "⚠️ [ViewModel] 图片压缩失败 index=$index: ${e.message}")
                            null
                        }
                    }
                    compressedFile?.let { compressedFiles.add(it) }
                    Log.d(tag, "🗜️ [ViewModel] 压缩完成 ${index + 1}/$totalImages，文件=${compressedFile?.name}")
                }
            }

            // ================================================================
            // Step 2: 模拟上传（每张 600ms，占进度 50→100%）
            // ================================================================
            val filesToUpload = if (compressedFiles.isNotEmpty()) compressedFiles else
                List(maxOf(totalImages, 1)) { null } // 无图片时至少跑一次模拟

            filesToUpload.forEachIndexed { index, file ->
                val uploadProgress = 50 + ((index.toFloat() / filesToUpload.size) * 50).toInt()
                val uploadState = _state.value.copy(
                    uploadProgress = uploadProgress,
                    uploadStatusText = if (totalImages > 0) "正在上传 ${index + 1}/${filesToUpload.size}..."
                                       else "发布中..."
                )
                _state.value = uploadState

                delay(600) // 模拟单张上传耗时
                Log.d(tag, "📤 [ViewModel] 上传完成 ${index + 1}/${filesToUpload.size}，文件=${file?.name ?: "（无图）"}")
            }

            // ================================================================
            // Step 3: 清理临时文件 + 重置 State
            // ================================================================
            withContext(Dispatchers.IO) {
                compressedFiles.forEach { it.delete() }
                Log.d(tag, "🗑️ [ViewModel] 清理临时压缩文件 ${compressedFiles.size} 个")
            }

            val resetState = PublishState() // 所有字段默认值
            _state.value = resetState
            persistState(resetState)

            Log.d(tag, "✅ [ViewModel] 发布完成，表单已重置")
        }
    }

    /**
     * 处理插入话题标签意图
     *
     * Day 6 修复：View 层的 insertTopicIntoEditor 已直接修改 EditText 文本，
     * doAfterTextChanged 会自动触发 TextChanged 将完整文本同步过来。
     * 因此此处不再追加文本（避免重复插入），仅确保发布按钮亮起。
     */
    private fun handleInsertTopic(topicText: String) {
        if (!_state.value.isPublishButtonEnabled) {
            val newState = _state.value.copy(isPublishButtonEnabled = true)
            _state.value = newState
            persistState(newState)
        }
        Log.d(tag, "📺 [ViewModel] 状态增量演算完成 [InsertTopic] -> 话题: $topicText（文本由TextChanged同步）")
    }

    /**
     * 处理插入 @提及 意图
     *
     * 与 InsertTopic 同理：View 层直接修改 EditText 文本，
     * doAfterTextChanged 会触发 TextChanged 同步完整文本，
     * 此处仅确保发布按钮亮起。
     */
    private fun handleInsertMention(mentionText: String) {
        if (!_state.value.isPublishButtonEnabled) {
            val newState = _state.value.copy(isPublishButtonEnabled = true)
            _state.value = newState
            persistState(newState)
        }
        Log.d(tag, "📺 [ViewModel] 状态增量演算完成 [InsertMention] -> @$mentionText（文本由TextChanged同步）")
    }

    /**
     * 处理保存格式化 Span 描述符意图
     *
     * 格式化操作是纯 View 层行为，此处仅接收 Span 描述符并持久化到 SavedStateHandle，
     * 确保旋转屏幕后格式化状态不丢失。
     */
    private fun handleSaveFormatting(descriptors: List<SpanDescriptor>) {
        val newState = _state.value.copy(formatSpanDescriptors = descriptors)
        _state.value = newState
        persistState(newState)
        Log.d(tag, "🎨 [ViewModel] 格式化 Span 已保存: ${descriptors.size} 个")
    }

    /**
     * 处理拖拽排序意图：交换列表中两个位置的图片
     */
    private fun handleMoveImage(from: Int, to: Int) {
        val currentImages = _state.value.selectedImages.toMutableList()
        if ((from !in currentImages.indices) || (to !in currentImages.indices)) return
        if (from == to) return

        val moved = currentImages.removeAt(from)
        currentImages.add(to, moved)

        val newState = _state.value.copy(selectedImages = currentImages)
        _state.value = newState
        persistState(newState)

        Log.d(tag, "📺 [ViewModel] 状态增量演算完成 [MoveImage] -> $from ↔ $to")
    }
}
