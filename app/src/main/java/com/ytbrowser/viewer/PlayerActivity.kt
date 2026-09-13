package com.ytbrowser.viewer

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_TITLE = "title"

        // Thời gian thanh tiến độ tự ẩn sau khi không còn thao tác gì (chạm màn hình / kéo
        // thanh) - đủ lâu để đọc/kéo kịp, không tắt phụt quá nhanh.
        private const val CONTROLS_AUTO_HIDE_MS = 3000L
        // Chu kỳ cập nhật vị trí phát lên thanh tiến độ + nhãn thời gian khi đang phát.
        private const val PROGRESS_UPDATE_MS = 300L
        // Độ chia nhỏ của SeekBar - dùng thang cố định (thay vì gán max = duration tính bằng mili
        // giây) để tránh phụ thuộc keeping-in-sync khi duration lớn, đồng thời kéo mượt hơn.
        private const val SEEKBAR_MAX = 1000

        // File .mp4 gốc (sau khi giải mã từ .locked) do app quay màn hình lưu ra ĐÃ ở tốc độ 4x
        // THẬT (xem RECORD_SPEED_FACTOR trong Y-utubecuatoi/MainActivity.kt + ScreenRecordService
        // - quay nhanh 4x để tiết kiệm thời gian, KHÔNG kéo giãn PTS về lại 1x lúc lưu). Vì vậy
        // viewer này phải CHỦ ĐỘNG chia tốc độ cho 4 để nhãn nút hiển thị đúng với tốc độ NỘI DUNG
        // GỐC (thứ người xem thực sự quan tâm), không phải tốc độ thật của file:
        //   nhãn "1x" (xem đúng tốc độ nội dung gốc)   -> tốc độ MediaPlayer thật = 1.0 / 4 = 0.25
        //   nhãn "2x" (xem nhanh gấp đôi nội dung gốc) -> tốc độ MediaPlayer thật = 2.0 / 4 = 0.5
        private const val SPEED_LABEL_1X = 0.25f
        private const val SPEED_LABEL_2X = 0.5f

        // Mỗi lần bấm nút tua lùi/tua tới là nhảy đúng 2.5 giây - tính trực tiếp trên đồng hồ
        // của FILE đang phát (mp.currentPosition/duration), giống hệt cách tvCurrentTime/
        // tvDuration/seekBar đang hiển thị - không quy đổi gì thêm ở đây, khác với tốc độ phát
        // (SPEED_LABEL_1X/2X) vốn phải quy đổi vì đó là NHÃN tốc độ nội dung gốc.
        private const val SEEK_STEP_MS = 2500
    }

    private var videoPath: String? = null
    private var mediaPlayer: MediaPlayer? = null

    // is2x: nhãn tốc độ hiện tại đang hiển thị là "1x" hay "2x" (tốc độ NỘI DUNG GỐC, không phải
    // tốc độ thật truyền cho MediaPlayer - xem SPEED_LABEL_1X/2X ở companion object).
    private var is2x = false

    private lateinit var videoView: VideoView
    private lateinit var controlsBar: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvDuration: TextView
    private lateinit var btnSeekBack: TextView
    private lateinit var btnSeekForward: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    // true khi người dùng đang kéo tay trên seekbar - lúc này KHÔNG được tự cập nhật progress
    // đè lên (sẽ làm giật lại vị trí đang kéo), và KHÔNG đếm giờ để tự ẩn thanh.
    private var isUserSeeking = false

    // Cập nhật vị trí phát hiện tại lên seekbar + nhãn thời gian mỗi PROGRESS_UPDATE_MS - tự lặp
    // lại chính nó bằng postDelayed, chỉ dừng khi Activity bị huỷ (removeCallbacks ở onDestroy).
    private val progressUpdater = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && !isUserSeeking) {
                val duration = mp.duration.coerceAtLeast(1)
                val position = mp.currentPosition.coerceIn(0, duration)
                seekBar.progress = (position.toLong() * SEEKBAR_MAX / duration).toInt()
                tvCurrentTime.text = formatTime(position)
                tvDuration.text = formatTime(duration)
            }
            uiHandler.postDelayed(this, PROGRESS_UPDATE_MS)
        }
    }

    // Tự ẩn controlsBar sau CONTROLS_AUTO_HIDE_MS kể từ lần gọi scheduleAutoHide() gần nhất -
    // mỗi lần có thao tác mới thì removeCallbacks + post lại từ đầu (xem scheduleAutoHide()).
    private val hideControlsRunnable = Runnable {
        if (!isUserSeeking) controlsBar.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        enterImmersiveMode()

        videoView = findViewById(R.id.videoView)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val btnSpeed = findViewById<TextView>(R.id.btnSpeed)
        controlsBar = findViewById(R.id.controlsBar)
        seekBar = findViewById(R.id.seekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvDuration = findViewById(R.id.tvDuration)
        btnSeekBack = findViewById(R.id.btnSeekBack)
        btnSeekForward = findViewById(R.id.btnSeekForward)

        seekBar.max = SEEKBAR_MAX

        videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
        title = intent.getStringExtra(EXTRA_TITLE)

        val path = videoPath
        if (path == null || !File(path).exists()) {
            Toast.makeText(this, "Không tìm thấy video", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

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
            // Mặc định phát ở nhãn "1x" (tốc độ MediaPlayer thật = 25% - xem SPEED_LABEL_1X) vì
            // file gốc đã được quay ở 4x thật - không đặt mặc định thì video sẽ phát nhanh gấp 4
            // ngay từ giây đầu tiên. Không báo lỗi nếu máy không hỗ trợ đổi tốc độ (rất hiếm) -
            // im lặng phát ở tốc độ gốc còn hơn làm phiền bằng Toast ngay lúc mới mở video.
            applySpeed(mp, SPEED_LABEL_1X)
            videoView.start()
            tvDuration.text = formatTime(mp.duration)
            uiHandler.post(progressUpdater)
            // Hiện sẵn thanh tiến độ lúc mới vào, rồi tự đếm giờ ẩn như bình thường.
            showControls()
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
        btnSeekBack.setOnClickListener { seekRelative(-SEEK_STEP_MS) }
        btnSeekForward.setOnClickListener { seekRelative(SEEK_STEP_MS) }

        // Chạm vào khu vực video (ngoài thanh tiến độ) để: (1) hiện/ẩn thanh tiến độ, (2) tạm
        // dừng/tiếp tục phát - thay cho 3 nút tua lùi/play-pause/tua tới lớn ở giữa màn hình đã
        // bỏ (đã có sẵn thanh tiến độ bên dưới để tua rồi nên không cần thêm nữa).
        videoView.setOnClickListener { onVideoTapped() }

        setupSeekBar()
    }

    private fun onVideoTapped() {
        if (controlsBar.visibility == View.VISIBLE) {
            togglePlayPause()
        } else {
            showControls()
        }
    }

    private fun togglePlayPause() {
        val vv = videoView
        if (vv.isPlaying) {
            vv.pause()
        } else {
            vv.start()
        }
        scheduleAutoHide()
    }

    // Bấm 1 cái là nhảy ngay deltaMs (âm = lùi, dương = tới) rồi cập nhật hiển thị NGAY LẬP TỨC
    // (không đợi progressUpdater chạy vòng kế tiếp, tối đa PROGRESS_UPDATE_MS sau) để cảm giác
    // bấm phát ăn liền, giống hệt cách onStopTrackingTouch() của seekBar đang làm.
    private fun seekRelative(deltaMs: Int) {
        val mp = mediaPlayer ?: return
        val duration = mp.duration.coerceAtLeast(1)
        val target = (mp.currentPosition + deltaMs).coerceIn(0, duration)
        videoView.seekTo(target)
        seekBar.progress = (target.toLong() * SEEKBAR_MAX / duration).toInt()
        tvCurrentTime.text = formatTime(target)
        // Hiện lại thanh (nếu đang ẩn) + gia hạn tự ẩn, giống mọi thao tác chạm khác trên màn hình.
        showControls()
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val mp = mediaPlayer ?: return
                    val duration = mp.duration.coerceAtLeast(1)
                    val target = (progress.toLong() * duration / SEEKBAR_MAX).toInt()
                    tvCurrentTime.text = formatTime(target)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                isUserSeeking = true
                // Đang kéo thì không được tự đếm giờ ẩn thanh - huỷ luôn lệnh ẩn đang chờ (nếu có).
                uiHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                val mp = mediaPlayer
                if (mp != null) {
                    val duration = mp.duration.coerceAtLeast(1)
                    val target = (sb.progress.toLong() * duration / SEEKBAR_MAX).toInt()
                    videoView.seekTo(target)
                }
                isUserSeeking = false
                scheduleAutoHide()
            }
        })
    }

    private fun showControls() {
        controlsBar.visibility = View.VISIBLE
        scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        uiHandler.removeCallbacks(hideControlsRunnable)
        uiHandler.postDelayed(hideControlsRunnable, CONTROLS_AUTO_HIDE_MS)
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    // Ẩn thanh điều hướng (navigation bar) và thanh trạng thái (status bar - pin/mạng/giờ) ở
    // trên khi phát video - kiểu "immersive sticky": vuốt nhẹ từ mép màn hình vẫn xem tạm được
    // thanh hệ thống rồi nó tự ẩn lại, không cần bấm nút back/home mới ẩn lại. Gọi lại ở
    // onWindowFocusChanged(true) vì hệ thống có thể tự hiện lại thanh này khi Activity mất rồi
    // lấy lại focus (vd sau khi tắt/mở lại màn hình, hoặc quay lại từ 1 dialog hệ thống).
    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    // Bấm 1 cái là chuyển thẳng sang tốc độ đó và phát tiếp luôn (không phải giữ nút) - bấm lại
    // lần nữa để trả về 1x. Nhãn hiển thị ("1x"/"2x") là tốc độ NỘI DUNG GỐC người xem cảm nhận -
    // tốc độ THẬT truyền cho MediaPlayer đã được chia 4 (xem SPEED_LABEL_1X/2X) vì file đang phát
    // vốn đã được quay nhanh 4x thật từ trước.
    private fun toggleSpeed(btnSpeed: TextView) {
        val mp = mediaPlayer ?: return
        val wantsIs2x = !is2x
        val targetSpeed = if (wantsIs2x) SPEED_LABEL_2X else SPEED_LABEL_1X
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
            // QUAN TRỌNG: phải set CẢ pitch = 1f, không chỉ speed. Nếu chỉ setSpeed() mà bỏ
            // trống pitch, nhiều máy/thiết bị KHÔNG tự dùng thuật toán giữ nguyên cao độ
            // (time-stretch) mà để cao độ trôi theo tốc độ luôn - phát chậm (0.25x/0.5x) thì
            // giọng bị kéo trầm xuống nghe "ma quái", không bình thường. Set pitch=1f ép hệ
            // thống luôn giữ đúng cao độ gốc bất kể tốc độ phát nhanh/chậm bao nhiêu.
            mp.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
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
        uiHandler.removeCallbacksAndMessages(null)
        videoPath?.let { File(it).delete() }
    }
}
