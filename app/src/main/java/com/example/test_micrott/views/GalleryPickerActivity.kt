package com.example.test_micrott.views

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ProgressBar
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
        /** 最多查询的照片数量，避免全盘扫描导致超时 */
        private const val QUERY_LIMIT = 500
    }

    private lateinit var adapter: GalleryPickerAdapter
    private lateinit var tvConfirm: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var rvGallery: RecyclerView
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // 从 Intent 读取的输入
    private var maxSelectable = 9
    private var preselectedIds = LongArray(0)

    // 全部照片列表
    private val allPhotos = mutableListOf<GalleryPhoto>()

    /** 用户点击选中/取消的 mediaId 顺序。用于确认时按点击顺序返回 URI。 */
    private val selectedOrder = mutableListOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val t0 = System.currentTimeMillis()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery_picker)
        Log.d(tag, "⏱️ onCreate setContentView 耗时: ${System.currentTimeMillis() - t0}ms")

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

        progressBar = findViewById(R.id.progress_gallery)

        // 网格
        rvGallery = findViewById(R.id.rv_gallery)
        rvGallery.layoutManager = GridLayoutManager(this, COLUMN_COUNT)
        adapter = GalleryPickerAdapter(scope,
            onToggle = { position -> toggleSelection(position) },
            onPreviewClick = { position -> previewPhoto(position) }
        )
        rvGallery.adapter = adapter

        // 开始加载：显示进度条
        progressBar.visibility = View.VISIBLE
        rvGallery.visibility = View.GONE
        loadPhotos(preselectedSet)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ========================================================================
    // 加载相册
    // ========================================================================

    private var onCreateTime = 0L

    private fun loadPhotos(preselectedSet: Set<Long>) {
        onCreateTime = System.currentTimeMillis()
        scope.launch {
            // ── 阶段1：MediaStore 查询 ──
            val tQueryStart = System.currentTimeMillis()
            Log.d(tag, "⏱️ [阶段1] 开始查询 MediaStore...")
            val photos = withContext(Dispatchers.IO) { queryAllPhotos(preselectedSet) }
            val tQueryEnd = System.currentTimeMillis()
            Log.d(tag, "⏱️ [阶段1] MediaStore 查询完成: ${photos.size} 张, 耗时 ${tQueryEnd - tQueryStart}ms")

            // 内存快照
            val rt = Runtime.getRuntime()
            val memUsed = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
            val memMax = rt.maxMemory() / 1024 / 1024
            Log.d(tag, "🧠 当前堆内存: ${memUsed}MB / ${memMax}MB (max)")

            // ── 阶段2：提交到 Adapter ──
            allPhotos.clear()
            allPhotos.addAll(photos)
            // 预选照片排在选中顺序最前面（来自上一轮选择）
            selectedOrder.clear()
            selectedOrder.addAll(preselectedSet)
            val tSubmitStart = System.currentTimeMillis()
            adapter.submitList(photos)
            val tSubmitEnd = System.currentTimeMillis()
            Log.d(tag, "⏱️ [阶段2] submitList + 首屏 onBind 触发完成: ${tSubmitEnd - tSubmitStart}ms")

            updateConfirmButton()
            // 加载完成：隐藏进度条，显示网格
            progressBar.visibility = View.GONE
            rvGallery.visibility = View.VISIBLE
            val totalElapsed = System.currentTimeMillis() - onCreateTime
            Log.d(tag, "✅ 相册就绪，总耗时: ${totalElapsed}ms")
        }
    }

    private fun queryAllPhotos(preselectedSet: Set<Long>): List<GalleryPhoto> {
        val result = mutableListOf<GalleryPhoto>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        val queryArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：用 Bundle 参数限制查询数量，避免全盘扫描超时
            Bundle().apply {
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, QUERY_LIMIT)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            }
        } else {
            null
        }

        val cursor = if (queryArgs != null) {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                queryArgs,
                null
            )
        } else {
            // Android 10 及以下：直接 query + 手动截断
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null, null,
                "$sortOrder LIMIT $QUERY_LIMIT"
            )
        }

        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
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

        if (cursor == null) {
            Log.e(tag, "MediaStore query 返回 null — 权限不足或系统媒体库异常")
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
            selectedOrder.remove(photo.mediaId)
            adapter.notifyItemChanged(position)
        } else {
            // 检查上限：已选数量 < maxSelectable + 预选数量（= 总共最多 MAX_TOTAL 张）
            val totalLimit = maxSelectable + preselectedIds.size
            if (selectedCount >= totalLimit) {
                Log.d(tag, "已达上限 $totalLimit 张")
                return
            }
            photo.isSelected = true
            selectedOrder.add(photo.mediaId)
            adapter.notifyItemChanged(position)
        }
        updateConfirmButton()
    }

    private fun updateConfirmButton() {
        val count = allPhotos.count { it.isSelected }
        tvConfirm.text = "完成($count/$MAX_TOTAL)"
    }

    // ========================================================================
    // 预览大图（长按触发）
    // ========================================================================

    private fun previewPhoto(position: Int) {
        if (allPhotos.isEmpty()) return
        val uriStrings = allPhotos.map { it.uri.toString() }
        val intent = Intent(this, ImagePreviewActivity::class.java).apply {
            putStringArrayListExtra(ImagePreviewActivity.EXTRA_URI_LIST, ArrayList(uriStrings))
            putExtra(ImagePreviewActivity.EXTRA_POSITION, position)
        }
        startActivity(intent)
    }

    // ========================================================================
    // 确认返回
    // ========================================================================

    private fun confirmSelection() {
        // 按用户点击顺序收集 URI（而非 MediaStore DATE_MODIFIED 顺序）
        val uriMap = allPhotos.associate { it.mediaId to it.uri }
        val selectedUris = selectedOrder
            .mapNotNull { mediaId -> uriMap[mediaId] }

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
