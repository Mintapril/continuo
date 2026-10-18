package dev.dsh.pocket.cowork

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * Hardware H.264 decoder for the owned-display live view. The broker's encoder reads the virtual display's GPU
 * output; here the decoder renders straight into a [Surface] (TextureView), so no frame ever becomes a Bitmap or
 * touches the CPU after entropy decoding.
 *
 * Several views may want the picture (island preview, full-screen intervention, Activity); the highest priority
 * wins (newest registration on a tie) and the previous one takes over again when it is removed. With no surface the decoder is released
 * and the stream keeps running only for bookkeeping.
 */
object LiveVideo {
    data class Info(val width: Int, val height: Int, val fps: Int)

    private val infoState = MutableStateFlow<Info?>(null)
    /** Size/rate announced by the broker; null while no stream is attached. */
    val info = infoState.asStateFlow()

    /** Set by [CoworkConnection]: ask the broker for a key frame (new surface, dropped input). */
    @Volatile var requestKeyFrame: () -> Unit = {}

    private val lock = Any()
    private class Sink(val owner: Any, val surface: Surface, val priority: Int)
    private val sinks = ArrayList<Sink>()
    /** Highest priority wins, the newest registration breaks ties: the full-screen intervention view must beat the island preview. */
    private fun best(): Surface? { var pick: Sink? = null; for (s in sinks) if (pick == null || s.priority >= pick.priority) pick = s; return pick?.surface }
    private var config: ByteArray? = null
    private var codec: MediaCodec? = null
    private var decoderSurface: Surface? = null
    private var output: Thread? = null
    private var waitingForKey = true

    fun addSink(owner: Any, surface: Surface, priority: Int = 0) {
        synchronized(lock) {
            sinks.removeAll { it.owner === owner }
            sinks.add(Sink(owner, surface, priority))
            retarget()
        }
    }

    fun removeSink(owner: Any) {
        synchronized(lock) {
            if (sinks.removeAll { it.owner === owner }) retarget()
        }
    }

    fun onInfo(value: Info) {
        synchronized(lock) {
            if (infoState.value != value) { infoState.value = value; releaseDecoder() }
            startDecoderIfPossible()
        }
    }

    fun onConfig(bytes: ByteArray) {
        synchronized(lock) {
            if (config?.contentEquals(bytes) == true) return
            config = bytes
            releaseDecoder()
            startDecoderIfPossible()
        }
    }

    /** One H.264 access unit; [offset]..[offset]+[size] of [data]. */
    fun onUnit(key: Boolean, pts: Long, data: ByteArray, offset: Int, size: Int) {
        var resync = false
        synchronized(lock) {
            val c = codec ?: return
            if (waitingForKey) { if (!key) return; waitingForKey = false }
            try {
                val index = c.dequeueInputBuffer(10_000)
                if (index < 0) { waitingForKey = true; resync = true }
                else {
                    val buffer = c.getInputBuffer(index) ?: return
                    buffer.clear()
                    if (size > buffer.capacity()) { c.queueInputBuffer(index, 0, 0, pts, 0); waitingForKey = true; resync = true }
                    else { buffer.put(data, offset, size); c.queueInputBuffer(index, 0, size, pts, 0) }
                }
            } catch (_: Exception) {
                releaseDecoder(); resync = true
                startDecoderIfPossible()
            }
        }
        if (resync) requestKeyFrame()
    }

    /** The stream ended (link lost, viewer detached): drop all state so the next stream starts clean. */
    fun reset() {
        synchronized(lock) {
            releaseDecoder()
            config = null
            infoState.value = null
        }
    }

    // --- decoder lifecycle; all below run under [lock] ---

    private fun retarget() {
        val surface = best()
        val c = codec
        if (surface == null) { releaseDecoder(); return }
        if (c != null && decoderSurface !== surface) {
            try { c.setOutputSurface(surface); decoderSurface = surface; waitingForKey = true; requestKeyFrame() }
            catch (_: Exception) { releaseDecoder(); startDecoderIfPossible() }
        } else if (c == null) startDecoderIfPossible()
    }

    private fun startDecoderIfPossible() {
        if (codec != null) return
        val surface = best() ?: return
        val size = infoState.value ?: return
        val csd = config ?: return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height)
            val (sps, pps) = splitConfig(csd)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            if (pps.isNotEmpty()) format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            runCatching { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1) }
            runCatching { format.setInteger(MediaFormat.KEY_PRIORITY, 0) }
            runCatching { format.setInteger(MediaFormat.KEY_OPERATING_RATE, size.fps) }
            val created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                created.configure(format, surface, null, 0)
                created.start()
            } catch (e: Exception) { runCatching { created.release() }; throw e }
            codec = created
            decoderSurface = surface
            waitingForKey = true
            val thread = Thread({ drain(created) }, "cowork-live-decoder")
            thread.isDaemon = true
            output = thread
            thread.start()
            requestKeyFrame()
        } catch (_: Exception) {
            codec = null; decoderSurface = null
        }
    }

    private fun drain(c: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            try {
                val index = c.dequeueOutputBuffer(bufferInfo, 50_000)
                if (index >= 0) c.releaseOutputBuffer(index, true) // render the moment it is decoded
            } catch (_: Exception) { return } // released or failed: onUnit/retarget start a new one
        }
    }

    private fun releaseDecoder() {
        val c = codec ?: return
        codec = null; decoderSurface = null
        val thread = output; output = null
        runCatching { c.stop() }
        runCatching { c.release() }
        if (thread != null && thread !== Thread.currentThread()) runCatching { thread.join(200) }
    }

    /** SPS/PPS arrive as one Annex-B blob; the decoder wants them as separate csd buffers. */
    private fun splitConfig(blob: ByteArray): Pair<ByteArray, ByteArray> {
        var second = -1
        var i = 4
        while (i + 3 < blob.size) {
            if (blob[i].toInt() == 0 && blob[i + 1].toInt() == 0 && (blob[i + 2].toInt() == 1 || (blob[i + 2].toInt() == 0 && blob[i + 3].toInt() == 1))) { second = i; break }
            i++
        }
        return if (second < 0) blob to ByteArray(0) else blob.copyOfRange(0, second) to blob.copyOfRange(second, blob.size)
    }
}

/** TextureView that registers its surface as the live picture while it is available. */
class LiveTextureView(context: android.content.Context, private val priority: Int = 0) : TextureView(context), TextureView.SurfaceTextureListener {
    private var surface: Surface? = null

    init {
        isOpaque = false // transparent until the first video frame so a poster behind it shows through
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(texture)
        surface = s
        LiveVideo.addSink(this, s, priority)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        LiveVideo.removeSink(this)
        surface?.release(); surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
}

