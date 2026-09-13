package com.ytbrowser.viewer

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_TITLE = "title"
    }

    private var videoPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val videoView = findViewById<VideoView>(R.id.videoView)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)

        videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
        title = intent.getStringExtra(EXTRA_TITLE)

        val path = videoPath
        if (path == null || !File(path).exists()) {
            Toast.makeText(this, "Không tìm thấy video", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setVideoURI(Uri.fromFile(File(path)))

        videoView.setOnPreparedListener {
            progressBar.visibility = View.GONE
            videoView.start()
        }
        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Không phát được video này", Toast.LENGTH_SHORT).show()
            finish()
            true
        }
        videoView.setOnCompletionListener {
            finish()
        }
    }

    // Xoá bản .mp4 tạm đã giải mã ngay khi rời màn hình xem - giữ đúng tinh thần "chỉ giải mã
    // tạm thời để xem, không để lại bản rõ nằm lâu trong máy" của app quay màn hình gốc.
    override fun onDestroy() {
        super.onDestroy()
        videoPath?.let { File(it).delete() }
    }
}
