package com.example.test_micrott.view

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.test_micrott.R
import java.util.Collections

/**
 * MVI 主项目九宫格适配器
 * 职责：纯渲染，不持有业务状态，数据由 PublishState.selectedImages 驱动
 * Day 7 升级：支持拖拽排序 + ViewHolder 类型暴露（供 ItemTouchHelper 过滤加号）
 */
class ImageGridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_IMAGE = 0
        const val TYPE_ADD_BUTTON = 1
    }

    private val MAX_IMAGE_COUNT = 9

    private var mSelectedImages = ArrayList<Uri>()
    private var mAddListener: (() -> Unit)? = null
    private var mDeleteListener: ((Int) -> Unit)? = null
    private var mOnMoveListener: ((Int, Int) -> Unit)? = null

    fun setListeners(
        onAddClickListener: () -> Unit,
        onDeleteClickListener: (Int) -> Unit,
        onMoveListener: (Int, Int) -> Unit
    ) {
        this.mAddListener = onAddClickListener
        this.mDeleteListener = onDeleteClickListener
        this.mOnMoveListener = onMoveListener
    }

    fun updateData(images: List<Uri>) {
        mSelectedImages = ArrayList(images)
        notifyDataSetChanged()
    }

    fun getImages(): List<Uri> = mSelectedImages

    /**
     * 拖拽排序：交换数据源中的两个位置，并通知回调 → ViewModel
     */
    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in mSelectedImages.indices || toPosition !in mSelectedImages.indices) return
        if (fromPosition == toPosition) return

        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(mSelectedImages, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(mSelectedImages, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        mOnMoveListener?.invoke(fromPosition, toPosition)
    }

    override fun getItemCount(): Int {
        return if (mSelectedImages.size < MAX_IMAGE_COUNT) mSelectedImages.size + 1 else MAX_IMAGE_COUNT
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == mSelectedImages.size && mSelectedImages.size < MAX_IMAGE_COUNT) {
            TYPE_ADD_BUTTON
        } else {
            TYPE_IMAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_ADD_BUTTON) {
            AddViewHolder(inflater.inflate(R.layout.item_publish_add, parent, false))
        } else {
            ImageViewHolder(inflater.inflate(R.layout.item_publish_image, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AddViewHolder) {
            holder.itemView.setOnClickListener { mAddListener?.invoke() }
        } else if (holder is ImageViewHolder) {
            holder.imageView.setImageURI(mSelectedImages[position])
            holder.btnDelete.setOnClickListener { mDeleteListener?.invoke(position) }
        }
    }

    class AddViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
    class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.iv_thumnail)
        val btnDelete: View = itemView.findViewById(R.id.view_delete_fork)
    }
}
