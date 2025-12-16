package com.example.latininscription

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.regex.Pattern

class InscriptionsAdapter(
    private val onItemLongClick: (String) -> Unit
) : ListAdapter<Inscription, InscriptionsAdapter.ViewHolder>(DiffCallback()) {

    private var highlightTerms: List<String> = emptyList()

    fun submitList(list: List<Inscription>, highlights: List<String>) {
        this.highlightTerms = highlights
        super.submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_inscription, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, highlightTerms, onItemLongClick)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvId: TextView = itemView.findViewById(R.id.tvId)
        private val tvLocation: TextView = itemView.findViewById(R.id.tvLocation)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val tvText: TextView = itemView.findViewById(R.id.tvText)
        private val tvRef: TextView = itemView.findViewById(R.id.tvRef)

        fun bind(item: Inscription, highlights: List<String>, onItemLongClick: (String) -> Unit) {
            tvId.text = item.id
            tvLocation.text = item.location
            tvDate.text = item.date
            tvDate.visibility = if (item.date.isBlank()) View.GONE else View.VISIBLE
            
            // Highlight Logic (Red & Bold)
            val spannable = SpannableString(item.text)
            for (term in highlights) {
                if (term.isBlank()) continue
                val p = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE)
                val m = p.matcher(item.text)
                while (m.find()) {
                    spannable.setSpan(ForegroundColorSpan(Color.RED), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(StyleSpan(Typeface.BOLD), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            tvText.text = spannable
            tvRef.text = "${item.ref1} ${item.ref2}"

            // Long Click triggers the popup in MainActivity
            itemView.setOnLongClickListener {
                onItemLongClick(item.text)
                true
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Inscription>() {
        override fun areItemsTheSame(oldItem: Inscription, newItem: Inscription) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Inscription, newItem: Inscription) = oldItem == newItem
    }
}