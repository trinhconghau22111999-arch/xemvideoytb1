package com.ytbrowser.viewer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Phải khớp đúng thư mục mà ScreenRecordService (app quay màn hình) ghi file .locked vào -
    // xem buildOutputPath() trong repo Y-utubecuatoi: Downloads/vdy/y.<n>.locked
    private val VIDEO_SUBFOLDER = "vdy"

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnGrantPermission: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: VideoAdapter

    // Xin quyền READ_EXTERNAL_STORAGE thường (chỉ cần trên Android <= 12, xem manifest) -
    // MANAGE_EXTERNAL_STORAGE (Android 11+) không xin qua dialog thường được, phải mở màn
    // hình Cài đặt riêng (xem requestAllFilesAccess()).
    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadVideos() else showPermissionNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        progressBar = findViewById(R.id.progressBar)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = VideoAdapter { playVideo(it) }
        recyclerView.adapter = adapter

        btnGrantPermission.setOnClickListener { ensurePermissionAndLoad() }

        // Dọn sạch mọi bản .mp4 tạm giải mã còn sót lại từ phiên trước (vd app bị tắt đột ngột
        // trước khi PlayerActivity kịp xoá ở onDestroy) - không để lại nội dung đã giải mã nằm
        // trong cache lâu hơn mức cần thiết.
        cleanupDecryptedCache()
    }

    override fun onResume() {
        super.onResume()
        ensurePermissionAndLoad()
    }

    private fun ensurePermissionAndLoad() {
        if (hasStoragePermission()) {
            loadVideos()
        } else {
            showPermissionNeeded()
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionNeeded() {
        recyclerView.visibility = View.GONE
        progressBar.visibility = View.GONE
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = "Cần cấp quyền truy cập bộ nhớ để tìm video đã tải trong Downloads/$VIDEO_SUBFOLDER"
        btnGrantPermission.visibility = View.VISIBLE
        btnGrantPermission.setOnClickListener { requestStoragePermission() }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun loadVideos() {
        btnGrantPermission.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        Thread {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(downloads, VIDEO_SUBFOLDER)
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(VideoCrypto.LOCKED_EXTENSION) }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            runOnUiThread {
                progressBar.visibility = View.GONE
                if (files.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "Chưa có video nào trong Downloads/$VIDEO_SUBFOLDER"
                } else {
                    recyclerView.visibility = View.VISIBLE
                    tvEmpty.visibility = View.GONE
                    adapter.submitList(files)
                }
            }
        }.start()
    }

    private fun playVideo(file: File) {
        progressBar.visibility = View.VISIBLE
        Thread {
            val cacheDir = File(cacheDir, "playback").apply { mkdirs() }
            val outFile = File(cacheDir, "play_${System.currentTimeMillis()}.mp4")
            val ok = VideoCrypto.decryptFile(file, outFile)

            runOnUiThread {
                progressBar.visibility = View.GONE
                if (ok) {
                    val intent = Intent(this, PlayerActivity::class.java)
                    intent.putExtra(PlayerActivity.EXTRA_VIDEO_PATH, outFile.absolutePath)
                    intent.putExtra(PlayerActivity.EXTRA_TITLE, file.name)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Không mở được video này (file lỗi hoặc không hợp lệ)", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun cleanupDecryptedCache() {
        Thread {
            File(cacheDir, "playback").listFiles()?.forEach { it.delete() }
        }.start()
    }

    class VideoAdapter(private val onClick: (File) -> Unit) :
        RecyclerView.Adapter<VideoAdapter.ViewHolder>() {

        private var items: List<File> = emptyList()

        fun submitList(files: List<File>) {
            items = files
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = items[position]
            holder.tvName.text = file.name.removeSuffix(VideoCrypto.LOCKED_EXTENSION)
            val sizeMb = file.length() / (1024.0 * 1024.0)
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            holder.tvMeta.text = String.format(Locale.getDefault(), "%.1f MB • %s", sizeMb, sdf.format(file.lastModified()))
            holder.itemView.setOnClickListener { onClick(file) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvMeta: TextView = view.findViewById(R.id.tvMeta)
        }
    }
}
