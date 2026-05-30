package com.example.test_micrott.view

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.test_micrott.R

/**
 * 自定义相册网格适配器
 *
 * 每项显示缩略图 + 选中遮罩 + 勾选标记。
 * 使用 MediaStore.Images.Thumbnails 加载缩略图（快速、低内存）。
 */
class GalleryPickerAdapter(
    private val onToggle: (Int) -> Unit,
) : RecyclerView.Adapter<GalleryPickerAdapter.PhotoViewHolder>() {

    private val items = mutableListOf<GalleryPhoto>()

    fun submitList(newItems: List<GalleryPhoto>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItem(position: Int): GalleryPhoto = items[position]

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photo = items[position]

        // 直接用 MediaStore URI 加载缩略图（GridLayoutManager 回收机制保证内存可控）
        holder.imageView.setImageURI(photo.uri)

        // 选中状态
        val isSelected = photo.isSelected
        holder.overlay.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.checkMark.visibility = if (isSelected) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            onToggle(position)
        }
    }

    class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.iv_photo)
        val overlay: View = itemView.findViewById(R.id.view_selected_overlay)
        val checkMark: ImageView = itemView.findViewById(R.id.iv_check)
    }
}

/**
 * 相册中单张照片的数据模型
 */
data class GalleryPhoto(
    val mediaId: Long,
    val uri: Uri,
    var isSelected: Boolean = false,
)
