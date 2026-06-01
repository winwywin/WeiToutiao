package com.example.test_micrott.view

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 强制正方形的 FrameLayout。
 * onMeasure 时将高度设为宽度，确保 GridLayoutManager 中每个格子是正方形。
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
