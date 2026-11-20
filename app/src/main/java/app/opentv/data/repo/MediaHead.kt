package app.opentv.data.repo

/**
 * What a video file is, from its first bytes: picture size, codec and HDR for Matroska (MKV), which is how most
 * providers and torrents ship films and episodes. Other containers give nothing here (their details are at the end).
 * Used for a playlist copy's Sources row (read over the network) and a download's row (read from the file).
 */
object MediaHead {
    class Tech(val width: Int?, val height: Int?, val codec: String?, val hdr: Boolean) {
        /** "4K · HDR · HEVC", "1080p · H.264"; blank when nothing is known. */
        fun line(): String = listOfNotNull(
            height?.let { h -> val w = width ?: 0; when { w >= 3200 || h >= 2000 -> "4K"; w >= 1800 || h >= 1000 -> "1080p"; w >= 1200 || h >= 700 -> "720p"; else -> "${h}p" } },
            "HDR".takeIf { hdr },
            codec?.let { when (it.lowercase()) { "hevc", "h265" -> "HEVC"; "h264", "avc" -> "H.264"; "av1" -> "AV1"; else -> it.uppercase() } },
        ).joinToString(" · ")
    }

    fun isMatroska(b: ByteArray) = b.size > 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()

    fun parse(b: ByteArray): Tech? {
        if (!isMatroska(b)) return null
        val text = String(b, Charsets.ISO_8859_1)
        val codec = Regex("""V_[A-Z0-9/_.]+""").find(text)?.value?.let {
            when { "HEVC" in it -> "hevc"; "AVC" in it -> "h264"; "AV1" in it -> "av1"; else -> it.removePrefix("V_") } }
        // PixelWidth (B0) then PixelHeight (BA), each with a 1-4 byte size marker (0x81-0x84).
        var w: Int? = null; var h: Int? = null
        var k = 0
        while (k < b.size - 12 && w == null) {
            if (b[k] == 0xB0.toByte() && (b[k + 1].toInt() and 0xFF) in 0x81..0x84) {
                val n = b[k + 1].toInt() and 0x0F
                val ww = (0 until n).fold(0) { a, x -> (a shl 8) or (b[k + 2 + x].toInt() and 0xFF) }
                val at = k + 2 + n
                if (b[at] == 0xBA.toByte() && (b[at + 1].toInt() and 0xFF) in 0x81..0x84) {
                    val n2 = b[at + 1].toInt() and 0x0F
                    val hh = (0 until n2).fold(0) { a, x -> (a shl 8) or (b[at + 2 + x].toInt() and 0xFF) }
                    if (ww in 101..8999 && hh in 101..4999) { w = ww; h = hh }
                }
            }
            k++
        }
        // TransferCharacteristics (55 BA) of 16 (PQ) or 18 (HLG).
        val hdr = (0 until b.size - 3).any { i -> b[i] == 0x55.toByte() && b[i + 1] == 0xBA.toByte() && b[i + 2] == 0x81.toByte() && (b[i + 3].toInt() == 16 || b[i + 3].toInt() == 18) }
        return Tech(w, h, codec, hdr)
    }

    /** The first 256 KB of a local file. */
    fun ofFile(file: java.io.File): Tech? = runCatching {
        file.inputStream().use { input -> val buf = ByteArray(262_144); val n = input.read(buf); if (n <= 0) null else parse(buf.copyOf(n)) }
    }.getOrNull()
}
