package com.example.test_micrott.model

import android.net.Uri

/**
 * 上传状态的结构化描述，由 View 层负责转换为本地化字符串。
 *
 * 设计原则：ViewModel 只输出结构化数据，不持有 UI 字符串，
 * 符合「Separation of Concerns」和「Use resource strings」的 lint 要求。
 */
sealed class UploadStatus {
    /** 空闲（未发布）*/
    object Idle : UploadStatus()
    /** 初始化，尚未开始具体步骤 */
    object Preparing : UploadStatus()
    /** 正在压缩第 [current] 张，共 [total] 张 */
    data class Compressing(val current: Int, val total: Int) : UploadStatus()
    /** 正在上传第 [current] 张，共 [total] 张 */
    data class Uploading(val current: Int, val total: Int) : UploadStatus()
    /** 发布中（无图片场景）*/
    object Publishing : UploadStatus()
}

/**
 * 微头条页面唯一状态源 (A7 进阶亮点)
 * 核心设计哲学：
 * 1. 界面上所有控件呈现的细节，有且仅有单向监听这一个"唯一真理源"。
 * 2. 内部属性必须全部用 val 修饰，确保状态只读、线程安全，彻底消灭并发导致的状态冲突。
 *
 * Day 17 升级：
 *   - uploadProgress: 0-100，供水平进度条渲染
 *   - uploadStatus: 结构化步骤描述（UploadStatus 密封类），由 View 层用 getString() 渲染，
 *                   避免 ViewModel 持有硬编码字符串
 */
data class PublishState(
    val text: String = "",
    val selectedImages: List<Uri> = emptyList(),
    val isLoading: Boolean = false,
    val isPublishButtonEnabled: Boolean = false,
    val formatSpanDescriptors: List<SpanDescriptor> = emptyList(),
    // Day 17: 上传进度（0-100）
    val uploadProgress: Int = 0,
    // Day 17: 上传步骤（结构化，不含硬编码字符串）
    val uploadStatus: UploadStatus = UploadStatus.Idle,
    // Day 17+：字数统计
    val charCount: Int = 0,
    val maxCharLimit: Int = MAX_CHAR_LIMIT,
    val isCharLimitExceeded: Boolean = false,
    // Day 17+：发布成功结果页
    val publishSuccess: Boolean = false,
    val publishResultText: String = "",
    val publishResultImageCount: Int = 0,
    // Day 20+：草稿自动保存
    val hasDraft: Boolean = false,
    val showDraftPrompt: Boolean = false,  // 弹框展示标记（只展示一次）
    val draftSavedAt: Long = 0L,
    val draftTextLength: Int = 0,
    val draftImageCount: Int = 0,
) {
    companion object {
        /** 微头条字数上限（与今日头条一致） */
        const val MAX_CHAR_LIMIT = 2000
    }
}
