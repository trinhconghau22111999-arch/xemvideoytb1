package com.ytbrowser.viewer

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// ---- Giải mã video .locked do app quay màn hình tạo ra ----
// Phải khớp CHÍNH XÁC với thuật toán trong VideoCrypto.kt của app quay màn hình (repo
// Y-utubecuatoi): AES-256-CBC + PKCS5Padding, khoá suy ra từ mật khẩu cố định bằng
// PBKDF2WithHmacSHA256 (65536 vòng lặp), salt + IV đọc thẳng từ 16 byte đầu file (sau 8 byte
// "magic" nhận diện định dạng). Vì mật khẩu CỐ ĐỊNH ngay trong code, app này không bao giờ cần
// hỏi người dùng nhập mật khẩu - chỉ cần đọc đúng file .locked là tự giải mã được luôn.
object VideoCrypto {

    // PHẢI giống hệt mật khẩu trong app quay màn hình, nếu không sẽ không giải mã được file nào.
    const val VIDEO_PASSWORD = "TRINHCONGHAU99"

    const val LOCKED_EXTENSION = ".locked"

    private const val MAGIC = "YOYLOCK1"
    private const val SALT_LEN = 16
    private const val IV_LEN = 16
    private const val PBKDF2_ITERATIONS = 65536
    private const val KEY_LEN_BITS = 256

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LEN_BITS)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    // Giải mã lockedFile -> ghi ra outFile. Trả về true nếu định dạng hợp lệ và giải mã ra đúng
    // 1 file MP4 thật (kiểm tra chữ ký "ftyp" ở offset 4) - phòng trường hợp file .locked bị
    // hỏng hoặc không phải do app quay màn hình tạo ra.
    fun decryptFile(lockedFile: File, outFile: File, password: String = VIDEO_PASSWORD): Boolean {
        try {
            FileInputStream(lockedFile).use { rawIn ->
                val magic = ByteArray(MAGIC.length)
                if (rawIn.read(magic) != magic.size || String(magic, Charsets.US_ASCII) != MAGIC) {
                    return false
                }
                val salt = ByteArray(SALT_LEN)
                val iv = ByteArray(IV_LEN)
                if (rawIn.read(salt) != SALT_LEN || rawIn.read(iv) != IV_LEN) return false

                val key = deriveKey(password, salt)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))

                FileOutputStream(outFile).use { output ->
                    CipherInputStream(rawIn, cipher).use { cIn ->
                        cIn.copyTo(output, bufferSize = 1 shl 16)
                    }
                }
            }

            val header = ByteArray(8)
            FileInputStream(outFile).use { it.read(header) }
            val looksLikeMp4 = header.size >= 8 &&
                header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()

            if (!looksLikeMp4) {
                outFile.delete()
                return false
            }
            return true
        } catch (e: Exception) {
            outFile.delete()
            return false
        }
    }
}
