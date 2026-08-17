package com.example.ultrastarandroidtv.visual

import android.opengl.GLES20
import com.example.ultrastarandroidtv.audio.SpectrumTap
import com.example.ultrastarandroidtv.audio.WAVE_POINTS
import kotlin.math.cos
import kotlin.math.sin

/**
 * Height of the feedback buffer. Width follows the surface's aspect.
 *
 * Deliberately far below the screen. Every frame is a copy of the last one, so any sharpness put
 * in is smeared away within a few frames regardless — and the softness that comes free from
 * scaling 540 lines up to 1080 is the look, not a compromise. It also means the whole effect
 * costs a fraction of a screen's worth of pixels.
 */
private const val BUFFER_HEIGHT = 540

/** Seconds a preset holds, and how long it takes to become the next one. */
private const val PRESET_SECONDS = 22f
private const val BLEND_SECONDS = 4f

/** Longest frame step believed to be real; past this the app was stalled, not slow. */
private const val MAX_STEP = 0.05f

private const val VERTEX_PASS = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

/**
 * The feedback pass: the previous frame, moved.
 *
 * This one shader is the entire reason the result looks like MilkDrop rather than like a graph.
 * Nothing here draws the music — it samples the frame before it at slightly wrong coordinates and
 * dims it slightly, and repeating that sixty times a second turns anything drawn on top into a
 * tunnel, a spiral or a plume depending only on how the coordinates are wrong.
 */
private const val FRAGMENT_WARP = """
precision mediump float;
uniform sampler2D uPrev;
uniform float uZoom;
uniform float uRotate;
uniform float uWarp;
uniform float uWarpScale;
uniform float uTime;
uniform float uDecay;
uniform float uAspect;
varying vec2 vUv;

void main() {
    // Work in centred coordinates with the aspect taken out, so a rotation is a rotation rather
    // than a shear, and a circle stays a circle on a 16:9 screen.
    vec2 p = (vUv - 0.5) * vec2(uAspect, 1.0);

    float c = cos(uRotate);
    float s = sin(uRotate);
    p = mat2(c, -s, s, c) * p;
    p /= uZoom;

    p += uWarp * vec2(
        sin(p.y * uWarpScale + uTime * 0.7),
        cos(p.x * uWarpScale + uTime * 0.9)
    );

    vec2 uv = p / vec2(uAspect, 1.0) + 0.5;
    gl_FragColor = vec4(texture2D(uPrev, uv).rgb * uDecay, 1.0);
}
"""

/** The waveform ribbon, drawn additively so overlaps burn brighter instead of covering. */
private const val VERTEX_WAVE = """
attribute vec2 aPos;
attribute float aT;
varying float vT;
void main() {
    vT = aT;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

private const val FRAGMENT_WAVE = """
precision mediump float;
uniform vec3 uFrom;
uniform vec3 uTo;
uniform float uGain;
varying float vT;
void main() {
    gl_FragColor = vec4(mix(uFrom, uTo, vT) * uGain, 1.0);
}
"""

/**
 * The final pass to the screen.
 *
 * A gentle curve lifts the dim trails without blowing out the bright core, and a vignette keeps
 * the corners from competing with the lyrics. Nothing here feeds back, so it is free to be as
 * opinionated as it likes.
 */
private const val FRAGMENT_PRESENT = """
precision mediump float;
uniform sampler2D uFrame;
uniform float uFlash;
varying vec2 vUv;
void main() {
    vec3 c = texture2D(uFrame, vUv).rgb;
    c = pow(c, vec3(0.85)) * (1.0 + uFlash);
    float d = distance(vUv, vec2(0.5));
    c *= 1.0 - 0.55 * d * d;
    gl_FragColor = vec4(c, 1.0);
}
"""

/**
 * Draws the music, full screen, for songs with no video.
 *
 * The shape of it is the one every visualiser of this kind uses: keep a low-resolution buffer,
 * and each frame redraw *the previous frame* slightly moved and slightly darker, then draw the
 * current instant of sound on top. The trail left behind is the picture; the waveform is only the
 * pen. What separates one look from another is nothing but how the previous frame is moved, which
 * is what a [Preset] is.
 *
 * All of this runs on its own thread with its own GL context — see [VisualizerView]. It reads the
 * audio through [SpectrumTap]'s volatile fields and copies, so it never blocks the audio thread
 * and never allocates once running.
 */
class VisualizerRenderer(private val tap: SpectrumTap) {

    private var warpProgram = 0
    private var waveProgram = 0
    private var presentProgram = 0

    private val quad = GlSupport.quad()

    /** Two buffers, used alternately: one is being read as "the previous frame", one written. */
    private val textures = IntArray(2)
    private val framebuffers = IntArray(2)
    private var front = 0

    private var bufferWidth = 0
    private var bufferHeight = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var aspect = 16f / 9f
    private var ready = false

    private val wave = FloatArray(WAVE_POINTS)

    /** Ribbon vertices: two per waveform point, each (x, y, t). Filled fresh every frame. */
    private val ribbon = GlSupport.buffer(WAVE_POINTS * 2 * 3)

    private var seconds = 0f
    private var presetIndex = 0
    private var lastBeats = 0
    private var flash = 0f

    /** Smoothed audio, so a single quiet analysis window cannot make the picture stutter. */
    private var bass = 0f
    private var treble = 0f

    fun onSurfaceCreated() {
        warpProgram = GlSupport.program(VERTEX_PASS, FRAGMENT_WARP)
        waveProgram = GlSupport.program(VERTEX_WAVE, FRAGMENT_WAVE)
        presentProgram = GlSupport.program(VERTEX_PASS, FRAGMENT_PRESENT)
        ready = warpProgram != 0 && waveProgram != 0 && presentProgram != 0

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        surfaceWidth = width
        surfaceHeight = height
        aspect = width.toFloat() / height

        releaseTargets()
        bufferHeight = BUFFER_HEIGHT
        bufferWidth = (BUFFER_HEIGHT * aspect).toInt().coerceAtLeast(16)
        for (i in 0..1) {
            val (texture, framebuffer) = GlSupport.renderTarget(bufferWidth, bufferHeight)
            textures[i] = texture
            framebuffers[i] = framebuffer
        }

        // Both start black, so the first frame has something defined to feed back from.
        for (i in 0..1) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[i])
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun onDrawFrame(stepSeconds: Float) {
        if (!ready || bufferWidth == 0) return
        val step = stepSeconds.coerceIn(0f, MAX_STEP)
        seconds += step

        readAudio(step)
        val preset = currentPreset()

        val back = 1 - front
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[back])
        GLES20.glViewport(0, 0, bufferWidth, bufferHeight)

        drawWarp(preset)
        drawWave(preset)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        drawPresent(textures[back])

        front = back
    }

    fun release() {
        releaseTargets()
        if (warpProgram != 0) GLES20.glDeleteProgram(warpProgram)
        if (waveProgram != 0) GLES20.glDeleteProgram(waveProgram)
        if (presentProgram != 0) GLES20.glDeleteProgram(presentProgram)
        warpProgram = 0
        waveProgram = 0
        presentProgram = 0
        ready = false
    }

    private fun releaseTargets() {
        if (textures[0] != 0) GLES20.glDeleteTextures(2, textures, 0)
        if (framebuffers[0] != 0) GLES20.glDeleteFramebuffers(2, framebuffers, 0)
        textures.fill(0)
        framebuffers.fill(0)
    }

    /**
     * Pulls the latest sound across, easing the levels.
     *
     * The levels are smoothed and the waveform is not: motion driven by a jittery number looks
     * broken, while a waveform that has been smoothed stops being the sound and starts being an
     * impression of it.
     */
    private fun readAudio(step: Float) {
        tap.copyWaveInto(wave)

        val ease = (step * 12f).coerceIn(0f, 1f)
        bass += (tap.bass - bass) * ease
        treble += (tap.treble - treble) * ease

        val beats = tap.beats
        if (beats != lastBeats) {
            lastBeats = beats
            flash = 1f
        }
        flash *= (1f - step * 6f).coerceIn(0f, 1f)
    }

    /** Where the cycle has got to: a preset, or a blend of two while it is changing over. */
    private fun currentPreset(): Preset {
        val bank = Presets.all
        val cycle = PRESET_SECONDS + BLEND_SECONDS
        val position = seconds % (cycle * bank.size)
        val index = (position / cycle).toInt().coerceIn(0, bank.size - 1)
        val within = position - index * cycle
        presetIndex = index

        val current = bank[index]
        if (within < PRESET_SECONDS) return current

        val next = bank[(index + 1) % bank.size]
        return Preset.between(current, next, (within - PRESET_SECONDS) / BLEND_SECONDS)
    }

    private fun drawWarp(preset: Preset) {
        GLES20.glUseProgram(warpProgram)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[front])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(warpProgram, "uPrev"), 0)

        // Bass pushes the zoom and treble the spin, which is what ties the motion to the music
        // rather than to the clock. Both are added to the preset's own steady drift.
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(warpProgram, "uZoom"),
            preset.zoom + preset.bassZoom * bass,
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(warpProgram, "uRotate"),
            preset.rotate + preset.trebleSpin * treble,
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(warpProgram, "uWarp"), preset.warp)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(warpProgram, "uWarpScale"), preset.warpScale)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(warpProgram, "uTime"), seconds)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(warpProgram, "uDecay"), preset.decay)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(warpProgram, "uAspect"), aspect)

        val position = GLES20.glGetAttribLocation(warpProgram, "aPos")
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
    }

    private fun drawWave(preset: Preset) {
        buildRibbon(preset)

        GLES20.glUseProgram(waveProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        // Additive: where the ribbon crosses itself it burns brighter, which is what gives the
        // bright core its shape. Alpha blending would just paint over and look flat.
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)

        GLES20.glUniform3fv(GLES20.glGetUniformLocation(waveProgram, "uFrom"), 1, preset.fromColor, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(waveProgram, "uTo"), 1, preset.toColor, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(waveProgram, "uGain"), 0.55f + 0.45f * flash)

        val position = GLES20.glGetAttribLocation(waveProgram, "aPos")
        val t = GLES20.glGetAttribLocation(waveProgram, "aT")

        ribbon.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 12, ribbon)
        GLES20.glEnableVertexAttribArray(position)
        ribbon.position(2)
        GLES20.glVertexAttribPointer(t, 1, GLES20.GL_FLOAT, false, 12, ribbon)
        GLES20.glEnableVertexAttribArray(t)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, WAVE_POINTS * 2)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(t)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Lays the waveform out as a triangle strip.
     *
     * A strip rather than `GL_LINE_STRIP` because line width above one pixel is optional in GL ES
     * and widely ignored — a line that is a hairline on the device it ships on is not a look. Two
     * vertices per point, offset either side of the path, gives a ribbon of any width, and the
     * path itself is whatever the preset's [WaveStyle] says.
     */
    private fun buildRibbon(preset: Preset) {
        ribbon.position(0)
        val half = preset.thickness
        val twoPi = 2f * Math.PI.toFloat()

        for (i in 0 until WAVE_POINTS) {
            val t = i.toFloat() / (WAVE_POINTS - 1)
            val value = wave[i] * preset.amplitude

            var x: Float
            var y: Float
            var nx: Float
            var ny: Float

            // Round shapes divide x by the aspect so a circle is a circle on a wide screen; the
            // straight ones must not, or the line would only reach across the middle half of it.
            // Either way the *normal* is squeezed, which is what keeps the ribbon an even width
            // all the way round rather than fattening at the sides.
            when (preset.waveStyle) {
                WaveStyle.CIRCLE -> {
                    val angle = t * twoPi
                    val radius = 0.42f + value
                    x = cos(angle) * radius / aspect
                    y = sin(angle) * radius
                    nx = cos(angle) / aspect
                    ny = sin(angle)
                }
                WaveStyle.SPIRAL -> {
                    val angle = t * 3f * twoPi
                    val radius = 0.08f + t * 0.52f + value * 0.4f
                    x = cos(angle) * radius / aspect
                    y = sin(angle) * radius
                    nx = cos(angle) / aspect
                    ny = sin(angle)
                }
                WaveStyle.MIRROR -> {
                    // The second half of the strip retraces the first, mirrored, so one draw call
                    // produces both halves of the ribbon.
                    val u = if (t < 0.5f) t * 2f else 2f - t * 2f
                    x = u * 2f - 1f
                    y = if (t < 0.5f) value else -value
                    nx = 0f
                    ny = 1f
                }
                else -> {
                    x = t * 2f - 1f
                    y = value
                    nx = 0f
                    ny = 1f
                }
            }

            ribbon.put(x + nx * half).put(y + ny * half).put(t)
            ribbon.put(x - nx * half).put(y - ny * half).put(t)
        }
        ribbon.position(0)
    }

    private fun drawPresent(texture: Int) {
        GLES20.glUseProgram(presentProgram)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(presentProgram, "uFrame"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(presentProgram, "uFlash"), flash * 0.25f)

        val position = GLES20.glGetAttribLocation(presentProgram, "aPos")
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
    }
}
