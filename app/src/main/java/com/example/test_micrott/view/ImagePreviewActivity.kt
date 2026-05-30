package com.example.test_micrott.view

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.test_micrott.R
import com.example.test_micrott.util.ImageCompressor
import com.example.test_micrott.util.ThumbnailCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Day 9: ViewPager2 全屏大图预览
 *
 * 特性：
 *   - 全屏暗色背景，沉浸式浏览
 *   - ViewPager2 左右滑动切换图片
 *   - 按需懒加载：offScreenPageLimit=1，只解码当前页+相邻页
 *   - ImageCompressor 下采样到屏幕宽度（~1080px），防 OOM
 *   - FIT_CENTER 缩放，各分辨率不拉伸变形
 *   - 页码指示器（当前/总数）
 *   - 关闭按钮 → finish()
 */
class ImagePreviewActivity : AppCompatActivity() {

    companion object {
        const val TAG = "ImagePreview"
        const val EXTRA_URI_LIST = "extra_uri_list"
        const val EXTRA_POSITION = "extra_position"
        // 大图预览的目标尺寸：屏幕宽度典型值
        const val PREVIEW_TARGET_SIZE = 1080
    }

    private val imageUris = mutableListOf<Uri>()
    private var currentPosition = 0
    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: PreviewAdapter

    // 独立 CoroutineScope，activity 销毁时取消所有正在加载的任务
    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_preview)

        parseIntent()
        initViews()
    }

    private fun parseIntent() {
        val uriStrings = intent.getStringArrayListExtra(EXTRA_URI_LIST)
        if (uriStrings != null) {
            imageUris.clear()
            uriStrings.forEach { imageUris.add(Uri.parse(it)) }
        }
        currentPosition = intent.getIntExtra(EXTRA_POSITION, 0)
            .coerceIn(0, (imageUris.size - 1).coerceAtLeast(0))

        Log.d(TAG, "打开预览: ${imageUris.size} 张, 起始位置=$currentPosition")
    }

    private fun initViews() {
        // 页码指示器
        val tvIndicator = findViewById<android.widget.TextView>(R.id.tv_page_indicator)
        updateIndicator(tvIndicator, currentPosition)

        // 关闭按钮
        findViewById<View>(R.id.btn_close_preview).setOnClickListener {
            finish()
        }

        // ViewPager2
        viewPager = findViewById(R.id.vp_preview)
        adapter = PreviewAdapter(imageUris, previewScope)
        viewPager.adapter = adapter

        // 懒加载：只预加载当前页前后各 1 页
        viewPager.offscreenPageLimit = 1

        viewPager.setCurrentItem(currentPosition, false)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicator(tvIndicator, position)
            }
        })
    }

    private fun updateIndicator(tv: android.widget.TextView, position: Int) {
        tv.text = "${position + 1}/${imageUris.size}"
    }

    override fun onDestroy() {
        super.onDestroy()
        previewScope.cancel()
    }

    // ========================================================================
    // PreviewAdapter — ViewPager2 内部适配器
    // ========================================================================

    private class PreviewAdapter(
        private val uris: List<Uri>,
        private val scope: CoroutineScope,
    ) : RecyclerView.Adapter<PreviewViewHolder>() {

        override fun getItemCount(): Int = uris.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image_preview, parent, false)
            return PreviewViewHolder(view)
        }

        override fun onBindViewHolder(holder: PreviewViewHolder, position: Int) {
            holder.bind(uris[position], scope)
        }

        override fun onViewRecycled(holder: PreviewViewHolder) {
            holder.recycle()
        }
    }

    // ========================================================================
    // PreviewViewHolder
    // ========================================================================

    private class PreviewViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.iv_preview)
        private var loadJob: Job? = null
        private var boundPosition = RecyclerView.NO_POSITION

        fun bind(uri: Uri, scope: CoroutineScope) {
            loadJob?.cancel()

            boundPosition = adapterPosition
            val context = itemView.context
            val cacheKey = "preview_$uri"

            // 先查缓存
            val cached = ThumbnailCache.get(cacheKey)
            if (cached != null) {
                imageView.setImageBitmap(cached)
                return
            }

            loadJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    ImageCompressor.decodeSampledBitmap(
                        context, uri,
                        targetWidth = PREVIEW_TARGET_SIZE,
                        targetHeight = PREVIEW_TARGET_SIZE
                    )
                }
                if (bitmap != null) {
                    ThumbnailCache.put(cacheKey, bitmap)
                    // 校验：异步完成时 ViewHolder 仍绑定同一位置
                    if (adapterPosition == boundPosition) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }
        }

        fun recycle() {
            loadJob?.cancel()
            imageView.setImageDrawable(null)
        }
    }
}
