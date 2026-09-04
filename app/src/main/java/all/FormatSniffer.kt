package io.github.aixtin.nyral

/**
 * 附件格式嗅探器: 通过魔数识别文件真实根类型, 解决"扩展名≠真实格式"导致的解码失败。
 * 仅读取少量头字节判断, 不影响文件本体; 无法识别时返回 UNKNOWN 由上层拒绝并提示。
 * 说明: 不改后缀名, 只影响发送时按真实格式走分支与归一 mime。
 */
object FormatSniffer {

    const val IMAGE_JPEG = "image/jpeg"
    const val IMAGE_PNG = "image/png"
    const val IMAGE_WEBP = "image/webp"
    const val IMAGE_GIF = "image/gif"
    const val IMAGE_HEIC = "image/heic"
    const val AUDIO_MP3 = "audio/mpeg"
    const val AUDIO_AAC = "audio/aac"
    const val AUDIO_WAV = "audio/wav"
    const val AUDIO_FLAC = "audio/flac"
    const val AUDIO_OGG = "audio/ogg"
    const val VIDEO_MP4 = "video/mp4"
    const val VIDEO_3GP = "video/3gpp"
    const val PDF = "application/pdf"
    const val TEXT = "text/plain"
    const val EMPTY = "empty"
    const val UNKNOWN = "unknown"

    /**
     * @param head 文件头部字节(建议 >=16, 文本/全零判定会按可用长度宽松处理)
     * @param lowName 小写文件名(用于 m4a 等容器语义辅助判定)
     * @return 根类型(规范 mime 或 EMPTY/TEXT/UNKNOWN)
     */
    fun sniff(head: ByteArray, lowName: String): String {
        val n = head.size
        if (n == 0) return EMPTY
        // 全零文件
        var zero = true
        for (i in 0 until n) if (head[i] != 0.toByte()) { zero = false; break }
        if (zero) return EMPTY

        val b0 = head[0].toInt() and 0xFF
        val b1 = if (n > 1) head[1].toInt() and 0xFF else -1
        val b2 = if (n > 2) head[2].toInt() and 0xFF else -1
        val b3 = if (n > 3) head[3].toInt() and 0xFF else -1

        // JPEG
        if (n >= 3 && b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return IMAGE_JPEG
        // PNG
        if (n >= 8 && b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 &&
            (head[4].toInt() and 0xFF) == 0x0D && (head[5].toInt() and 0xFF) == 0x0A &&
            (head[6].toInt() and 0xFF) == 0x1A && (head[7].toInt() and 0xFF) == 0x0A) return IMAGE_PNG
        // GIF
        if (n >= 6 && b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return IMAGE_GIF

        // RIFF....(WEBP/WAVE)
        if (n >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) {
            val four = String(head, 8, 4)
            return when (four) {
                "WEBP" -> IMAGE_WEBP
                "WAVE" -> AUDIO_WAV
                else -> UNKNOWN
            }
        }

        // ISO BMFF / MP4 家族: ....ftypXXXX
        if (n >= 12 && (head[4].toInt() and 0xFF) == 0x66 && (head[5].toInt() and 0xFF) == 0x74 &&
            (head[6].toInt() and 0xFF) == 0x79 && (head[7].toInt() and 0xFF) == 0x70) {
            val brand = String(head, 8, 4).uppercase()
            return when {
                brand == "HEIC" || brand == "HEIX" || brand == "HEIF" || brand == "MIF1" ||
                    brand == "HEIM" || brand == "HEVC" || brand == "HEVX" -> IMAGE_HEIC
                brand == "3GP4" || brand == "3GP5" || brand == "3GP6" || brand == "3GP7" -> VIDEO_3GP
                brand == "ISOM" || brand == "MP42" || brand == "MP41" || brand == "MP4V" ||
                    brand == "MSNV" || brand == "QT  " -> {
                    // isom 容器既可能是 mp4 也可能是 m4a(audio), 用扩展名辅助区分
                    if (lowName.endsWith(".m4a") || lowName.endsWith(".m4b")) AUDIO_AAC else VIDEO_MP4
                }
                brand == "M4A " -> AUDIO_AAC
                brand == "M4V " || brand == "MP4 " || brand == "DASH" -> VIDEO_MP4
                else -> VIDEO_MP4 // 其他 ftyp 容器默认按视频走, 上层 m4a 修正逻辑原样保留
            }
        }

        // ID3 -> MP3
        if (n >= 3 && b0 == 0x49 && b1 == 0x44 && b2 == 0x33) return AUDIO_MP3

        // MPEG audio sync 0xFFEx/0xFFFx: 0xFFF0/0xFFF1/0xFFF9 且层位00 -> ADTS(AAC), 其余归 MP3
        if (n >= 2 && b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
            return when (b1 and 0xF0) {
                0xF0 -> if ((b1 and 0x06) == 0) AUDIO_AAC else AUDIO_MP3
                else -> AUDIO_MP3
            }
        }

        // fLaC -> FLAC
        if (n >= 4 && b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) return AUDIO_FLAC
        // OggS -> OGG
        if (n >= 4 && b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53) return AUDIO_OGG
        // %PDF
        if (n >= 4 && b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46) return PDF

        // 文本判定: 前 64 字节中可打印 ASCII 占比 >=80%
        val sample = minOf(n, 64)
        var printable = 0
        for (i in 0 until sample) {
            val v = head[i].toInt() and 0xFF
            if (v == 9 || v == 10 || v == 13 || (v in 32..126)) printable++
        }
        if (printable * 10 >= sample * 8) return TEXT
        return UNKNOWN
    }
}
