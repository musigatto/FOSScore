package com.musigatto.fosscore.sound

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min

private const val LOG_TAG = "FOSScore-SoundBank"

// how long the decoder waits for a free buffer, in microseconds
private const val DECODE_WAIT_US = 10_000L

/**
 * The app's sound bank, GeneralUser GS by S. Christian Collins (its licence is next to it in the
 * assets), in SF3: its samples are Ogg Vorbis streams (9 MB instead of 32). The bank is mapped
 * from the APK, where it is stored uncompressed, so it is never copied into the app's memory. A
 * sample is decoded by Android's own Vorbis decoder the first time a piece plays it, then kept in
 * the app's cache: the next pieces read it straight from there.
 */
object SoundBank {
    private const val ASSET = "soundfont/GeneralUser-GS.sf3"

    // the decoded samples of this version of the bank
    private const val CACHE = "soundbank/GeneralUser-GS-2.0.3"

    @Volatile private var font: SoundFont? = null

    /** The bank, read on the first call (a few tens of milliseconds): call it off the main thread. */
    fun load(context: Context): SoundFont = font ?: synchronized(this) {
        font ?: context.assets.openFd(ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                SoundFont.read(channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length))
            }
        }.also { read ->
            val cache = File(context.cacheDir, CACHE)
            read.decoder = { index, ogg -> cached(cache, index) ?: decode(ogg).also { keep(cache, index, it) } }
            font = read
        }
    }

    private fun cached(dir: File, index: Int): ShortArray? = runCatching {
        val file = File(dir, "$index.pcm").takeIf { it.isFile } ?: return null
        val bytes = file.readBytes()
        ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }.getOrNull()

    // written aside first then renamed: a reader never gets half a file
    private fun keep(dir: File, index: Int, pcm: ShortArray) {
        runCatching {
            dir.mkdirs()
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(pcm)
            val part = File(dir, "$index.part")
            part.writeBytes(bytes.array())
            part.renameTo(File(dir, "$index.pcm"))
        }
    }

    // One Ogg Vorbis stream into 16-bit values, by Android's decoder (no code of our own).
    private fun decode(ogg: ByteBuffer): ShortArray {
        val bytes = ByteArray(ogg.remaining()).also { ogg.duplicate().get(it) }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val n = min(size, bytes.size - position.toInt())
                    System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                    return n
                }

                override fun getSize(): Long = bytes.size.toLong()

                override fun close() = Unit
            })
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return ShortArray(0)
            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()
            val out = ArrayList<ShortArray>()
            var total = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(DECODE_WAIT_US)
                    if (i >= 0) {
                        val buffer = decoder.getInputBuffer(i) ?: continue
                        val n = extractor.readSampleData(buffer, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, DECODE_WAIT_US)
                if (o >= 0) {
                    decoder.getOutputBuffer(o)?.let { buffer ->
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val part = ShortArray(info.size / 2)
                        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(part)
                        out += part
                        total += part.size
                    }
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val pcm = ShortArray(total)
            var at = 0
            for (part in out) {
                part.copyInto(pcm, at)
                at += part.size
            }
            return pcm
        } catch (e: Exception) {
            // a sample that cannot be decoded is silent: better than no music at all
            Log.e(LOG_TAG, "sample not decoded: ${e.message}", e)
            return ShortArray(0)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
