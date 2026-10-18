package dev.dsh.pocket.cowork.broker;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** GPU-to-hardware-encoder path for the live view. The virtual display composes straight into the encoder's
 *  input Surface (no CPU pixel copy, no JPEG); the CPU only moves the already-compressed H.264 access units.
 *  Messages handed to the socket are [type:1][payload]:
 *  CONFIG = SPS/PPS (Annex B), KEY/DELTA = [pts us:8 big-endian][access unit], INFO = JSON, STILL = PNG. */
final class LiveStream implements AutoCloseable {
    static final int INFO = 1, CONFIG = 2, KEY = 3, DELTA = 4, STILL = 5;
    private static final int MAX_QUEUE = 90;
    private final int width, height, fps;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private MediaCodec codec;
    private Surface input;
    private Thread pump;
    private volatile boolean running;
    private boolean dropUntilKey;
    private byte[] config;

    LiveStream(int width, int height, int fps) { this.width = width; this.height = height; this.fps = fps; }

    static byte[] message(int type, byte[] payload) {
        byte[] out = new byte[payload.length + 1];
        out[0] = (byte) type;
        System.arraycopy(payload, 0, out, 1, payload.length);
        return out;
    }

    byte[] info() throws Exception {
        return message(INFO, new org.json.JSONObject().put("w", width).put("h", height).put("fps", fps).put("codec", "avc").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Starts the encoder and returns the Surface the virtual display must render into. */
    synchronized Surface start() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        // A static screen emits nothing; one repeat per 500 ms keeps a joining viewer and a sync request satisfiable.
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L);
        try { format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0); } catch (Throwable ignored) {}
        try { format.setInteger(MediaFormat.KEY_LATENCY, 1); } catch (Throwable ignored) {}
        try { format.setInteger(MediaFormat.KEY_PRIORITY, 0); } catch (Throwable ignored) {}
        try { format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps); } catch (Throwable ignored) {}
        MediaCodec created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = created.createInputSurface();
            created.start();
        } catch (Exception e) { created.release(); input = null; throw e; }
        codec = created;
        running = true;
        pump = new Thread(this::pump, "cowork-live-encoder");
        pump.setDaemon(true);
        pump.start();
        return input;
    }

    private void pump() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        final MediaCodec codec = this.codec;
        while (running && codec != null) {
            int index;
            try { index = codec.dequeueOutputBuffer(info, 100_000); } catch (Exception e) { break; }
            if (index < 0) continue;
            try {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    byte[] data = new byte[info.size];
                    buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(data);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        synchronized (this) { config = data; offer(message(CONFIG, data), true); }
                    } else {
                        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        byte[] payload = new byte[8 + data.length];
                        long pts = info.presentationTimeUs;
                        for (int i = 0; i < 8; i++) payload[i] = (byte) (pts >>> (56 - 8 * i));
                        System.arraycopy(data, 0, payload, 8, data.length);
                        synchronized (this) { offer(message(key ? KEY : DELTA, payload), key); }
                    }
                }
            } finally { try { codec.releaseOutputBuffer(index, false); } catch (Exception ignored) {} }
        }
    }

    /** Called with the lock held. A slow reader never blocks the encoder: it drops to the next key frame instead. */
    private void offer(byte[] message, boolean key) {
        if (dropUntilKey && !key) return;
        if (key) dropUntilKey = false;
        if (queue.size() >= MAX_QUEUE) {
            queue.clear();
            if (config != null) queue.add(message(CONFIG, config));
            if (!key) { dropUntilKey = true; requestSyncLocked(); return; }
        }
        queue.add(message);
        notifyAll();
    }

    /** Next message or null after the timeout. */
    synchronized byte[] take(long timeoutMs) throws InterruptedException {
        long until = SystemClock.elapsedRealtime() + timeoutMs;
        while (queue.isEmpty() && running) {
            long left = until - SystemClock.elapsedRealtime();
            if (left <= 0) return null;
            wait(left);
        }
        return queue.poll();
    }

    synchronized void requestSync() { requestSyncLocked(); }
    private void requestSyncLocked() {
        try {
            if (codec == null) return;
            Bundle b = new Bundle(); b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(b);
        } catch (Exception ignored) {}
    }

    @Override public void close() {
        MediaCodec c; Surface s; Thread t;
        synchronized (this) { running = false; c = codec; s = input; t = pump; codec = null; input = null; queue.clear(); notifyAll(); }
        if (t != null) { try { t.join(500); } catch (InterruptedException ignored) {} }
        if (c != null) { try { c.stop(); } catch (Exception ignored) {} try { c.release(); } catch (Exception ignored) {} }
        if (s != null) s.release();
    }
}
