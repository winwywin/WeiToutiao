package com.example.test_micrott.views

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.test_micrott.R

/**
 * 表情网格适配器：每个格子就是一个 emoji 字符
 */
class EmojiGridAdapter(
    private val emojis: List<String>,
    private val onEmojiClick: (String) -> Unit,
) : RecyclerView.Adapter<EmojiGridAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_emoji, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val emoji = emojis[position]
        holder.tvEmoji.text = emoji
        holder.tvEmoji.setOnClickListener { onEmojiClick(emoji) }
    }

    override fun getItemCount(): Int = emojis.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvEmoji: TextView = itemView.findViewById(R.id.tv_emoji)
    }
}
