package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

class AvatarUploadActivity : AppCompatActivity() {
    
    private lateinit var currentSetName: String
    private lateinit var adapter: AvatarImageAdapter
    
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImageSelected(it) }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_avatar_upload)
        
        currentSetName = intent.getStringExtra(EXTRA_SET_NAME) ?: AvatarAssets.DIR_CUSTOM_SET_1
        
        setupViews()
        loadImages()
    }
    
    private fun setupViews() {
        findViewById<TextView>(R.id.tvSetTitle).text = 
            if (currentSetName == AvatarAssets.DIR_CUSTOM_SET_1) "图片集 1" else "图片集 2"
        
        findViewById<Button>(R.id.btnAddImage).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        
        findViewById<Button>(R.id.btnClearAll).setOnClickListener {
            showClearConfirmDialog()
        }
        
        val recyclerView = findViewById<RecyclerView>(R.id.rvImages)
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        adapter = AvatarImageAdapter(currentSetName) { imageName ->
            showDeleteConfirmDialog(imageName)
        }
        recyclerView.adapter = adapter
    }
    
    private fun loadImages() {
        val imageNames = AvatarImageManager.getAvailableImageNames(this, currentSetName)
        adapter.updateImages(imageNames)
    }
    
    private fun handleImageSelected(uri: Uri) {
        // 获取当前已有的图片数量，自动生成下一个序号
        val existingImages = AvatarImageManager.getAvailableImageNames(this, currentSetName)
        val nextIndex = getNextFrameIndex(existingImages)
        val imageName = "dancer_single$nextIndex"
        
        val success = AvatarImageManager.saveAvatarImage(
            context = this,
            uri = uri,
            setName = currentSetName,
            imageName = imageName
        )
        
        if (success) {
            Toast.makeText(this, "图片上传成功: $imageName", Toast.LENGTH_SHORT).show()
            loadImages()
        } else {
            Toast.makeText(this, "图片上传失败", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun getNextFrameIndex(existingImages: List<String>): Int {
        // 从现有图片名称中提取序号
        val indices = existingImages.mapNotNull { name ->
            // 匹配 dancer_single1, dancer_single2 等格式
            val match = Regex("dancer_single(\\d+)").find(name)
            match?.groupValues?.get(1)?.toIntOrNull()
        }
        
        // 返回最大序号+1，如果没有图片则从1开始
        return (indices.maxOrNull() ?: 0) + 1
    }
    
    private fun showDeleteConfirmDialog(imageName: String) {
        AlertDialog.Builder(this)
            .setTitle("删除图片")
            .setMessage("确定要删除这张图片吗？")
            .setPositiveButton("删除") { _, _ ->
                deleteImage(imageName)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun deleteImage(imageName: String) {
        val file = AvatarImageManager.getAvatarDirectory(this, currentSetName)
            .resolve("$imageName.png")
        
        if (file.delete()) {
            Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show()
            loadImages()
        } else {
            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showClearConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("清空图片集")
            .setMessage("确定要删除所有图片吗？此操作不可恢复。")
            .setPositiveButton("清空") { _, _ ->
                clearAllImages()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun clearAllImages() {
        if (AvatarImageManager.deleteAvatarSet(this, currentSetName)) {
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
            loadImages()
        } else {
            Toast.makeText(this, "清空失败", Toast.LENGTH_SHORT).show()
        }
    }
    
    companion object {
        private const val EXTRA_SET_NAME = "set_name"
        
        fun createIntent(activity: Activity, setName: String): Intent {
            return Intent(activity, AvatarUploadActivity::class.java).apply {
                putExtra(EXTRA_SET_NAME, setName)
            }
        }
    }
}

class AvatarImageAdapter(
    private val setName: String,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<AvatarImageAdapter.ViewHolder>() {
    
    private var imageNames = listOf<String>()
    
    fun updateImages(names: List<String>) {
        imageNames = names
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_avatar_image, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(imageNames[position])
    }
    
    override fun getItemCount() = imageNames.size
    
    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imageView: ImageView = view.findViewById(R.id.ivAvatar)
        private val btnDelete: View = view.findViewById(R.id.btnDelete)
        
        fun bind(imageName: String) {
            val bitmap = AvatarImageManager.loadAvatarImage(
                itemView.context,
                setName,
                imageName
            )
            imageView.setImageBitmap(bitmap)
            
            btnDelete.setOnClickListener {
                onDeleteClick(imageName)
            }
        }
    }
}
