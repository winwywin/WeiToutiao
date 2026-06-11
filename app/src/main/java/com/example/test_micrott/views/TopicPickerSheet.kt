package com.example.test_micrott.views

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.example.test_micrott.models.TopicItem
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 话题选择器 BottomSheet 委托。
 *
 * 从 MainActivity 提取，独立管理：
 * - BottomSheet 的创建 / 展示 / 关闭
 * - 热门话题标签流式布局（3 列，嵌套 LinearLayout 手动分行）
 * - 自定义话题输入框 + "插入" 按钮
 *
 * @param activity   宿主 Activity
 * @param onTopicPicked 用户选中话题（热门标签点击 或 自定义输入）→ 回调给 MainActivity 处理插入
 * @param onDismissed   BottomSheet 关闭 → 通知 ViewModel
 */
class TopicPickerSheet(
    private val activity: Activity,
    private val onTopicPicked: (String) -> Unit,
    private val onDismissed: () -> Unit
) {
    private var bottomSheet: BottomSheetDialog? = null

    /** 展示话题选择器。若已展示则跳过。 */
    fun show(topics: List<TopicItem>) {
        if (bottomSheet?.isShowing == true) return

        val sheet = BottomSheetDialog(activity)
        bottomSheet = sheet

        val container = buildContent(topics, sheet)
        sheet.setContentView(container)
        sheet.setOnDismissListener {
            bottomSheet = null
            onDismissed()
        }
        sheet.show()
    }

    /** 关闭话题选择器 */
    fun dismiss() {
        bottomSheet?.dismiss()
        bottomSheet = null
    }

    // ========================================================================
    // 内容构建
    // ========================================================================

    private fun buildContent(topics: List<TopicItem>, sheet: BottomSheetDialog): LinearLayout {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        // 标题
        container.addView(TextView(activity).apply {
            text = "热门话题"
            setTextColor("#1A1A1A".toColorInt())
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })

        // 话题标签流式布局
        container.addView(buildChipsRow(topics, sheet))

        // 分隔线
        container.addView(View(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            )
            setBackgroundColor("#E5E5E5".toColorInt())
        })

        // 自定义输入区域
        container.addView(TextView(activity).apply {
            text = "或输入自定义话题"
            setTextColor("#999999".toColorInt())
            textSize = 13f
            setPadding(0, dp(12), 0, dp(8))
        })

        container.addView(buildCustomInput(sheet))

        return container
    }

    private fun buildChipsRow(topics: List<TopicItem>, sheet: BottomSheetDialog): LinearLayout {
        val chipHeight = dp(36)
        val chipPaddingH = dp(14)
        val chipPaddingV = dp(8)
        val chipMargin = dp(8)
        val maxRowWidth = dp(280)

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        var currentRow: LinearLayout? = null
        var currentRowWidth = 0

        topics.forEach { topic ->
            val chip = TextView(activity).apply {
                text = topic.displayText
                setTextColor("#2A62FF".toColorInt())
                textSize = 13f
                setPadding(chipPaddingH, chipPaddingV, chipPaddingH, chipPaddingV)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), "#2A62FF".toColorInt())
                    setColor(Color.TRANSPARENT)
                }
                setOnClickListener {
                    onTopicPicked(topic.name)
                    sheet.dismiss()
                }

                val paint = this.paint
                val textWidth = paint.measureText(topic.displayText).toInt()
                val chipWidth = textWidth + chipPaddingH * 2 + chipMargin
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, chipHeight
                )
                tag = chipWidth
            }

            val estimatedWidth = chip.tag as Int
            if (currentRow == null || currentRowWidth + estimatedWidth > maxRowWidth) {
                currentRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, 0, 0, chipMargin)
                }
                rootLayout.addView(currentRow)
                currentRowWidth = 0
            }

            val chipLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, chipHeight
            ).apply { setMargins(0, 0, chipMargin, 0) }
            currentRow.addView(chip, chipLp)
            currentRowWidth += estimatedWidth
        }

        return rootLayout
    }

    private fun buildCustomInput(sheet: BottomSheetDialog): LinearLayout {
        val inputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val inputField = EditText(activity).apply {
            hint = "输入话题名称"
            setHintTextColor("#CCCCCC".toColorInt())
            setTextColor("#1A1A1A".toColorInt())
            textSize = 14f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), "#E5E5E5".toColorInt())
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(inputField)

        val confirmBtn = TextView(activity).apply {
            text = "插入"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor("#2A62FF".toColorInt())
            }
            val btnLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(10), 0, 0, 0) }
            layoutParams = btnLp

            setOnClickListener {
                val customTopic = inputField.text.toString().trim()
                if (customTopic.isNotEmpty()) {
                    onTopicPicked(customTopic)
                    sheet.dismiss()
                }
            }
        }
        inputRow.addView(confirmBtn)

        return inputRow
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
