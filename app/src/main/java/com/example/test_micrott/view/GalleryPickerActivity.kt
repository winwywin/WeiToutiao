package com.example.test_micrott.view

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.test_micrott.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自定义相册选择器 Activity
 *
 * 替代系统 Photo Picker，实现：
 * 1. 已选照片勾选标记（跨会话记忆）
 * 2. 动态上限（9 张总上限，本次还能选 N 张）
 * 3. 点击切换选中/取消
 */
class GalleryPickerActivity : AppCompatActivity() {

    companion object {
        private const val tag = "GalleryPicker"
        private const val COLUMN_COUNT = 3
        private const val MAX_TOTAL = 9
    }

    private lateinit var adapter: GalleryPickerAdapter
    private lateinit var tvConfirm: TextView
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // 从 Intent 读取的输入
    private var maxSelectable = 9
    private var preselectedIds = LongArray(0)

    // 全部照片列表
    private val allPhotos = mutableListOf<GalleryPhoto>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery_picker)

        maxSelectable = intent.getIntExtra(
            GalleryPickerContract.EXTRA_MAX, 9
        )
        preselectedIds = intent.getLongArrayExtra(
            GalleryPickerContract.EXTRA_PRESELECTED_IDS
        ) ?: LongArray(0)

        val preselectedSet = preselectedIds.toSet()
        Log.d(tag, "启动相册: max=$maxSelectable, 已选=${preselectedIds.size}")

        // 顶部栏
        findViewById<TextView>(R.id.tv_cancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        tvConfirm = findViewById(R.id.tv_confirm)
        tvConfirm.setOnClickListener { confirmSelection() }

        // 网格
        val rv = findViewById<RecyclerView>(R.id.rv_gallery)
        rv.layoutManager = GridLayoutManager(this, COLUMN_COUNT)
        adapter = GalleryPickerAdapter { position -> toggleSelection(position) }
        rv.adapter = adapter

        loadPhotos(preselectedSet)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ========================================================================
    // 加载相册
    // ========================================================================

    private fun loadPhotos(preselectedSet: Set<Long>) {
        scope.launch {
            val photos = withContext(Dispatchers.IO) { queryAllPhotos(preselectedSet) }
            allPhotos.clear()
            allPhotos.addAll(photos)
            adapter.submitList(photos)
            updateConfirmButton()
            Log.d(tag, "加载完成: ${photos.size} 张照片")
        }
    }

    private fun queryAllPhotos(preselectedSet: Set<Long>): List<GalleryPhoto> {
        val result = mutableListOf<GalleryPhoto>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null, null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                result.add(
                    GalleryPhoto(
                        mediaId = id,
                        uri = uri,
                        isSelected = id in preselectedSet,
                    )
                )
            }
        }
        return result
    }

    // ========================================================================
    // 选中/取消
    // ========================================================================

    private fun toggleSelection(position: Int) {
        val photo = adapter.getItem(position)
        val selectedCount = allPhotos.count { it.isSelected }

        if (photo.isSelected) {
            // 取消选中
            photo.isSelected = false
            adapter.notifyItemChanged(position)
        } else {
            // 检查上限：已选数量 < maxSelectable + 预选数量（= 总共最多 MAX_TOTAL 张）
            val totalLimit = maxSelectable + preselectedIds.size
            if (selectedCount >= totalLimit) {
                Log.d(tag, "已达上限 $totalLimit 张")
                return
            }
            photo.isSelected = true
            adapter.notifyItemChanged(position)
        }
        updateConfirmButton()
    }

    private fun updateConfirmButton() {
        val count = allPhotos.count { it.isSelected }
        tvConfirm.text = "完成($count/$MAX_TOTAL)"
    }

    // ========================================================================
    // 确认返回
    // ========================================================================

    private fun confirmSelection() {
        val selectedUris = allPhotos
            .filter { it.isSelected }
            .map { photo -> photo.uri }

        Log.d(tag, "用户确认选择 ${selectedUris.size} 张")
        val data = intent.apply {
            putParcelableArrayListExtra(
                GalleryPickerContract.EXTRA_RESULT_URIS,
                ArrayList(selectedUris)
            )
        }
        setResult(RESULT_OK, data)
        finish()
    }
}
