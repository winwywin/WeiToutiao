package com.example.test_micrott.view

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.test_micrott.R

/**
 * MVI 主项目九宫格适配器
 * 职责：纯渲染，不持有业务状态，数据由 PublishState.selectedImages 驱动
 * 接口对齐 MVC 沙盒的 ImageGridAdapter，支持加号按钮和删除按钮回调
 */
class ImageGridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val TYPE_IMAGE = 0
    private val TYPE_ADD_BUTTON = 1
    private val MAX_IMAGE_COUNT = 9

    private var mSelectedImages = ArrayList<Uri>()
    private var mAddListener: (() -> Unit)? = null
    private var mDeleteListener: ((Int) -> Unit)? = null

    fun setListeners(onAddClickListener: () -> Unit, onDeleteClickListener: (Int) -> Unit) {
        this.mAddListener = onAddClickListener
        this.mDeleteListener = onDeleteClickListener
    }

    fun updateData(images: List<Uri>) {
        mSelectedImages = ArrayList(images)
        notifyDataSetChanged()
    }

    fun getImages(): List<Uri> = mSelectedImages

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
