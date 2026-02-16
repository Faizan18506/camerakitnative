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
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.snap.camerakit.lenses.LensesComponent

class LensesAdapter(
    private val onLensSelected: (LensesComponent.Lens) -> Unit
) : ListAdapter<LensesComponent.Lens, LensesAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var selectedPosition = -1

    fun select(lens: LensesComponent.Lens) {
        val position = currentList.indexOf(lens)
        if (position != -1) {
            val prev = selectedPosition
            selectedPosition = position
            notifyItemChanged(prev)
            notifyItemChanged(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lens, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), selectedPosition == position)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameText: TextView = view.findViewById(R.id.lens_name_text)
        private val iconView: ImageView = view.findViewById(R.id.lens_icon_placeholder)

        fun bind(lens: LensesComponent.Lens, isSelected: Boolean) {
            nameText.text = lens.name
            
            // Apply circle crop to lens icons
            val iconUri = lens.icons.find { it is LensesComponent.Lens.Media.Image.Webp }?.uri
            Glide.with(itemView)
                .load(iconUri)
                .transform(CircleCrop()) // Makes the icons circular
                .placeholder(android.R.drawable.btn_default)
                .into(iconView)
            
            itemView.setOnClickListener {
                onLensSelected(lens)
            }
            
            // Smooth look for selected/unselected items
            if (isSelected) {
                itemView.scaleX = 1.15f
                itemView.scaleY = 1.15f
                itemView.alpha = 1.0f
                nameText.visibility = View.VISIBLE
            } else {
                itemView.scaleX = 0.9f
                itemView.scaleY = 0.9f
                itemView.alpha = 0.7f
                nameText.visibility = View.INVISIBLE // Hide names for unselected for cleaner look
            }
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
