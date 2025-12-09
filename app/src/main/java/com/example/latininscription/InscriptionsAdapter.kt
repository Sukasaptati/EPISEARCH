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
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class InscriptionsAdapter : RecyclerView.Adapter<InscriptionsAdapter.InscriptionViewHolder>() {

    private var items: List<Inscription> = emptyList()
    private var currentSearchTerms: List<String> = emptyList()

    fun submitList(newItems: List<Inscription>, searchTerms: List<String>) {
        this.items = newItems
        this.currentSearchTerms = searchTerms
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InscriptionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_inscription, parent, false)
        return InscriptionViewHolder(view)
    }

    override fun onBindViewHolder(holder: InscriptionViewHolder, position: Int) {
        holder.bind(items[position], currentSearchTerms)
    }

    override fun getItemCount(): Int = items.size

    class InscriptionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvHeader: TextView = itemView.findViewById(R.id.tvHeader)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val tvLatinText: TextView = itemView.findViewById(R.id.tvLatinText)
        private val tvReferences: TextView = itemView.findViewById(R.id.tvReferences)

        fun bind(item: Inscription, searchTerms: List<String>) {
            tvHeader.text = "${item.id}  |  ${item.location}"
            
            if (item.date.isNotBlank()) {
                tvDate.visibility = View.VISIBLE
                tvDate.text = item.date
            } else {
                tvDate.visibility = View.GONE
            }

            val fullText = item.text
            val spannable = SpannableString(fullText)

            for (term in searchTerms) {
                if (term.isBlank()) continue
                try {
                    val regex = Regex(term, RegexOption.IGNORE_CASE)
                    val matches = regex.findAll(fullText)
                    for (match in matches) {
                        spannable.setSpan(ForegroundColorSpan(Color.RED), match.range.first, match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        spannable.setSpan(StyleSpan(Typeface.BOLD), match.range.first, match.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } catch (e: Exception) {}
            }
            tvLatinText.text = spannable

            val refs = listOf(item.ref1, item.ref2).filter { it.isNotBlank() }.joinToString(" | ")
            if (refs.isNotBlank()) {
                tvReferences.visibility = View.VISIBLE
                tvReferences.text = refs
            } else {
                tvReferences.visibility = View.GONE
            }
        }
    }
}