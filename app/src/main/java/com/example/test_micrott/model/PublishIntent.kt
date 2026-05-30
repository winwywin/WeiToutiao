package com.example.test_micrott.model

import android.net.Uri

/**
 * 集中式用户意图密封舱 (MVI 核心架构组件)
 * 核心设计哲学：
 * 1. 严格枚举用户在微头条发布页上能做的【所有动作】。
 * 2. 界面上任何交互（打字、选图、删图、点击）绝不允许私自修改变量，必须打包成对应的 Intent 塞给 ViewModel。
 */
sealed class PublishIntent {
    // 1. 用户在输入框打字或删除文本
    data class TextChanged(val text: String) : PublishIntent()

    // 2. 用户通过 PhotoPicker 选图完毕回来（带回选中的 Uri 列表）
    data class ImagesPicked(val uris: List<Uri>) : PublishIntent()

    // 3. 用户点击九宫格图片右上角的小红叉（传入被删图片的索引位置）
    data class RemoveImage(val index: Int) : PublishIntent()

    // 4. 用户点击右上角的"发布"按钮
    object ClickPublish : PublishIntent()

    // 5. 用户点击底部工具栏的"# 话题"按钮，插入话题标签
    data class InsertTopic(val topicText: String) : PublishIntent()

    // 6. 用户拖拽九宫格图片调整顺序
    data class MoveImage(val from: Int, val to: Int) : PublishIntent()

    // 7. 用户点击底部工具栏"@"提及按钮，选中用户后插入 @用户名
    data class InsertMention(val mentionText: String) : PublishIntent()
}