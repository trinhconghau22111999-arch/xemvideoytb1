package com.ytbrowser.viewer

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView

// File video quay được đã bị "nén" thời gian lại theo speedFactor lần so với video gốc thật (xem
// giải thích chi tiết ở PlayerActivity.REAL_1X / RECORD_SPEED_FACTOR). MediaController mặc định
// (đi kèm VideoView) lấy tổng thời lượng, vị trí hiện tại, và bước nhảy của 2 nút tua tới/tua lùi
// (+/-15 giây) TRỰC TIẾP từ getDuration()/getCurrentPosition()/seekTo() của VideoView - toàn bộ
// tính bằng đơn vị thời gian CỦA FILE THÔ (đã nén), không phải thời gian thật của video gốc.
// Trước đây không quy đổi lại nên: tổng thời lượng hiển thị sai (ngắn hơn thật speedFactor lần),
// và mỗi lần bấm tua tới/lui 15 giây thực chất nhảy tới 15 × speedFactor giây nội dung thật.
//
// Lớp này ghi đè 3 hàm trên để tự quy đổi 2 chiều ngay tại nguồn: bên ngoài (MediaController) chỉ
// thấy toàn bộ thời gian ở đơn vị THẬT, còn bên trong vẫn gọi MediaPlayer thật bằng đơn vị RAW của
// file - MediaController (kể cả seekbar và 2 nút tua) tự động đúng mà không cần sửa gì thêm ở đó.
class SpeedAdjustedVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : VideoView(context, attrs) {

    // Hệ số tốc độ quay (vd 10 nếu quay ở 10x) - PlayerActivity gán giá trị này ngay sau khi tạo
    // view, phải khớp với tốc độ forceSpeed(...) dùng bên app quay màn hình (ScreenRecordService).
    var speedFactor: Int = 1

    override fun getDuration(): Int {
        val raw = super.getDuration()
        if (raw < 0) return raw
        return scaleUp(raw)
    }

    override fun getCurrentPosition(): Int {
        val raw = super.getCurrentPosition()
        if (raw < 0) return raw
        return scaleUp(raw)
    }

    override fun seekTo(msec: Int) {
        // msec đến từ MediaController (kéo seekbar hoặc bấm tua tới/lui) đang ở đơn vị THẬT -
        // quy đổi ngược lại thành đơn vị RAW của file trước khi gọi MediaPlayer thật.
        val rawMsec = (msec.toLong() / speedFactor.coerceAtLeast(1)).toInt()
        super.seekTo(rawMsec)
    }

    private fun scaleUp(raw: Int): Int {
        // Dùng Long để tránh tràn số trước khi ép lại về Int (video quay thường không đủ dài để
        // vượt quá Int.MAX_VALUE mili-giây ngay cả sau khi nhân, nhưng vẫn phòng hờ cho chắc).
        val scaled = raw.toLong() * speedFactor.coerceAtLeast(1)
        return scaled.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
