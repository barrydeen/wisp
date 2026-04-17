@file:Suppress("DEPRECATION") // android.graphics.Movie is the only public GIF-frame API

package com.wisp.app.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

// Uses the deprecated `android.graphics.Movie` API — it's the only public Android class that exposes
// per-frame GIF decoding at arbitrary timestamps. Still functional on all supported API levels.
object GifToMp4Converter {

    private const val MIME = "video/avc"
    private const val MAX_DIM = 1920
    private const val FPS = 20
    private const val I_FRAME_INTERVAL_S = 1
    private const val TIMEOUT_US = 10_000L

    suspend fun convert(gifBytes: ByteArray, context: Context): Triple<ByteArray, String, String> =
        withContext(Dispatchers.Default) {
            @Suppress("DEPRECATION")
            val movie = Movie.decodeByteArray(gifBytes, 0, gifBytes.size)
                ?: throw IllegalArgumentException("Not a valid GIF")

            val srcW = movie.width().coerceAtLeast(1)
            val srcH = movie.height().coerceAtLeast(1)
            val durationMs = movie.duration().let { if (it <= 0) 100 else it }

            val (outW, outH) = fitWithin(srcW, srcH, MAX_DIM).toEven()

            val tempFile = File.createTempFile("gif2mp4_", ".mp4", context.cacheDir)
            try {
                encode(movie, srcW, srcH, outW, outH, durationMs, tempFile)
                Triple(tempFile.readBytes(), "video/mp4", "mp4")
            } finally {
                tempFile.delete()
            }
        }

    private fun encode(
        @Suppress("DEPRECATION") movie: Movie,
        srcW: Int,
        srcH: Int,
        outW: Int,
        outH: Int,
        durationMs: Int,
        outFile: File,
    ) {
        val format = MediaFormat.createVideoFormat(MIME, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, estimateBitrate(outW, outH))
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
        }

        val encoder = MediaCodec.createEncoderByType(MIME)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val state = MuxState(muxer = muxer)

            val egl = EglCore(inputSurface)
            try {
                val bitmap = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val renderer = TextureRenderer()
                renderer.init()

                val frameCount = max(1, (durationMs * FPS / 1000))
                val frameDurationUs = 1_000_000L / FPS

                for (i in 0 until frameCount) {
                    val presentationTimeUs = i * frameDurationUs
                    val movieTimeMs = ((i.toLong() * durationMs) / frameCount).toInt().coerceIn(0, durationMs)
                    movie.setTime(movieTimeMs)

                    canvas.drawColor(Color.BLACK)
                    movie.draw(canvas, 0f, 0f)

                    renderer.drawBitmap(bitmap, outW, outH)
                    egl.setPresentationTime(presentationTimeUs * 1000L) // ns
                    egl.swap()

                    drain(encoder, state, endOfStream = false)
                }

                encoder.signalEndOfInputStream()
                drain(encoder, state, endOfStream = true)

                bitmap.recycle()
                renderer.release()
            } finally {
                egl.release()
                inputSurface.release()
                try {
                    if (state.muxerStarted) muxer.stop()
                } catch (_: Exception) { /* best effort */ }
                muxer.release()
            }
        } finally {
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
        }
    }

    private class MuxState(val muxer: MediaMuxer) {
        var trackIndex = -1
        var muxerStarted = false
    }

    private fun drain(encoder: MediaCodec, state: MuxState, endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (state.muxerStarted) throw IllegalStateException("format changed after muxer start")
                    state.trackIndex = state.muxer.addTrack(encoder.outputFormat)
                    state.muxer.start()
                    state.muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val buf = encoder.getOutputBuffer(outputIndex)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && state.muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        state.muxer.writeSampleData(state.trackIndex, buf, info)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    private fun estimateBitrate(w: Int, h: Int): Int {
        // ~4 bits per pixel per frame at FPS — empirically decent for motion graphics.
        val bpp = 4
        return (w.toLong() * h.toLong() * FPS * bpp).toInt().coerceIn(256_000, 8_000_000)
    }

    private fun fitWithin(w: Int, h: Int, maxDim: Int): Pair<Int, Int> {
        val longest = max(w, h)
        if (longest <= maxDim) return w to h
        val scale = maxDim.toFloat() / longest.toFloat()
        return ((w * scale).roundToInt().coerceAtLeast(2)) to ((h * scale).roundToInt().coerceAtLeast(2))
    }

    private fun Pair<Int, Int>.toEven(): Pair<Int, Int> {
        val a = if (first % 2 == 0) first else first - 1
        val b = if (second % 2 == 0) second else second - 1
        return max(a, 2) to max(b, 2)
    }

    // -- EGL + GL helpers ---------------------------------------------------------------------

    private class EglCore(surface: Surface) {
        private val display: EGLDisplay
        private val context: EGLContext
        private val eglSurface: EGLSurface

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display === EGL14.EGL_NO_DISPLAY) throw RuntimeException("no EGL display")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw RuntimeException("eglInitialize failed")

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0))
                throw RuntimeException("eglChooseConfig failed")
            val config = configs[0] ?: throw RuntimeException("no EGL config")

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, surfaceAttribs, 0)
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
                throw RuntimeException("eglMakeCurrent failed")
        }

        fun setPresentationTime(ns: Long) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, ns)
        }

        fun swap() {
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            if (display !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, eglSurface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    private class TextureRenderer {
        private var program = 0
        private var textureId = 0
        private var aPositionLoc = 0
        private var aTexCoordLoc = 0
        private var uMvpLoc = 0
        private var uTexLoc = 0
        private val mvp = FloatArray(16)
        private val vertexBuf: FloatBuffer
        private val texCoordBuf: FloatBuffer

        init {
            val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            vertexBuf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(verts); position(0)
            }
            // Flip V: OpenGL texture origin is bottom-left; Bitmap is top-left.
            val tex = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            texCoordBuf = ByteBuffer.allocateDirect(tex.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(tex); position(0)
            }
            Matrix.setIdentityM(mvp, 0)
        }

        fun init() {
            program = compileProgram(VS, FS)
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uMvpLoc = GLES20.glGetUniformLocation(program, "uMvp")
            uTexLoc = GLES20.glGetUniformLocation(program, "uTex")

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        fun drawBitmap(bitmap: Bitmap, viewportW: Int, viewportH: Int) {
            GLES20.glViewport(0, 0, viewportW, viewportH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glUniform1i(uTexLoc, 0)
            GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvp, 0)

            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuf)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        }

        fun release() {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            program = 0
            textureId = 0
        }

        companion object {
            private const val VS = """
                uniform mat4 uMvp;
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = uMvp * aPosition;
                    vTexCoord = aTexCoord;
                }
            """
            private const val FS = """
                precision mediump float;
                uniform sampler2D uTex;
                varying vec2 vTexCoord;
                void main() {
                    gl_FragColor = texture2D(uTex, vTexCoord);
                }
            """

            private fun compileShader(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val status = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
                if (status[0] == 0) {
                    val log = GLES20.glGetShaderInfoLog(shader)
                    GLES20.glDeleteShader(shader)
                    throw RuntimeException("shader compile failed: $log")
                }
                return shader
            }

            fun compileProgram(vs: String, fs: String): Int {
                val vsId = compileShader(GLES20.GL_VERTEX_SHADER, vs)
                val fsId = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
                val program = GLES20.glCreateProgram()
                GLES20.glAttachShader(program, vsId)
                GLES20.glAttachShader(program, fsId)
                GLES20.glLinkProgram(program)
                val status = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
                GLES20.glDeleteShader(vsId)
                GLES20.glDeleteShader(fsId)
                if (status[0] == 0) {
                    val log = GLES20.glGetProgramInfoLog(program)
                    GLES20.glDeleteProgram(program)
                    throw RuntimeException("program link failed: $log")
                }
                return program
            }
        }
    }
}
