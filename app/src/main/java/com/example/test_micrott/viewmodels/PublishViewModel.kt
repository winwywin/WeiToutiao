package com.example.test_micrott.viewmodels

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.example.test_micrott.model.PublishIntent
import com.example.test_micrott.model.PublishState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 核心调度大脑 - PublishViewModel (MVI-MVVM 融合架构核心)
 * 职责：
 * 1. 唯一持有并驱动全场唯一的 PublishState。
 * 2. 接收来自 View 层的单向 Intent 指令，进行闭环业务演算。
 *
 * Day 6 升级：接入 SavedStateHandle，旋转屏幕 / 进程销毁后状态100%恢复。
 */
class PublishViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        // SavedStateHandle 存储键
        private const val KEY_TEXT = "publish_text"
        private const val KEY_IMAGES = "publish_images"
        private const val KEY_LOADING = "publish_loading"
        private const val KEY_BUTTON_ENABLED = "publish_button_enabled"
    }

    // 🛡️ 防护隔离防线：对内私有可变，严禁外部直接 `.value = ...` 篡改账本
    // Day 6 升级：初始化时从 SavedStateHandle 恢复（旋转后重建场景）
    private val _state = MutableStateFlow(restoreState())

    // 📺 对外只读暴露：View 层（Activity）只能单向 collect 监听这个流
    val state: StateFlow<PublishState> = _state.asStateFlow()

    private val TAG = "MVI_FRAMEWORK"

    // ========================================================================
    // Day 6 新增：SavedStateHandle 状态恢复
    // ========================================================================

    /**
     * 从 SavedStateHandle 恢复上次的 UI 状态。
     * 旋转屏幕 / 进程销毁后，SavedStateHandle 会自动反序列化 Bundle 中的数据。
     */
    private fun restoreState(): PublishState {
        val savedText = savedStateHandle.get<String>(KEY_TEXT) ?: ""
        val savedImages = savedStateHandle.get<ArrayList<Uri>>(KEY_IMAGES) ?: emptyList<Uri>()
        val savedLoading = savedStateHandle.get<Boolean>(KEY_LOADING) ?: false
        val savedButtonEnabled = savedStateHandle.get<Boolean>(KEY_BUTTON_ENABLED) ?: false

        if (savedText.isNotEmpty() || savedImages.isNotEmpty()) {
            Log.d(TAG, "🔄 [ViewModel] SavedStateHandle 恢复 -> text=${savedText.length}字, images=${savedImages.size}张")
        }

        return PublishState(
            text = savedText,
            selectedImages = savedImages,
            isLoading = savedLoading,
            isPublishButtonEnabled = savedButtonEnabled
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
    }

    // ========================================================================
    // MVI 入口
    // ========================================================================

    /**
     * View 层向 ViewModel 打小报告的唯一物理入口
     */
    fun sendIntent(intent: PublishIntent) {
        Log.d(TAG, "🧠 [ViewModel] 成功拦截到用户意图: $intent")

        // 利用 sealed class 的完备性强制分支检查，漏写任何一个意图直接编译报错
        when (intent) {
            is PublishIntent.TextChanged -> handleTextChanged(intent.text)
            is PublishIntent.ImagesPicked -> handleImagesPicked(intent.uris)
            is PublishIntent.RemoveImage -> handleRemoveImage(intent.index)
            is PublishIntent.ClickPublish -> handleClickPublish()
            is PublishIntent.InsertTopic -> handleInsertTopic(intent.topicText)
            is PublishIntent.MoveImage -> handleMoveImage(intent.from, intent.to)
        }
    }

    // ========================================================================
    // Intent 处理器
    // ========================================================================

    /**
     * 处理用户打字意图（高低频清洗与逻辑演算）
     */
    private fun handleTextChanged(newText: String) {
        if (_state.value.text == newText) return

        val isButtonEnabled = newText.trim().isNotEmpty() || _state.value.selectedImages.isNotEmpty()

        val newState = _state.value.copy(
            text = newText,
            isPublishButtonEnabled = isButtonEnabled
        )
        _state.value = newState
        persistState(newState)

        Log.d(TAG, "📺 [ViewModel] 状态增量演算完成 [TextChanged] -> 吐出新State")
    }

    /**
     * 处理照片选择器带回图片的意图
     */
    private fun handleImagesPicked(uris: List<Uri>) {
        val currentImages = _state.value.selectedImages.toMutableList()
        currentImages.addAll(uris)

        // 微信/头条硬约束：最多支持 9 张图
        val safetyImages = if (currentImages.size > 9) currentImages.subList(0, 9) else currentImages

        val newState = _state.value.copy(
            selectedImages = safetyImages
        )
        _state.value = newState
        persistState(newState)

        Log.d(TAG, "📺 [ViewModel] 状态增量演算完成 [ImagesPicked] -> 当前图片数: ${safetyImages.size}")
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
                isPublishButtonEnabled = isButtonEnabled
            )
            _state.value = newState
            persistState(newState)

            Log.d(TAG, "📺 [ViewModel] 状态增量演算完成 [RemoveImage] -> 删除了索引 $index 的图片")
        }
    }

    /**
     * 处理点击发布按钮意图
     */
    private fun handleClickPublish() {
        val newState = _state.value.copy(
            isLoading = true
        )
        _state.value = newState
        persistState(newState)

        Log.d(TAG, "📺 [ViewModel] 状态增量演算完成 [ClickPublish] -> 进入 Loading 发布中状态")

        // TODO: Day 7 引入协程正式通知 data 层进行物理网络请求或数据库写入
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
        Log.d(TAG, "📺 [ViewModel] 状态增量演算完成 [InsertTopic] -> 话题: $topicText（文本由TextChanged同步）")
    }

    /**
     * 处理拖拽排序意图：交换列表中两个位置的图片
     */
    private fun handleMoveImage(from: Int, to: Int) {
        val currentImages = _state.value.selectedImages.toMutableList()
        if (from !in currentImages.indices || to !in currentImages.indices) return
        if (from == to) return

        val moved = currentImages.removeAt(from)
        currentImages.add(to, moved)

        val newState = _state.value.copy(selectedImages = currentImages)
        _state.value = newState
        persistState(newState)

        Log.d(TAG, "📺 [ViewModel] 状态增量演算完成 [MoveImage] -> $from ↔ $to")
    }
}
