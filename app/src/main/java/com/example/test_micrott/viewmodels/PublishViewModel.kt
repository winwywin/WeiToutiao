package com.example.test_micrott.viewmodels

import android.net.Uri
import android.util.Log
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
 */
class PublishViewModel : ViewModel() {

    // 🛡️ 防护隔离防线：对内私有可变，严禁外部直接 `.value = ...` 篡改账本
    private val _state = MutableStateFlow(PublishState())

    // 📺 对外只读暴露：View 层（Activity）只能单向 collect 监听这个流
    val state: StateFlow<PublishState> = _state.asStateFlow()

    private val TAG = "MVI_FRAMEWORK"

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
        }
    }

    /**
     * 处理用户打字意图（高低频清洗与逻辑演算）
     */
    private fun handleTextChanged(newText: String) {
        // 核心高低频过滤逻辑：只有当内容实质发生改变时，才允许执行 copy 并向下游倒灌
        if (_state.value.text == newText) return

        // 核心联动业务计算：按钮亮灭判定（文本除去空格后非空，或已有图片）
        val isButtonEnabled = newText.trim().isNotEmpty() || _state.value.selectedImages.isNotEmpty()

        // 增量演进：克隆老状态，赋上新值，弹射出全新状态
        _state.value = _state.value.copy(
            text = newText,
            isPublishButtonEnabled = isButtonEnabled
        )
        Log.d(TAG, "📺 [ViewModel] 状态增量演算完成 [TextChanged] -> 吐出新State")
    }

    /**
     * 处理照片选择器带回图片的意图
     */
    private fun handleImagesPicked(uris: List<Uri>) {
        // 将只读 List 转为可变列表进行增量合并
        val currentImages = _state.value.selectedImages.toMutableList()
        currentImages.addAll(uris)

        // 微信/头条硬约束：最多支持 9 张图
        val safetyImages = if (currentImages.size > 9) currentImages.subList(0, 9) else currentImages

        _state.value = _state.value.copy(
            selectedImages = safetyImages
        )
        Log.d(TAG, "📺 [ViewModel] 状态增量演算完成 [ImagesPicked] -> 当前图片数: ${safetyImages.size}")
    }

    /**
     * 处理九宫格点击小红叉删图意图
     */
    private fun handleRemoveImage(index: Int) {
        val currentImages = _state.value.selectedImages.toMutableList()
        if (index in currentImages.indices) {
            currentImages.removeAt(index)

            // 删图后需要重新联动计算发布按钮的亮灭（若字空且图空，按钮应该变灰）
            val isButtonEnabled = _state.value.text.trim().isNotEmpty() || currentImages.isNotEmpty()

            _state.value = _state.value.copy(
                selectedImages = currentImages,
                isPublishButtonEnabled = isButtonEnabled
            )
            Log.d(TAG, "📺 [ViewModel] 状态增量演算完成 [RemoveImage] -> 删除了索引 $index 的图片")
        }
    }

    /**
     * 处理点击发布按钮意图
     */
    private fun handleClickPublish() {
        // 拉起全局进度条 Loading 遮罩，锁死界面防止二次狂点并发
        _state.value = _state.value.copy(
            isLoading = true
        )
        Log.d(TAG, "📺 [ViewModel] 状态增量演算完成 [ClickPublish] -> 进入 Loading 发布中状态")

        // TODO: Day 7 引入协程正式通知 data 层进行物理网络请求或数据库写入
    }

    /**
     * 处理插入话题标签意图（MVI 大脑演算新文本）
     * 当前实现：在文本末尾追加话题（Day 6 可升级为光标位置插入）
     */
    private fun handleInsertTopic(topicText: String) {
        val currentText = _state.value.text
        val newText = currentText + topicText

        _state.value = _state.value.copy(
            text = newText,
            isPublishButtonEnabled = true
        )
        Log.d(TAG, "📺 [ViewModel] 状态增量演算完成 [InsertTopic] -> 插入话题: $topicText")
    }
}
