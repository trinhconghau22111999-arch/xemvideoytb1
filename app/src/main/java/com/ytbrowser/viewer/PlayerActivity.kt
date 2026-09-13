package com.ytbrowser.viewer

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
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
    private var mediaPlayer: MediaPlayer? = null

    // Đã bỏ toàn bộ phần quy đổi tốc độ/thời lượng (không còn RECORD_SPEED_FACTOR/REAL_1X/
    // REAL_2X) - phát trực tiếp file gốc, không quy đổi gì cả. Tổng thời lượng, vị trí hiện tại,
    // 2 nút tua tới/lui trên MediaController và nhãn tốc độ đều lấy/hiểu theo đúng dữ liệu gốc
    // của file, không suy ra thời gian "thật" nào khác nữa.
    private var is2x = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val videoView = findViewById<VideoView>(R.id.videoView)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val btnSpeed = findViewById<TextView>(R.id.btnSpeed)

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

        // Nút "2x" vô hiệu (mờ đi) cho tới khi MediaPlayer chuẩn bị xong (onPrepared) - gọi
        // playbackParams trước lúc đó sẽ ném lỗi.
        btnSpeed.alpha = 0.4f
        btnSpeed.isEnabled = false

        videoView.setOnPreparedListener { mp ->
            mediaPlayer = mp
            progressBar.visibility = View.GONE
            btnSpeed.alpha = 1f
            btnSpeed.isEnabled = true
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

        btnSpeed.setOnClickListener { toggleSpeed(btnSpeed) }
    }

    // Bấm 1 cái là chuyển thẳng sang tốc độ đó và phát tiếp luôn (không phải giữ nút) - bấm lại
    // lần nữa để trả về 1x. Tốc độ ở đây là tốc độ THẬT của trình phát (1.0x/2.0x bình thường),
    // không quy đổi gì thêm.
    private fun toggleSpeed(btnSpeed: TextView) {
        val mp = mediaPlayer ?: return
        val wantsIs2x = !is2x
        val targetSpeed = if (wantsIs2x) 2.0f else 1.0f
        if (applySpeed(mp, targetSpeed)) {
            is2x = wantsIs2x
            btnSpeed.text = if (is2x) "1x" else "2x"
        } else {
            Toast.makeText(this, "Máy này không hỗ trợ đổi tốc độ phát", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applySpeed(mp: MediaPlayer, speed: Float): Boolean {
        return try {
            val wasPlaying = mp.isPlaying
            mp.playbackParams = PlaybackParams().setSpeed(speed)
            if (wasPlaying && !mp.isPlaying) mp.start()
            true
        } catch (e: Exception) {
            false
        }
    }

    // Xoá bản .mp4 tạm đã giải mã ngay khi rời màn hình xem - giữ đúng tinh thần "chỉ giải mã
    // tạm thời để xem, không để lại bản rõ nằm lâu trong máy" của app quay màn hình gốc.
    override fun onDestroy() {
        super.onDestroy()
        videoPath?.let { File(it).delete() }
    }
}
