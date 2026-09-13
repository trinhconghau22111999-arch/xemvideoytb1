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
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_TITLE = "title"
    }

    private var videoPath: String? = null
    private var mediaPlayer: MediaPlayer? = null

    // QUAN TRỌNG: bản thân file video này được QUAY LÚC nguồn đang phát ở tốc độ 8x (xem
    // RECORD_SPEED_FACTOR trong app quay màn hình - repo Y-utubecuatoi/MainActivity.kt, trước
    // đây là 16x rồi 10x, nay giảm xuống 8x) - nội dung bên trong file đã bị "nén" thời gian lại
    // 8 lần so với video gốc thật. Nếu phát file này ở đúng tốc độ chuẩn 1.0x của trình phát, mắt
    // sẽ thấy nó chạy NHANH GẤP 8 LẦN so với video gốc trên YouTube. Muốn xem đúng bằng tốc độ
    // thật của video gốc, trình phát phải chạy CHẬM LẠI đúng 8 lần: 1 ÷ 8 = 0.125 - đây mới là
    // tốc độ tương ứng với "1x" thật sự (không phải 1.0 của trình phát). "2x" (nhanh gấp đôi tốc
    // độ thật) tương ứng 0.125 × 2 = 0.25.
    //
    // Hệ số này (8) PHẢI khớp với RECORD_SPEED_FACTOR bên app quay (Y-utubecuatoi) - dùng chung 1
    // hằng số ở đây rồi suy ra REAL_1X/REAL_2X, đồng thời gán sang SpeedAdjustedVideoView (xem
    // onCreate bên dưới) để tổng thời lượng + 2 nút tua tới/lui trên thanh điều khiển cũng tự quy
    // đổi đúng theo thời gian thật, không còn tính theo thời gian gốc (đã nén) của file nữa.
    private val RECORD_SPEED_FACTOR = 8
    private val REAL_1X = 1f / RECORD_SPEED_FACTOR
    private val REAL_2X = REAL_1X * 2f
    // Mặc định LUÔN mở video ở tốc độ thật 1x (0.1x của trình phát) - "2x" chỉ là lựa chọn xem
    // nhanh, bấm thêm mới bật.
    private var is2x = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val videoView = findViewById<SpeedAdjustedVideoView>(R.id.videoView)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val btnSpeed = findViewById<TextView>(R.id.btnSpeed)

        // Tổng thời lượng, vị trí hiện tại, và 2 nút tua tới/lui trên MediaController mặc định sẽ
        // tự lấy qua getDuration()/getCurrentPosition()/seekTo() đã bị ghi đè trong
        // SpeedAdjustedVideoView - gán hệ số NGAY TỪ ĐẦU (trước khi MediaController được tạo/dùng
        // tới) để không có lúc nào các hàm đó vô tình trả về thời gian gốc (chưa quy đổi).
        videoView.speedFactor = RECORD_SPEED_FACTOR

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
            // Chỉnh ngay về tốc độ thật 1x (0.1x) - phải gọi SAU start() (đổi playbackParams
            // lúc MediaPlayer đã chạy mới ổn định trên đa số thiết bị).
            if (!applySpeed(mp, REAL_1X)) {
                Toast.makeText(
                    this,
                    "Máy này không hỗ trợ phát chậm 1/10 - video có thể bị nhanh hơn tốc độ thật",
                    Toast.LENGTH_LONG
                ).show()
            }
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
    // lần nữa để trả về 1x thật (0.1x). Nhãn trên nút luôn hiển thị tốc độ HIỆN TẠI đang phát
    // (tính theo tốc độ THẬT của video gốc, không phải số nhân của trình phát).
    private fun toggleSpeed(btnSpeed: TextView) {
        val mp = mediaPlayer ?: return
        val wantsIs2x = !is2x
        val targetSpeed = if (wantsIs2x) REAL_2X else REAL_1X
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
            // Vài thiết bị/codec hiếm gặp không hỗ trợ tốc độ quá thấp (0.1x) hoặc đổi tốc độ
            // giữa chừng - bỏ qua, không crash, giữ nguyên tốc độ đang phát.
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
