package com.example.test_micrott.views

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.graphics.toColorInt

/**
 * 文字颜色选择器 — PopupWindow 5×2 色块网格。
 *
 * 10 个预设颜色（排除 #2A62FF 避免与话题/提及颜色混淆）。
 * 包含黑色 #000000，用户选中后可让文字变回默认黑色。
 * 点击色块 → 回调颜色 Int → 自动 dismiss。
 *
 * 布局改用双层 LinearLayout（垂直笼 + 两个水平行），
 * 彻底规避 GridLayout 在 WRAP_CONTENT 下第二行被截断的问题。
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
        val density = context.resources.displayMetrics.density
        val cellSizeDp = (48 * density).toInt()
        val cellMarginDp = (8 * density).toInt()
        val paddingDp = (16 * density).toInt()

        val cellSize = cellSizeDp
        val cellMargin = cellMarginDp

        fun createCircle(color: Int): View {
            return View(context).apply {
                val params = LinearLayout.LayoutParams(cellSize, cellSize).apply {
                    setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
                }
                layoutParams = params
                background = GradientDrawable().apply {
                    setColor(color)
                    shape = GradientDrawable.OVAL
                    val strokeColor = if (color == Color.BLACK || color == "#333333".toColorInt()) {
                        "#AAAAAA".toColorInt()
                    } else {
                        Color.WHITE
                    }
                    setStroke((2 * density).toInt(), strokeColor)
                }
                setOnClickListener {
                    onColorPicked(color)
                    popup.dismiss()
                }
            }
        }

        // 第一行 5 个
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            colors.take(5).forEach { addView(createCircle(it)) }
        }

        // 第二行 5 个
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            colors.drop(5).forEach { addView(createCircle(it)) }
        }

        // 垂直笼：两行
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingDp, paddingDp, paddingDp, paddingDp)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 12f * density
                setStroke(1, "#DDDDDD".toColorInt())
            }
            addView(row1)
            addView(row2)
        }

        popup = PopupWindow(
            root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 8f
            isOutsideTouchable = true
        }
    }

    fun show(anchor: View) {
        // 先强制测量内容视图，避免 WRAP_CONTENT 下 measuredHeight 为 0
        popup.contentView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupHeight = popup.contentView.measuredHeight
        val xOff = anchor.width / 2 - popup.contentView.measuredWidth / 2
        // 显示在锚点上方，yOff 为负
        popup.showAsDropDown(anchor, xOff, -anchor.height - popupHeight - 8)
    }
}
