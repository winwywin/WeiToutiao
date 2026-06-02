package com.example.test_micrott.views

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.test_micrott.R
import com.example.test_micrott.data.DraftManager
import com.example.test_micrott.data.DraftSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 草稿箱列表页。
 *
 * 展示所有历史草稿，支持：
 * - 点击 → 恢复草稿到发布页
 * - 管理模式 → 删除草稿
 */
class DraftListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESTORED_DRAFT_ID = "restored_draft_id"
    }

    private lateinit var draftManager: DraftManager
    private lateinit var adapter: DraftListAdapter
    private var isManageMode = false
    private var drafts: List<DraftSummary> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_draft_list)

        draftManager = DraftManager(applicationContext)

        findViewById<TextView>(R.id.btn_back).setOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.btn_manage).setOnClickListener {
            toggleManageMode()
        }

        adapter = DraftListAdapter(
            onItemClick = { draft ->
                if (isManageMode) {
                    deleteDraft(draft)
                } else {
                    restoreDraft(draft)
                }
            }
        )

        findViewById<RecyclerView>(R.id.rv_drafts).apply {
            layoutManager = LinearLayoutManager(this@DraftListActivity)
            adapter = this@DraftListActivity.adapter
        }

        loadDrafts()
    }

    private fun loadDrafts() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                draftManager.getAllDrafts()
            }
            drafts = list
            adapter.submitList(list)

            findViewById<View>(R.id.rv_drafts).visibility =
                if (list.isEmpty()) View.GONE else View.VISIBLE
            findViewById<View>(R.id.tv_empty).visibility =
                if (list.isEmpty()) View.VISIBLE else View.GONE
            findViewById<View>(R.id.tv_footer).visibility =
                if (list.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun restoreDraft(draft: DraftSummary) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_RESTORED_DRAFT_ID, draft.id)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun deleteDraft(draft: DraftSummary) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                draftManager.deleteDraft(draft.id)
            }
            loadDrafts()
        }
    }

    private fun toggleManageMode() {
        isManageMode = !isManageMode
        findViewById<TextView>(R.id.btn_manage).text = if (isManageMode) "完成" else "管理"
        adapter.setManageMode(isManageMode)
    }
}

/**
 * 草稿箱列表 Adapter。
 */
class DraftListAdapter(
    private val onItemClick: (DraftSummary) -> Unit,
) : RecyclerView.Adapter<DraftListAdapter.ViewHolder>() {

    private var drafts: List<DraftSummary> = emptyList()
    private var showDeleteButton: Boolean = false

    fun submitList(list: List<DraftSummary>) {
        drafts = list
        notifyDataSetChanged()
    }

    fun setManageMode(enabled: Boolean) {
        showDeleteButton = enabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_draft, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val draft = drafts[position]
        holder.bind(draft, showDeleteButton, onItemClick)
    }

    override fun getItemCount(): Int = drafts.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTextPreview: TextView = itemView.findViewById(R.id.tv_text_preview)
        private val tvStats: TextView = itemView.findViewById(R.id.tv_stats)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        private val btnDelete: TextView = itemView.findViewById(R.id.btn_delete)

        fun bind(
            draft: DraftSummary,
            showDelete: Boolean,
            onClick: (DraftSummary) -> Unit,
        ) {
            // 文本预览：有内容显示内容，无内容显示占位
            tvTextPreview.text = if (draft.textPreview.isNotEmpty()) {
                draft.textPreview
            } else {
                "[空文本]"
            }

            tvStats.text = draft.toStatsText()
            tvTime.text = draft.toRelativeTime()

            btnDelete.visibility = if (showDelete) View.VISIBLE else View.GONE
            btnDelete.setOnClickListener { onClick(draft) }

            itemView.setOnClickListener {
                if (!showDelete) {
                    onClick(draft)
                }
            }
        }
    }
}
