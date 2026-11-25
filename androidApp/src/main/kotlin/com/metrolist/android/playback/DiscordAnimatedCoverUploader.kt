package com.metrolist.android.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Converts a short portion of Apple's motion-art MP4 into animated WebP and
 * uploads it through the user's Cloudflare Worker. No Cloudflare credentials
 * or R2 API keys are embedded in the application.
 */
internal class DiscordAnimatedCoverUploader(
    context: Context,
) {
    private val cacheDirectory = File(context.cacheDir, "discord-animated-cover-uploads").apply {
        mkdirs()
    }

    suspend fun convertAndUpload(
        videoUrl: String,
        workerUrl: String,
        uploadSecret: String,
        songId: String,
    ): String = withContext(Dispatchers.IO) {
        require(videoUrl.startsWith("https://", ignoreCase = true)) { "Motion artwork must use HTTPS" }
        require(workerUrl.startsWith("https://", ignoreCase = true)) { "Worker URL must use HTTPS" }
        require(uploadSecret.isNotBlank()) { "Cloudflare upload secret is missing" }

        val videoFile = File.createTempFile("motion-", ".mp4", cacheDirectory)
        val webpFile = File.createTempFile("discord-cover-", ".webp", cacheDirectory)
        try {
            downloadMotionVideo(videoUrl, videoFile)
            encodeAnimatedWebp(videoFile, webpFile)
            uploadWebp(webpFile, workerUrl, uploadSecret, songId, videoUrl)
        } finally {
            videoFile.delete()
            webpFile.delete()
        }
    }

    private suspend fun downloadMotionVideo(
        videoUrl: String,
        destination: File,
    ) {
        val connection = (URL(videoUrl).openConnection() as? HttpsURLConnection)
            ?: error("Expected an HTTPS motion-art URL")
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connect()

            val finalHost = connection.url.host.lowercase()
            require(isTrustedArtworkHost(finalHost)) { "Motion artwork redirected to an untrusted host" }
            require(connection.responseCode in 200..299) { "Motion-art download failed: HTTP ${connection.responseCode}" }
            val declaredLength = connection.getHeaderFieldLong("Content-Length", -1L)
            require(declaredLength <= MAX_SOURCE_BYTES) { "Motion-art video is too large" }

            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_SOURCE_BYTES) { "Motion-art video is too large" }
                        output.write(buffer, 0, read)
                    }
                    require(total > 0L) { "Motion-art video was empty" }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun encodeAnimatedWebp(
        source: File,
        destination: File,
    ) {
        val retriever = MediaMetadataRetriever()
        var writer: AnimatedWebpWriter? = null
        try {
            retriever.setDataSource(source.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: error("Could not read motion-art duration")
            val clipDurationMs = durationMs.coerceAtMost(MAX_CLIP_DURATION_MS)
            val frameCount = (clipDurationMs / MIN_FRAME_INTERVAL_MS).toInt().coerceIn(2, MAX_FRAME_COUNT)
            val frameDurationMs = (clipDurationMs / frameCount).toInt().coerceIn(80, 1_000)

            writer = AnimatedWebpWriter(destination, FRAME_SIZE, FRAME_SIZE)
            writer.setLoopCount(0)
            var writtenFrames = 0
            repeat(frameCount) { index ->
                currentCoroutineContext().ensureActive()
                val timestampMs = minOf((clipDurationMs * index / frameCount), durationMs - 1L)
                val original = retriever.getFrameAtTime(
                    timestampMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: return@repeat
                try {
                    val frame = centerCropSquare(original, FRAME_SIZE)
                    try {
                        writer.addFrame(frame, frameDurationMs)
                        writtenFrames++
                    } finally {
                        frame.recycle()
                    }
                } finally {
                    original.recycle()
                }
            }
            require(writtenFrames >= 2) { "Not enough motion-art frames could be decoded" }
        } finally {
            runCatching { writer?.close() }
            retriever.release()
        }
        require(destination.isFile && destination.length() in 1..MAX_OUTPUT_BYTES) {
            "Animated WebP exceeded the upload size limit"
        }
    }

    private fun centerCropSquare(
        source: Bitmap,
        size: Int,
    ): Bitmap {
        require(source.width > 0 && source.height > 0) { "Motion-art frame has invalid dimensions" }
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { result ->
            result.setHasAlpha(false)
            val canvas = Canvas(result)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            canvas.drawBitmap(
                source,
                Rect(left, top, left + side, top + side),
                Rect(0, 0, size, size),
                paint,
            )
        }
    }

    private suspend fun uploadWebp(
        file: File,
        workerUrl: String,
        uploadSecret: String,
        songId: String,
        videoUrl: String,
    ): String {
        val filename = "discord_${sha256("$songId|$videoUrl").take(48)}.webp"
        val endpoint = URL("${workerUrl.trim().trimEnd('/')}/?key=$filename")
        require(endpoint.protocol == "https") { "Worker URL must use HTTPS" }
        val connection = endpoint.openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = UPLOAD_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $uploadSecret")
            connection.setRequestProperty("Content-Type", "image/webp")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(file.length())

            FileInputStream(file).use { input ->
                connection.outputStream.use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }

            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            require(status in 200..299) { "Cloudflare Worker upload failed: HTTP $status" }
            val publicUrl = JSONObject(response).optString("url")
            require(publicUrl.startsWith("https://")) { "Worker did not return a valid public image URL" }
            return publicUrl
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun isTrustedArtworkHost(host: String): Boolean =
        host == "itunes.apple.com" ||
            host.endsWith(".apple.com") ||
            host.endsWith(".mzstatic.com") ||
            host.endsWith(".akamaized.net")

    private companion object {
        const val FRAME_SIZE = 256
        const val MAX_FRAME_COUNT = 12
        const val MIN_FRAME_INTERVAL_MS = 100L
        const val MAX_CLIP_DURATION_MS = 3_000L
        const val MAX_SOURCE_BYTES = 32L * 1024L * 1024L
        const val MAX_OUTPUT_BYTES = 15L * 1024L * 1024L
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val UPLOAD_TIMEOUT_MS = 30_000
        const val USER_AGENT = "Metrolist/DiscordAnimatedCover"
    }
}

/**
 * Minimal animated WebP RIFF writer. Each frame is encoded by Android's
 * native still-WebP encoder and wrapped in an ANMF animation frame chunk.
 */
private class AnimatedWebpWriter(
    file: File,
    private val width: Int,
    private val height: Int,
) : AutoCloseable {
    private val output = java.io.RandomAccessFile(file, "rw")
    private var closed = false

    init {
        require(width in 1..16_777_216 && height in 1..16_777_216)
        output.setLength(0)
        writeFourCc("RIFF")
        writeUInt32(0L)
        writeFourCc("WEBP")

        writeFourCc("VP8X")
        writeUInt32(10L)
        output.write(ANIMATION_FLAG)
        output.write(0)
        output.write(0)
        output.write(0)
        writeUInt24(width - 1)
        writeUInt24(height - 1)

        writeFourCc("ANIM")
        writeUInt32(6L)
        writeUInt32(0xFFFFFFFFL)
        writeUInt16(0)
    }

    fun setLoopCount(loops: Int) {
        val position = output.filePointer
        output.seek(42L)
        writeUInt16(loops.coerceIn(0, 65_535))
        output.seek(position)
    }

    fun addFrame(
        bitmap: Bitmap,
        durationMs: Int,
    ) {
        check(!closed) { "Encoder is already closed" }
        val encoded = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, encoded)) {
            "Android could not encode a WebP frame"
        }
        val (codec, payload) = extractStillWebpPayload(encoded.toByteArray())
        val bitstreamPadding = payload.size and 1
        val frameChunkSize = payload.size.toLong() + 24L + bitstreamPadding

        writeFourCc("ANMF")
        writeUInt32(frameChunkSize)
        writeUInt24(0)
        writeUInt24(0)
        writeUInt24(width - 1)
        writeUInt24(height - 1)
        writeUInt24(durationMs.coerceIn(1, 16_777_215))
        output.write(0)
        writeFourCc(codec)
        writeUInt32(payload.size.toLong())
        output.write(payload)
        if (bitstreamPadding != 0) output.write(0)
    }

    override fun close() {
        if (closed) return
        closed = true
        val fileSize = output.length()
        output.seek(4L)
        writeUInt32(fileSize - 8L)
        output.close()
    }

    private fun extractStillWebpPayload(bytes: ByteArray): Pair<String, ByteArray> {
        require(bytes.size >= 20 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF") {
            "Android returned an invalid WebP frame"
        }
        require(bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP") {
            "Android returned an invalid WebP header"
        }
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val codec = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
            val size = readUInt32(bytes, offset + 4)
            val payloadStart = offset + 8
            val payloadEnd = payloadStart.toLong() + size
            if (codec == "VP8 " || codec == "VP8L") {
                require(payloadEnd <= bytes.size) { "WebP frame payload was truncated" }
                return codec to bytes.copyOfRange(payloadStart, payloadEnd.toInt())
            }
            offset = payloadEnd.toInt() + (size and 1L).toInt()
        }
        error("Could not find a VP8 frame in Android's WebP output")
    }

    private fun writeFourCc(value: String) {
        output.write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun writeUInt16(value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
    }

    private fun writeUInt24(value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write((value ushr 16) and 0xFF)
    }

    private fun writeUInt32(value: Long) {
        for (shift in 0..24 step 8) output.write(((value ushr shift) and 0xFF).toInt())
    }

    private fun readUInt32(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private companion object {
        const val WEBP_QUALITY = 76
        const val ANIMATION_FLAG = 0x02
    }
}
