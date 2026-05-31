package com.example.test_micrott.model

import android.net.Uri
/**
 * 微头条页面唯一状态源 (A7 进阶亮点)
 * 核心设计哲学：
 * 1. 界面上所有控件呈现的细节，有且仅有单向监听这一个"唯一真理源"。
 * 2. 内部属性必须全部用 val 修饰，确保状态只读、线程安全，彻底消灭并发导致的状态冲突。
 *
 * Day 17 新增：
 *   - uploadProgress: 0-100，供水平进度条渲染
 *   - uploadStatusText: 当前步骤描述（"正在压缩 1/3..."、"正在上传 2/3..."）
 */
data class PublishState(
    val text: String = "",
    val selectedImages: List<Uri> = emptyList(),
    val isLoading: Boolean = false,
    val isPublishButtonEnabled: Boolean = false,
    val formatSpanDescriptors: List<SpanDescriptor> = emptyList(),
    // Day 17: 上传进度（0-100）和当前步骤文字
    val uploadProgress: Int = 0,
    val uploadStatusText: String = "",
)
