package com.example.camerakitnative

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.snap.camerakit.lenses.LensesComponent

class LensesAdapter(
    private val onLensSelected: (LensesComponent.Lens) -> Unit
) : ListAdapter<LensesComponent.Lens, LensesAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var selectedPosition = -1

    fun select(lens: LensesComponent.Lens) {
        val position = currentList.indexOf(lens)
        if (position != -1) {
            val previousSelectedPosition = selectedPosition
            selectedPosition = position
            notifyItemChanged(previousSelectedPosition)
            notifyItemChanged(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lens, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val lens = getItem(position)
        holder.bind(lens, selectedPosition == position)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameText: TextView = view.findViewById(R.id.lens_name_text)
        private val iconView: ImageView = view.findViewById(R.id.lens_icon_placeholder) as ImageView

        fun bind(lens: LensesComponent.Lens, isSelected: Boolean) {
            nameText.text = lens.name
            
            // Load lens icon using Glide as seen in the sample
            val iconUri = lens.icons.find { it is LensesComponent.Lens.Media.Image.Webp }?.uri
            Glide.with(itemView)
                .load(iconUri)
                .placeholder(android.R.drawable.btn_default)
                .into(iconView)
            
            itemView.setOnClickListener {
                onLensSelected(lens)
            }
            
            // Visual feedback for selection
            itemView.alpha = if (isSelected) 1.0f else 0.6f
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LensesComponent.Lens>() {
            override fun areItemsTheSame(oldItem: LensesComponent.Lens, newItem: LensesComponent.Lens) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: LensesComponent.Lens, newItem: LensesComponent.Lens) =
                oldItem == newItem
        }
    }
}
