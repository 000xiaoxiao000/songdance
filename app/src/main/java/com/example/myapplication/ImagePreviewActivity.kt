package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ImagePreviewActivity : AppCompatActivity() {
    
    companion object {
        private const val EXTRA_SET_NAME = "set_name"
        private const val EXTRA_IMAGE_NAME = "image_name"
        
        fun createIntent(activity: Activity, setName: String, imageName: String): Intent {
            return Intent(activity, ImagePreviewActivity::class.java).apply {
                putExtra(EXTRA_SET_NAME, setName)
                putExtra(EXTRA_IMAGE_NAME, imageName)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_preview)
        
        val setName = intent.getStringExtra(EXTRA_SET_NAME)
        val imageName = intent.getStringExtra(EXTRA_IMAGE_NAME)
        
        if (setName == null || imageName == null) {
            finish()
            return
        }
        
        val ivPreview = findViewById<ImageView>(R.id.ivPreview)
        val tvImageInfo = findViewById<TextView>(R.id.tvImageInfo)
        val btnClose = findViewById<Button>(R.id.btnClose)
        
        val bitmap = AvatarImageManager.loadAvatarImage(this, setName, imageName)
        if (bitmap != null) {
            ivPreview.setImageBitmap(bitmap)
            tvImageInfo.text = "图片: $imageName\n尺寸: ${bitmap.width} x ${bitmap.height}"
        } else {
            tvImageInfo.text = "无法加载图片: $imageName"
        }
        
        btnClose.setOnClickListener {
            finish()
        }
    }
}
