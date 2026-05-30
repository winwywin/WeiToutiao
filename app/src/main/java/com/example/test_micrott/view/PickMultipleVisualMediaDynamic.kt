package com.example.test_micrott.view

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract

/**
 * 动态上限版照片多选合约。
 *
 * 与标准 PickMultipleVisualMedia 的区别：
 * - 构造函数不固定 maxItems，而是通过 launch(input) 每次传入
 * - 解决"已有 2 张但相册仍提示可选 9 张"的 UX 问题
 */
class PickMultipleVisualMediaDynamic : ActivityResultContract<Int, List<Uri>>() {

    override fun createIntent(context: Context, input: Int): Intent {
        return Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, input)
            type = "image/*"
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()

        val uris = mutableListOf<Uri>()
        val clipData = intent.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let { uris.add(it) }
            }
        } else {
            intent.data?.let { uris.add(it) }
        }
        return uris
    }

    override fun getSynchronousResult(
        context: Context,
        input: Int
    ): SynchronousResult<List<Uri>>? = null
}
