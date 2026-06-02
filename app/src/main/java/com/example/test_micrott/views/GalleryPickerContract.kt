package com.example.test_micrott.views

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * 自定义相册选择器 Contract
 *
 * Input:  PickConfig — 剩余可选项数 + 已选照片的 MediaStore._ID 列表
 * Output: List<Uri>? — null=用户取消, 非空=最终选中 URI 列表
 */
class GalleryPickerContract : ActivityResultContract<PickConfig, List<Uri>?>() {

    companion object {
        const val EXTRA_MAX = "gallery_max_selectable"
        const val EXTRA_PRESELECTED_IDS = "gallery_preselected_ids"
        const val EXTRA_RESULT_URIS = "gallery_result_uris"
    }

    override fun createIntent(context: Context, input: PickConfig): Intent {
        return Intent(context, GalleryPickerActivity::class.java).apply {
            putExtra(EXTRA_MAX, input.maxSelectable)
            putExtra(EXTRA_PRESELECTED_IDS, input.preSelectedIds.toLongArray())
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri>? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        return intent.getParcelableArrayListExtra(EXTRA_RESULT_URIS, Uri::class.java)
    }
}

/**
 * @param maxSelectable  本次最多还能选几张（9 - 当前已有数）
 * @param preSelectedIds 已选照片的 MediaStore.Images.Media._ID，用于预勾选
 */
data class PickConfig(
    val maxSelectable: Int,
    val preSelectedIds: List<Long>,
)
