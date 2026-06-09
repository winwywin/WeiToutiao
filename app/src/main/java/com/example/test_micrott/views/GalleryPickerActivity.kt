package com.example.test_micrott.views

import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.test_micrott.R
import com.example.test_micrott.data.ImageCompressor
import com.example.test_micrott.data.ThumbnailCache
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

    // 预览层
    private lateinit var previewOverlay: FrameLayout
    private lateinit var vpPreview: ViewPager2
    private lateinit var tvPreviewCount: TextView
    private lateinit var btnPreviewCheck: ImageView
    private lateinit var btnPreviewBack: ImageView
    private var previewAdapter: PreviewPagerAdapter? = null
    private var currentPreviewPosition = 0
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

        // 初始化预览层
        previewOverlay = findViewById(R.id.preview_overlay)
        vpPreview = findViewById(R.id.vp_preview)
        tvPreviewCount = findViewById(R.id.tv_preview_count)
        btnPreviewCheck = findViewById(R.id.btn_preview_check)
        btnPreviewBack = findViewById(R.id.btn_preview_back)

        btnPreviewBack.setOnClickListener { hidePreview() }
        btnPreviewCheck.setOnClickListener { togglePreviewCheck() }

        // 开始加载：显示进度条
        progressBar.visibility = View.VISIBLE
        rvGallery.visibility = View.GONE
        loadPhotos(preselectedSet)

        // 返回键：优先关闭预览层
        onBackPressedDispatcher.addCallback(this) {
            if (previewOverlay.visibility == View.VISIBLE) {
                hidePreview()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
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
    // 预览大图（点击触发，内嵌覆盖层）
    // ========================================================================

    private fun previewPhoto(position: Int) {
        if (allPhotos.isEmpty()) return
        currentPreviewPosition = position

        previewAdapter = PreviewPagerAdapter(allPhotos, scope)
        vpPreview.adapter = previewAdapter
        vpPreview.setCurrentItem(position, false)
        vpPreview.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(pos: Int) {
                currentPreviewPosition = pos
                updatePreviewIndicator()
            }
        })

        updatePreviewIndicator()
        previewOverlay.visibility = View.VISIBLE
    }

    private fun hidePreview() {
        previewOverlay.visibility = View.GONE
        vpPreview.adapter = null
        previewAdapter = null
        // 刷新网格的勾选状态
        adapter.notifyDataSetChanged()
        updateConfirmButton()
    }

    private fun updatePreviewIndicator() {
        tvPreviewCount.text = "${currentPreviewPosition + 1}/${allPhotos.size}"
        val photo = allPhotos.getOrNull(currentPreviewPosition)
        btnPreviewCheck.setImageResource(
            if (photo?.isSelected == true) R.drawable.ic_check_circle
            else R.drawable.ic_check_circle_empty
        )
    }

    private fun togglePreviewCheck() {
        val photo = allPhotos.getOrNull(currentPreviewPosition) ?: return
        val selectedCount = allPhotos.count { it.isSelected }

        if (photo.isSelected) {
            photo.isSelected = false
            selectedOrder.remove(photo.mediaId)
        } else {
            val totalLimit = maxSelectable + preselectedIds.size
            if (selectedCount >= totalLimit) {
                Toast.makeText(this, "最多选${totalLimit}张", Toast.LENGTH_SHORT).show()
                return
            }
            photo.isSelected = true
            selectedOrder.add(photo.mediaId)
        }
        updatePreviewIndicator()
        adapter.notifyItemChanged(currentPreviewPosition)
        updateConfirmButton()
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

    // ========================================================================
    // 预览 ViewPager2 适配器（内嵌）
    // ========================================================================

    private class PreviewPagerAdapter(
        private val photos: List<GalleryPhoto>,
        private val scope: CoroutineScope
    ) : RecyclerView.Adapter<PreviewPagerAdapter.PreviewViewHolder>() {

        override fun getItemCount(): Int = photos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewViewHolder {
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            return PreviewViewHolder(iv)
        }

        override fun onBindViewHolder(holder: PreviewViewHolder, position: Int) {
            holder.bind(photos[position])
        }

        override fun onViewRecycled(holder: PreviewViewHolder) {
            holder.loadJob?.cancel()
            holder.imageView.setImageDrawable(null)
        }

        inner class PreviewViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView) {
            var loadJob: Job? = null

            fun bind(photo: GalleryPhoto) {
                loadJob?.cancel()
                val cacheKey = "preview_${photo.uri}"
                val cached = ThumbnailCache.get(cacheKey)
                if (cached != null) {
                    imageView.setImageBitmap(cached)
                    return
                }

                val targetSize = imageView.resources.displayMetrics.widthPixels
                loadJob = scope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        ImageCompressor.decodeSampledBitmap(
                            imageView.context, photo.uri, targetSize, targetSize
                        )
                    }
                    if (bitmap != null) {
                        ThumbnailCache.put(cacheKey, bitmap)
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }
}
