# Xem Video YTB (xemvideoytb1)

App riêng chỉ để **xem lại** các video đã quay/tải về nằm trong `Downloads/vdy` (do app quay
màn hình ở repo `Y-utubecuatoi` tạo ra, dạng file `.locked`).

- Không cần nhập mật khẩu: mật khẩu giải mã cố định đã có sẵn trong code (`VideoCrypto.kt`,
  phải khớp với mật khẩu trong app quay màn hình).
- Quét thư mục `Downloads/vdy`, liệt kê các file `.locked`, bấm vào là tự giải mã ra 1 bản
  `.mp4` tạm trong cache riêng của app rồi phát ngay bằng trình phát dựng sẵn trong app.
- Bản `.mp4` tạm bị xoá ngay khi đóng màn hình xem - không để lại bản rõ nằm lâu trong máy.
- Android 11+ cần cấp quyền "Cho phép quản lý mọi tệp" (All files access) để đọc được thư mục
  `Downloads/vdy` do app khác tạo ra (không đăng ký MediaStore).

## Build

CI (`.github/workflows/build.yml`) tự build APK debug ở mỗi lần push lên `main` và đính kèm
vào GitHub Release của lần build đó.
