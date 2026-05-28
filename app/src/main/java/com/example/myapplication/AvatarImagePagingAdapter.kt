package com.example.myapplication

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import java.io.File

class AvatarImagePagingAdapter(
    private val context: Context,
    private val setName: String,
    private val onItemClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : PagingDataAdapter<String, AvatarImagePagingAdapter.ViewHolder>(DIFF_CALLBACK) {
    
    private var isSelectionMode = false
    private val selectedImages = mutableSetOf<String>()
    
    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
                return oldItem == newItem
            }
            
            override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
                return oldItem == newItem
            }
        }
    }
    
    fun setSelectionMode(enabled: Boolean) {
        isSelectionMode = enabled
        if (!enabled) {
            selectedImages.clear()
        }
        notifyDataSetChanged()
    }
    
    fun toggleSelection(imageName: String) {
        if (selectedImages.contains(imageName)) {
            selectedImages.remove(imageName)
        } else {
            selectedImages.add(imageName)
        }
        
        val position = snapshot().items.indexOf(imageName)
        if (position >= 0) {
            notifyItemChanged(position)
        }
    }
    
    fun clearSelection() {
        selectedImages.clear()
        notifyDataSetChanged()
    }
    
    fun getSelectedCount() = selectedImages.size
    
    fun getSelectedImages() = selectedImages.toList()
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_avatar_image, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val imageName = getItem(position)
        if (imageName != null) {
            holder.bind(imageName)
        }
    }
    
    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imageView: ImageView = view.findViewById(R.id.ivAvatar)
        private val btnDelete: View = view.findViewById(R.id.btnDelete)
        private val selectionOverlay: View = view.findViewById(R.id.selectionOverlay)
        
        private val glideOptions = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .placeholder(R.drawable.ic_launcher_foreground)
            .error(R.drawable.ic_launcher_foreground)
        
        fun bind(imageName: String) {
            val dir = AvatarImageManager.getAvatarDirectory(context, setName)
            val file = File(dir, "$imageName.png")
            
            Glide.with(context)
                .load(file)
                .apply(glideOptions)
                .into(imageView)
            
            val isSelected = selectedImages.contains(imageName)
            selectionOverlay.visibility = if (isSelectionMode && isSelected) View.VISIBLE else View.GONE
            
            btnDelete.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
            
            itemView.setOnClickListener {
                onItemClick(imageName)
            }
            
            btnDelete.setOnClickListener {
                onDeleteClick(imageName)
            }
        }
    }
}
