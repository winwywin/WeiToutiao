package com.example.test_micrott.view

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.PopupWindow
import androidx.core.graphics.toColorInt

/**
 * 文字颜色选择器 — PopupWindow 5×2 色块网格。
 *
 * 10 个预设颜色（排除 #2A62FF 避免与话题/提及颜色混淆）。
 * 点击色块 → 回调颜色 Int → 自动 dismiss。
 */
class ColorPickerPopup(
    context: Context,
    private val onColorPicked: (Int) -> Unit
) {
    private val colors = listOf(
        "#FF0000".toColorInt(),  // 红
        "#FF6600".toColorInt(),  // 橙
        "#FFCC00".toColorInt(),  // 金
        "#33CC33".toColorInt(),  // 绿
        "#0066FF".toColorInt(),  // 蓝
        "#9933FF".toColorInt(),  // 紫
        "#FF3399".toColorInt(),  // 粉
        "#666666".toColorInt(),  // 灰
        "#333333".toColorInt(),  // 深灰
        "#000000".toColorInt(),  // 黑
    )

    private val popup: PopupWindow

    init {
        val grid = GridLayout(context).apply {
            columnCount = 5
            rowCount = 2
            setPadding(12, 12, 12, 12)
            // 背景: 白底 + 圆角 + 阴影
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 12f
                setStroke(1, "#DDDDDD".toColorInt())
            }
        }

        val cellSize = 44
        val cellMargin = 6

        colors.forEach { color ->
            val circle = View(context).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cellSize
                    height = cellSize
                    setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
                }
                background = GradientDrawable().apply {
                    setColor(color)
                    shape = GradientDrawable.OVAL
                    setStroke(2, Color.WHITE)
                }
                setOnClickListener {
                    onColorPicked(color)
                    popup.dismiss()
                }
            }
            grid.addView(circle)
        }

        popup = PopupWindow(
            grid,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true  // focusable = true → outside touch dismiss
        ).apply {
            elevation = 8f
            isOutsideTouchable = true
        }
    }

    fun show(anchor: View) {
        // 锚定到按钮上方居中
        val xOff = anchor.width / 2 - popup.contentView.measuredWidth / 2
        popup.showAsDropDown(anchor, xOff, -anchor.height - popup.height - 8)
    }
}
