package com.example.myapplication

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AvatarUploadActivity : AppCompatActivity() {
    
    private lateinit var currentSetName: String
    private lateinit var adapter: AvatarImageAdapter
    private var isSelectionMode = false
    private var progressDialog: AlertDialog? = null
    
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImageSelected(it) }
    }
    
    private val pickMultipleImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            handleMultipleImagesSelected(uris)
        }
    }
    
    companion object {
        private const val EXTRA_SET_NAME = "set_name"
        private const val MAX_FILE_SIZE_MB = 5
        private const val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024
        
        fun createIntent(activity: Activity, setName: String): Intent {
            return Intent(activity, AvatarUploadActivity::class.java).apply {
                putExtra(EXTRA_SET_NAME, setName)
            }
        }
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
        
        findViewById<Button>(R.id.btnAddMultiple).setOnClickListener {
            pickMultipleImagesLauncher.launch("image/*")
        }
        
        findViewById<Button>(R.id.btnBatchDelete).setOnClickListener {
            toggleSelectionMode()
        }
        
        findViewById<Button>(R.id.btnClearAll).setOnClickListener {
            showClearConfirmDialog()
        }
        
        val recyclerView = findViewById<RecyclerView>(R.id.rvImages)
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        adapter = AvatarImageAdapter(
            context = this,
            setName = currentSetName,
            onItemClick = { imageName ->
                if (isSelectionMode) {
                    adapter.toggleSelection(imageName)
                    updateSelectionUI()
                }
            },
            onDeleteClick = { imageName ->
                if (!isSelectionMode) {
                    showDeleteConfirmDialog(imageName)
                }
            }
        )
        recyclerView.adapter = adapter
    }
    
    private fun toggleSelectionMode() {
        isSelectionMode = !isSelectionMode
        adapter.setSelectionMode(isSelectionMode)
        updateSelectionUI()
        
        if (!isSelectionMode) {
            adapter.clearSelection()
        }
    }
    
    private fun updateSelectionUI() {
        val btnBatchDelete = findViewById<Button>(R.id.btnBatchDelete)
        val selectedCount = adapter.getSelectedCount()
        
        if (isSelectionMode) {
            btnBatchDelete.text = if (selectedCount > 0) {
                "删除选中 ($selectedCount)"
            } else {
                "取消选择"
            }
            
            if (selectedCount > 0) {
                btnBatchDelete.setOnClickListener {
                    showBatchDeleteConfirmDialog()
                }
            } else {
                btnBatchDelete.setOnClickListener {
                    toggleSelectionMode()
                }
            }
        } else {
            btnBatchDelete.text = "批量删除"
            btnBatchDelete.setOnClickListener {
                toggleSelectionMode()
            }
        }
    }
    
    private fun loadImages() {
        val imageNames = AvatarImageManager.getAvailableImageNames(this, currentSetName)
        adapter.updateImages(imageNames)
    }
    
    private fun handleImageSelected(uri: Uri) {
        val fileSize = getFileSize(uri)
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            val sizeMB = String.format("%.2f", fileSize / (1024f * 1024f))
            showFileSizeErrorDialog(sizeMB)
            return
        }
        
        showProgressDialog("正在上传图片...", 1, 0)
        
        CoroutineScope(Dispatchers.IO).launch {
            val existingImages = AvatarImageManager.getAvailableImageNames(this@AvatarUploadActivity, currentSetName)
            val nextIndex = getNextFrameIndex(existingImages)
            val imageName = "dancer_single$nextIndex"
            
            val success = AvatarImageManager.saveAvatarImage(
                context = this@AvatarUploadActivity,
                uri = uri,
                setName = currentSetName,
                imageName = imageName
            )
            
            withContext(Dispatchers.Main) {
                dismissProgressDialog()
                
                if (success) {
                    AvatarLoader.clearCacheForDirectory(currentSetName)
                    showSuccessDialog("上传成功", "图片已成功添加到图片集")
                    loadImages()
                } else {
                    showErrorDialog("上传失败", "图片保存失败，请重试")
                }
            }
        }
    }
    
    private fun handleMultipleImagesSelected(uris: List<Uri>) {
        val oversizedFiles = mutableListOf<Pair<Int, String>>()
        uris.forEachIndexed { index, uri ->
            val fileSize = getFileSize(uri)
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                val sizeMB = String.format("%.2f", fileSize / (1024f * 1024f))
                oversizedFiles.add(index + 1 to sizeMB)
            }
        }
        
        if (oversizedFiles.isNotEmpty()) {
            showBatchFileSizeErrorDialog(oversizedFiles, uris.size)
            return
        }
        
        showProgressDialog("正在批量上传图片...", uris.size, 0)
        
        CoroutineScope(Dispatchers.IO).launch {
            var successCount = 0
            var failCount = 0
            
            val existingImages = AvatarImageManager.getAvailableImageNames(this@AvatarUploadActivity, currentSetName)
            var nextIndex = getNextFrameIndex(existingImages)
            
            uris.forEachIndexed { index, uri ->
                val imageName = "dancer_single$nextIndex"
                val success = AvatarImageManager.saveAvatarImage(
                    context = this@AvatarUploadActivity,
                    uri = uri,
                    setName = currentSetName,
                    imageName = imageName
                )
                
                if (success) {
                    successCount++
                    nextIndex++
                } else {
                    failCount++
                }
                
                withContext(Dispatchers.Main) {
                    updateProgressDialog(index + 1)
                }
            }
            
            withContext(Dispatchers.Main) {
                dismissProgressDialog()
                
                if (successCount > 0) {
                    AvatarLoader.clearCacheForDirectory(currentSetName)
                }
                
                val message = buildString {
                    append("批量上传完成\n\n")
                    append("✓ 成功: $successCount 张")
                    if (failCount > 0) {
                        append("\n✗ 失败: $failCount 张")
                    }
                }
                
                showResultDialog("上传完成", message)
                loadImages()
            }
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
    
    private fun showBatchDeleteConfirmDialog() {
        val selectedCount = adapter.getSelectedCount()
        AlertDialog.Builder(this)
            .setTitle("批量删除")
            .setMessage("确定要删除选中的 $selectedCount 张图片吗？")
            .setPositiveButton("删除") { _, _ ->
                batchDeleteImages()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun batchDeleteImages() {
        val selectedImages = adapter.getSelectedImages()
        showProgressDialog("正在删除图片...", selectedImages.size, 0)
        
        CoroutineScope(Dispatchers.IO).launch {
            var successCount = 0
            var failCount = 0
            
            selectedImages.forEachIndexed { index, imageName ->
                val file = AvatarImageManager.getAvatarDirectory(this@AvatarUploadActivity, currentSetName)
                    .resolve("$imageName.png")
                
                if (file.delete()) {
                    successCount++
                } else {
                    failCount++
                }
                
                withContext(Dispatchers.Main) {
                    updateProgressDialog(index + 1)
                }
            }
            
            withContext(Dispatchers.Main) {
                dismissProgressDialog()
                
                if (successCount > 0) {
                    AvatarLoader.clearCacheForDirectory(currentSetName)
                }
                
                val message = buildString {
                    append("批量删除完成\n\n")
                    append("✓ 成功: $successCount 张")
                    if (failCount > 0) {
                        append("\n✗ 失败: $failCount 张")
                    }
                }
                
                showResultDialog("删除完成", message)
                toggleSelectionMode()
                loadImages()
            }
        }
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
        showProgressDialog("正在删除图片...", 1, 0)
        
        CoroutineScope(Dispatchers.IO).launch {
            val file = AvatarImageManager.getAvatarDirectory(this@AvatarUploadActivity, currentSetName)
                .resolve("$imageName.png")
            
            val success = file.delete()
            
            withContext(Dispatchers.Main) {
                dismissProgressDialog()
                
                if (success) {
                    AvatarLoader.clearCacheForDirectory(currentSetName)
                    showSuccessDialog("删除成功", "图片已从图片集中移除")
                    loadImages()
                } else {
                    showErrorDialog("删除失败", "无法删除图片，请重试")
                }
            }
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
        showProgressDialog("正在清空图片集...", 1, 0)
        
        CoroutineScope(Dispatchers.IO).launch {
            val success = AvatarImageManager.deleteAvatarSet(this@AvatarUploadActivity, currentSetName)
            
            withContext(Dispatchers.Main) {
                dismissProgressDialog()
                
                if (success) {
                    AvatarLoader.clearCacheForDirectory(currentSetName)
                    showSuccessDialog("清空成功", "所有图片已删除")
                    loadImages()
                } else {
                    showErrorDialog("清空失败", "删除图片时出错，请重试")
                }
            }
        }
    }
    
    private fun getFileSize(uri: Uri): Long {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun showProgressDialog(message: String, total: Int, current: Int) {
        dismissProgressDialog()
        val progressMessage = if (total > 1) {
            "$message ($current/$total)"
        } else {
            message
        }
        progressDialog = AlertDialog.Builder(this)
            .setMessage(progressMessage)
            .setCancelable(false)
            .create()
        progressDialog?.show()
    }
    
    private fun updateProgressDialog(current: Int) {
        // 更新进度消息（如果需要）
    }
    
    private fun dismissProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }
    
    private fun showFileSizeErrorDialog(sizeMB: String) {
        AlertDialog.Builder(this)
            .setTitle("文件过大")
            .setMessage("图片大小为 ${sizeMB}MB，超过了 ${MAX_FILE_SIZE_MB}MB 的限制。\n\n请选择较小的图片文件。")
            .setPositiveButton("确定", null)
            .show()
    }
    
    private fun showBatchFileSizeErrorDialog(oversizedFiles: List<Pair<Int, String>>, totalCount: Int) {
        val message = buildString {
            append("以下图片超过 ${MAX_FILE_SIZE_MB}MB 限制：\n\n")
            oversizedFiles.forEach { (index, sizeMB) ->
                append("• 第 $index 张: ${sizeMB}MB\n")
            }
            append("\n共 ${oversizedFiles.size}/${totalCount} 张图片超限")
        }
        
        AlertDialog.Builder(this)
            .setTitle("部分文件过大")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }
    
    private fun showSuccessDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }
    
    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }
    
    private fun showResultDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }
}

class AvatarImageAdapter(
    private val context: Context,
    private val setName: String,
    private val onItemClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<AvatarImageAdapter.ViewHolder>() {
    
    private var imageNames = listOf<String>()
    private var isSelectionMode = false
    private val selectedImages = mutableSetOf<String>()
    
    fun updateImages(names: List<String>) {
        imageNames = names
        notifyDataSetChanged()
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
        notifyItemChanged(imageNames.indexOf(imageName))
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
        holder.bind(imageNames[position])
    }
    
    override fun getItemCount() = imageNames.size
    
    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imageView: ImageView = view.findViewById(R.id.ivAvatar)
        private val btnDelete: View = view.findViewById(R.id.btnDelete)
        private val selectionOverlay: View = view.findViewById(R.id.selectionOverlay)
        
        fun bind(imageName: String) {
            val bitmap = AvatarImageManager.loadAvatarImage(context, setName, imageName)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                imageView.setImageResource(R.drawable.ic_launcher_foreground)
            }
            
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
