package com.example.ultrastarandroidtv.visual

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.TextureView
import com.example.ultrastarandroidtv.audio.SpectrumTap

private const val TAG = "Visualizer"

/**
 * A [TextureView] that runs the visualiser on its own OpenGL thread.
 *
 * **A TextureView rather than a `GLSurfaceView`**, for the same reason the song video is one: a
 * SurfaceView is a separate compositor layer punched *behind* the window, and this has to sit
 * inside the gameplay screen with the track and the scores drawn over it. `GLSurfaceView` would
 * also have brought its own thread and lifecycle to argue with Compose's.
 *
 * The EGL setup is the boilerplate price of that choice and is the only interesting thing here —
 * everything about how the picture looks lives in [VisualizerRenderer].
 */
@SuppressLint("ViewConstructor")
class VisualizerView(context: Context, tap: SpectrumTap) : TextureView(context) {

    private val renderer = VisualizerRenderer(tap)
    private var thread: RenderThread? = null

    init {
        isOpaque = true
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                thread = RenderThread(surface, width, height).also { it.start() }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                thread?.resize(width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                // Wait for the thread to let go of the SurfaceTexture before saying it may be
                // released, or the driver is handed a texture that is still current somewhere.
                thread?.finish()
                thread = null
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    /** Stops rendering. Safe to call more than once. */
    fun stop() {
        thread?.finish()
        thread = null
    }

    private inner class RenderThread(
        private val surface: SurfaceTexture,
        @Volatile private var width: Int,
        @Volatile private var height: Int,
    ) : Thread("visualizer-gl") {

        @Volatile
        private var running = true

        @Volatile
        private var resized = true

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        fun resize(width: Int, height: Int) {
            this.width = width
            this.height = height
            resized = true
        }

        fun finish() {
            running = false
            runCatching { join(1000) }
        }

        override fun run() {
            if (!setUpEgl()) return
            renderer.onSurfaceCreated()

            var lastNanos = System.nanoTime()
            while (running) {
                if (resized) {
                    resized = false
                    renderer.onSurfaceChanged(width, height)
                }

                val now = System.nanoTime()
                val step = ((now - lastNanos) / 1e9).toFloat()
                lastNanos = now

                renderer.onDrawFrame(step)

                // Swapping is what paces this loop: the driver blocks here until the display is
                // ready for the next frame, so there is no sleep to tune and no busy waiting.
                if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                    Log.w(TAG, "swap failed, stopping")
                    running = false
                }
            }

            renderer.release()
            tearDownEgl()
        }

        private fun setUpEgl(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

            val configAttributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) ||
                count[0] == 0
            ) {
                return false
            }

            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return false

            eglSurface = EGL14.eglCreateWindowSurface(
                display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false

            return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
        }

        private fun tearDownEgl() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }
}
