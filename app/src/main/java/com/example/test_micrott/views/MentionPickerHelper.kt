package com.example.test_micrott.views

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * @提及用户选择器辅助类。
 *
 * 从 MainActivity 提取，展示 AlertDialog 用户列表，
 * 点击用户名后回调 onMentionPicked。
 */
class MentionPickerHelper(
    private val activity: AppCompatActivity,
    private val onMentionPicked: (String) -> Unit
) {
    private val userNames = arrayOf(
        "张三", "李四", "王五", "赵六", "孙七",
        "周杰伦", "刘德华", "张学友", "郭富城", "黎明",
        "范冰冰", "李冰冰", "杨幂", "赵丽颖", "刘亦菲",
    )

    fun show() {
        AlertDialog.Builder(activity)
            .setTitle("@ 提及用户")
            .setItems(userNames) { _, which ->
                onMentionPicked(userNames[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
