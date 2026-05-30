package com.example.test_micrott.view

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.test_micrott.R

/**
 * Day 2 沙盒隔离演练场 (纯 MVC 裸写探路)
 * 核心任务：
 * 1. 验证跨分辨率抗拉伸高保真布局
 * 2. 调通 Android 13+ 原生 PhotoPicker 多图选择并打印日志
 * 3. 调通富文本动态局部蓝色高亮插入与光标精准定位
 * 4. 动态控制右上角发布按钮的置灰/高亮状态
 */
class MvcTestActivity : AppCompatActivity() {

    private val tag = "PhotoSandbox"

    // 声明布局中的核心控件
    private lateinit var etEditor: EditText
    private lateinit var btnPublish: Button
    private lateinit var barTopic: TextView
    private lateinit var barPhoto: TextView
    // day3：把今天新加的网格控件和它的干活适配器塞进声明区
    private lateinit var rvImages: RecyclerView
    private lateinit var imageGridAdapter: ImageGridAdapter


    // day2:用于暂存当前选中的图片 Uri 列表（沙盒阶段临时记录）
//    private var selectedImageUris = listOf<android.net.Uri>()
    // day3：把你昨天的 listOf() 升级为 ArrayList()
    // 只有换成可变的 ArrayList，后面点击右上角小红叉时才能执行 remove 删图！
    private var selectedImageUris = ArrayList<Uri>()

    // day2:【核心 API 攻坚 1】：注册现代原生相册选择器回调
    // 拒绝过期的 startActivityForResult，利用最新的 ActivityResultContracts 机制
    private val pickMultipleMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9), // 题目硬性要求：最大上限 9 张
    ) { uris ->
        if (uris.isNotEmpty()) {
            Log.d(tag, "==== 今日头条沙盒测试：本次新选中图片 ${uris.size} 张 ====")

            // day3:1. 【核心修正】：不要直接赋值，而是用循环“增量追加 + 去重”
            for (uri in uris) {
                // 如果全局列表里还没这张图，才加进去，防止重复
                if (!selectedImageUris.contains(uri)) {
                    selectedImageUris.add(uri)
                }
            }
            // 2. 【上限硬拦截】：如果追加完超过了 9 张，强行物理切除多余的
            if (selectedImageUris.size > 9) {
                Log.w(tag, "警告：总数超过9张！强行截断，只保留前9张")
                while (selectedImageUris.size > 9) {
                    selectedImageUris.removeAt(selectedImageUris.size - 1)
                }
            }
            // 3. 关键一笔：选图追加/截断完成后，派发给适配器，让界面长出九宫格！
            imageGridAdapter.updateData(selectedImageUris)
            // 打印最新累积的图片日志，方便汇报时在 Logcat 里向导师展示
            selectedImageUris.forEachIndexed { index, uri ->
                Log.d(tag, "当前图片总池[$index] Uri: $uri")
            }
            Log.d(tag, "======================================")
            //day2:把uris传入内部，输出图片的uris
//            selectedImageUris = uris
//            Log.d(tag, "==== 今日头条沙盒测试：成功选中图片 ====")
//            uris.forEachIndexed { index, uri ->
//                // 在 Logcat 里打印出绝对清晰的路径，完成今日数据拉通检验
//                Log.d(tag, "图片[$index] Uri 路径: $uri")
//            }
//            Log.d(tag, "======================================")

        } else {
            Log.d(tag, "用户取消了相册选择")
        }
        // 每次选图状态改变，重新检查一次发布按钮是否需要激活
        updatePublishButtonState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 绑定昨天搭建的高保真抗拉伸 XML 布局
        setContentView(R.layout.activity_mvc_test)

        // day2:初始化控件
        etEditor = findViewById(R.id.etEditor)
        btnPublish = findViewById(R.id.btnPublish)
        barTopic = findViewById(R.id.barTopic)
        barPhoto = findViewById(R.id.barPhoto)
        //day3：绑定 XML 里的网格控件
        rvImages = findViewById(R.id.rvImages)

        // day3：配置工业级 3 列网格，并把今天新加的适配器实例化
        rvImages.layoutManager = GridLayoutManager(this, 3)
        imageGridAdapter = ImageGridAdapter()

        // day3：焊死接口回调，负责处理九宫格里的“点击加号”和“点击叉叉”
        imageGridAdapter.setListeners(
            onAddClickListener = {
                // 点击尾部加号，直接调用你昨天写好的选图 API
                pickMultipleMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onDeleteClickListener = { position ->
                // 点击小叉，从数组里移除它，并通知九宫格局部回流刷新
                if (position in 0..<selectedImageUris.size) {
                    selectedImageUris.removeAt(position)
                    imageGridAdapter.updateData(selectedImageUris)
                    updatePublishButtonState() // 顺便联动更新右上角按钮状态
                }
            },
            onMoveListener = { _, _ -> /* MVC 沙盒暂不处理拖拽 */ },
        )
        rvImages.adapter = imageGridAdapter // 给控件穿上适配器外衣

        // 监听输入框文本变化，实时控场右上角发布按钮的状态（对齐 A5 校验）
        etEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePublishButtonState()
            }
        })

        // 【功能交互 1】：点击【📷 照片】按钮，唤起原生不弹窗隐私合规相册
        barPhoto.setOnClickListener {
            pickMultipleMedia.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }

        // 【功能交互 2】：点击【# 话题】按钮，在光标处插入蓝色高亮富文本，并防错位控强推光标
        barTopic.setOnClickListener {
            insertTopicIntoEditor()
        }
        // day3：在 onCreate 的最末尾，把今天最核心的話题删除拦截引擎启动起来
        setupTopicTokenGuard()
    }

    /**
     * 富文本核心算法：在当前光标处无错位插入高亮变色文本（对齐 A3 核心）
     */
    private fun insertTopicIntoEditor() {
        val topicText = " #请输入话题# "
        // 1. 获取当前输入框中的文本对象
        val editable = etEditor.text ?: return

        // 2. 获取当前光标位置。如果没有光标，默认插入到文字最后面面
        var start = etEditor.selectionStart
        var end = etEditor.selectionEnd
        if (start < 0) {
            start = editable.length
            end = editable.length
        }

        // 3. 构建高亮富文本对象
        val spannableStringBuilder = SpannableStringBuilder(topicText)
        spannableStringBuilder.setSpan(
            ForegroundColorSpan("#2A62FF".toColorInt()), // 今日头条官方微头条经典蓝色
            0,
            topicText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // 4. 执行替换插入：把光标处的文本（如果是选中的一段话则会被覆盖）替换成高亮话题
        editable.replace(start, end, spannableStringBuilder)

        // 5. 【防错位核心】：计算新光标的绝对位置，强行推到插入话题的右侧边缘
        val newCursorPosition = start + topicText.length
        etEditor.setSelection(newCursorPosition)
    }

    /**
     * 大厂发布按钮状态机控制逻辑（对齐 A5 / A6 标准）
     * 输入框有字，或者选了图，按钮瞬间变亮红激活；否则保持死灰置残
     */
    private fun updatePublishButtonState() {
        val hasText = !etEditor.text.isNullOrEmpty()
        val hasImages = selectedImageUris.isNotEmpty()
        val canPublish = hasText || hasImages

        btnPublish.isEnabled = canPublish
        if (canPublish) {
            // 激活状态：大厂高亮红（这里暂用标准的 Android 红色，后续可进修视觉）
            btnPublish.setBackgroundColor("#F85149".toColorInt())
        } else {
            // 置残状态：工业死灰色
            btnPublish.setBackgroundColor("#A8A8A8".toColorInt())
        }
    }
    // day3：把这个话题守卫引擎，当作新方法加进 Activity 肚子里
    private fun setupTopicTokenGuard() {
        // A. 拦截退格键（KEYCODE_DEL）实现一键整块销毁
        etEditor.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                val start = etEditor.selectionStart
                val end = etEditor.selectionEnd
                if (start == end) {
                    val editable = etEditor.text
                    val spans = editable.getSpans(start, start, ForegroundColorSpan::class.java)
                    for (span in spans) {
                        val spanEnd = editable.getSpanEnd(span)
                        if (start == spanEnd) { // 光标卡在话题最右边的 # 后面
                            val spanStart = editable.getSpanStart(span)
                            editable.delete(spanStart, spanEnd) // 顺位切除
                            return@setOnKeyListener true // 拦截拦截
                        }
                    }
                }
            }
            false
        }

        // B. 拦截光标触摸（防止手指戳进话题中间，实行两极边缘磁吸）
        etEditor.setOnClickListener {
            val position = etEditor.selectionStart
            val editable = etEditor.text ?: return@setOnClickListener
            val spans = editable.getSpans(position, position, ForegroundColorSpan::class.java)
            for (span in spans) {
                val start = editable.getSpanStart(span)
                val end = editable.getSpanEnd(span)
                if (position in (start + 1)..<end) {
                    if (position < (start + end) / 2) etEditor.setSelection(start) else etEditor.setSelection(end)
                    break
                }
            }
        }
        // C.:当用户长按试图滑动选中、或者系统尝试改变光标选择区间时，强制将非法光标弹回边界
        etEditor.setAccessibilityDelegate(object : android.view.View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: android.view.View, eventType: Int) {
                super.sendAccessibilityEvent(host, eventType)
                // TYPE_VIEW_TEXT_SELECTION_CHANGED 代表系统的光标或选择区域发生了变化（长按拖拽时必发）
                if (eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                    val start = etEditor.selectionStart
                    val end = etEditor.selectionEnd
                    val editable = etEditor.text ?: return

                    // 检查当前光标（或选择区边界）有没有不幸“陷进”话题内部
                    val spans = editable.getSpans(start, end, ForegroundColorSpan::class.java)
                    for (span in spans) {
                        val spanStart = editable.getSpanStart(span)
                        val spanEnd = editable.getSpanEnd(span)

                        // 1. 如果是纯光标闪烁（start == end）且陷在了话题肚子里
                        if (start == end && (start in (spanStart+1)..<spanEnd)) {
                            if (start < (spanStart + spanEnd) / 2) {
                                etEditor.setSelection(spanStart) // 强行吸附到左边
                            } else {
                                etEditor.setSelection(spanEnd)   // 强行吸附到右边
                            }
                            break
                        }

                        // 2. 如果是长按拖出了一段文本（start != end），且选区的边界斩断了话题
                        // 比如选区偷偷包住了话题的一部分，大厂规范也是不允许的，强行扩大选区包裹整个话题
                        if (start != end) {
                            var newStart = start
                            var newEnd = end
                            if (start in (spanStart+1)..<spanEnd) newStart = spanStart
                            if (end in (spanStart+1)..<spanEnd) newEnd = spanEnd
                            if ((newStart != start) || (newEnd != end)) {
                                etEditor.setSelection(newStart, newEnd) // 强行校正选区
                                break
                            }
                        }
                    }
                }
            }
        })
    }
}

// Day 5: ImageGridAdapter 已独立至 ImageGridAdapter.kt，移除旧版避免编译冲突

