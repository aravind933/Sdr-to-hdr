package com.example.sdr2hdr

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * SDR (BT.709) video -> optional 3D LUT -> HLG HDR (BT.2020, 10-bit HEVC) in an MP4.
 *
 * transcode() blocks, so call it from a background thread (e.g. withContext(Dispatchers.Default)
 * or a foreground service worker).
 */
class SdrToHdrTranscoder(private val context: Context) {

    data class Options(
        val lut: CubeLut? = null,
        val lutStrength: Float = 1f,
        val expand: Float = 0.25f,
        val bitrate: Int? = null          // bits/sec; null = estimated from resolution
    )

    fun transcode(
        input: Uri,
        outputPath: String,
        options: Options = Options(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ) {
        require(Build.VERSION.SDK_INT >= 33) {
            "10-bit HDR surface encoding needs Android 13 (API 33) or newer"
        }

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var egl: EglCore? = null
        var renderer: GlRenderer? = null
        var surfaceTexture: SurfaceTexture? = null
        var decoderSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        val frameThread = HandlerThread("frame-callbacks").also { it.start() }

        try {
            extractor.setDataSource(context, input, null)

            // ---- Find tracks --------------------------------------------------------------
            var videoTrack = -1
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (videoTrack < 0 && mime.startsWith("video/")) videoTrack = i
                else if (audioTrack < 0 && mime == MediaFormat.MIMETYPE_AUDIO_AAC) audioTrack = i
            }
            require(videoTrack >= 0) { "No video track found" }
            extractor.selectTrack(videoTrack)

            val inFormat = extractor.getTrackFormat(videoTrack)
            val inTransfer = inFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER, 0)
            require(
                inTransfer != MediaFormat.COLOR_TRANSFER_ST2084 &&
                    inTransfer != MediaFormat.COLOR_TRANSFER_HLG
            ) { "Input is already HDR" }

            val width = inFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = inFormat.getInteger(MediaFormat.KEY_ROTATION, 0)
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION))
                inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val fps = runCatching { inFormat.getNumber(MediaFormat.KEY_FRAME_RATE)?.toInt() }
                .getOrNull()?.takeIf { it > 0 } ?: 30
            val bitrate = options.bitrate ?: (width.toLong() * height * fps * 0.12).toInt()
                .coerceIn(4_000_000, 80_000_000)

            // Keep frames unrotated through the pipeline and apply rotation as muxer metadata.
            inFormat.setInteger(MediaFormat.KEY_ROTATION, 0)

            // ---- Encoder: HEVC Main10, BT.2020 / HLG ---------------------------------------
            val outFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC, width, height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(outFormat)
            checkNotNull(encoderName) { "This device has no HEVC Main10 encoder for ${width}x$height" }

            encoder = MediaCodec.createByCodecName(encoderName)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = encoder.createInputSurface()

            // ---- GL: 10-bit EGL surface on the encoder input -------------------------------
            egl = EglCore()
            egl.createWindowSurface(encoderInputSurface)
            egl.makeCurrent()
            renderer = GlRenderer(options.lut)

            // ---- Decoder -> SurfaceTexture -------------------------------------------------
            val frameAvailable = Semaphore(0)
            surfaceTexture = SurfaceTexture(renderer.oesTextureId)
            surfaceTexture.setOnFrameAvailableListener({ frameAvailable.release() }, Handler(frameThread.looper))
            decoderSurface = Surface(surfaceTexture)

            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inFormat, decoderSurface, null, 0)

            // ---- Muxer ---------------------------------------------------------------------
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)
            var muxVideoTrack = -1
            var muxAudioTrack = -1

            decoder.start()
            encoder.start()

            // ---- Main loop -----------------------------------------------------------------
            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            val texMatrix = FloatArray(16)
            var inputDone = false
            var decoderDone = false
            var encoderDone = false

            while (!encoderDone) {
                if (isCancelled()) throw CancellationException("Cancelled")

                // 1. Feed compressed samples to the decoder
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2. Pull decoded frames, render them through the shader into the encoder
                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, 10_000)
                    if (outIdx >= 0) {
                        val render = decInfo.size > 0
                        decoder.releaseOutputBuffer(outIdx, render)
                        if (render) {
                            check(frameAvailable.tryAcquire(2, TimeUnit.SECONDS)) { "Decoder frame timeout" }
                            surfaceTexture.updateTexImage()
                            surfaceTexture.getTransformMatrix(texMatrix)
                            renderer.draw(texMatrix, width, height, options.lutStrength, options.expand)
                            egl.setPresentationTimeNanos(decInfo.presentationTimeUs * 1000L)
                            egl.swapBuffers()
                            if (durationUs > 0) {
                                onProgress((decInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                        }
                        if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                // 3. Drain the encoder into the muxer
                val encTimeout = if (decoderDone) 10_000L else 0L
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(encInfo, encTimeout)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted) { "Format changed twice" }
                        muxVideoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioTrack >= 0) {
                            muxAudioTrack = runCatching {
                                muxer.addTrack(extractor.getTrackFormat(audioTrack))
                            }.getOrDefault(-1)
                        }
                        muxer.start()
                        muxerStarted = true
                        continue
                    }
                    if (idx >= 0) {
                        val data = encoder.getOutputBuffer(idx)!!
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                        if (encInfo.size > 0 && muxerStarted) {
                            data.position(encInfo.offset)
                            data.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(muxVideoTrack, data, encInfo)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                            break
                        }
                    }
                }
            }

            // ---- Copy audio untouched ------------------------------------------------------
            if (muxerStarted && muxAudioTrack >= 0 && audioTrack >= 0) {
                copyAudio(input, audioTrack, muxer, muxAudioTrack)
            }
            onProgress(1f)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { surfaceTexture?.release() }
            runCatching { decoderSurface?.release() }
            runCatching { renderer?.release() }
            runCatching { egl?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            frameThread.quitSafely()
        }
    }

    private fun copyAudio(input: Uri, trackIndex: Int, muxer: MediaMuxer, muxTrack: Int) {
        val audio = MediaExtractor()
        try {
            audio.setDataSource(context, input, null)
            audio.selectTrack(trackIndex)
            val format = audio.getTrackFormat(trackIndex)
            val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 256 * 1024
            val buffer = ByteBuffer.allocateDirect(maxSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = audio.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(
                    0, size, audio.sampleTime,
                    if (audio.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                )
                muxer.writeSampleData(muxTrack, buffer, info)
                audio.advance()
            }
        } finally {
            audio.release()
        }
    }
}
