package com.example.test_micrott.models

import android.net.Uri

/**
 * 集中式用户意图密封舱 (MVI 核心架构组件)
 *
 * 按业务子域分组：
 * - Text：文本编辑（打字、格式、话题、提及、编辑器交互）
 * - Image：图片管理（选图、删图、排序）
 * - Publish：发布流程（发布按钮、成功页）
 * - Draft：草稿操作（草稿箱、恢复、退出决策）
 * - Internal：框架协调信号（内部跳转、弹窗显隐）
 */
sealed class PublishIntent {

    // ================================================================
    // Text — 文本编辑
    // ================================================================
    sealed class Text : PublishIntent() {
        data class TextChanged(val text: String) : Text()
        data class InsertTopic(val topicText: String) : Text()
        data class InsertMention(val mentionText: String) : Text()
        data class SaveFormattingSpans(val descriptors: List<SpanDescriptor>) : Text()
        data class SelectTopic(val topicName: String) : Text()
        data object EditorTouched : Text()
    }

    // ================================================================
    // Image — 图片管理
    // ================================================================
    sealed class Image : PublishIntent() {
        data class ImagesPicked(val uris: List<Uri>) : Image()
        data class RemoveImage(val index: Int) : Image()
        data class ReorderImages(val uris: List<Uri>) : Image()
    }

    // ================================================================
    // Publish — 发布流程
    // ================================================================
    sealed class Publish : PublishIntent() {
        data object ClickPublish : Publish()
        data object DismissSuccess : Publish()
    }

    // ================================================================
    // Draft — 草稿操作
    // ================================================================
    sealed class Draft : PublishIntent() {
        data object OpenDraftBox : Draft()
        data class RestoreDraft(val id: Long) : Draft()
        data object ConfirmSaveAndExit : Draft()
        data object ConfirmDiscardAndExit : Draft()
    }

    // ================================================================
    // Internal — 框架协调信号（不改变用户数据）
    // ================================================================
    sealed class Internal : PublishIntent() {
        data object LaunchInternalActivity : Internal()
        data object ShowTopicPicker : Internal()
        data object HideTopicPicker : Internal()
    }
}
