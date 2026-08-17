package com.example.ultrastarandroidtv.visual

/** How the waveform is laid out. The one part of a preset that cannot be interpolated. */
object WaveStyle {
    /** A line across the middle, the classic oscilloscope. */
    const val LINE = 0

    /** A ring whose radius breathes with the sound. */
    const val CIRCLE = 1

    /** Two mirrored lines, which reads as a ribbon rather than a trace. */
    const val MIRROR = 2

    /** A spiral wound out from the centre — the busiest of the four. */
    const val SPIRAL = 3

    const val COUNT = 4
}

/**
 * One look: how the previous frame is moved, and what is drawn on top of it.
 *
 * Every field except [waveStyle] is a plain number, and that is deliberate — moving between two
 * presets is then just a matter of interpolating each one, which is enough to make the change
 * read as the picture *becoming* something else rather than being replaced. MilkDrop does the
 * same thing and it is most of why it never looks like a slideshow.
 *
 * These are the numbers that decide everything, so they are all in one place with the names their
 * effects have, rather than buried in the shader that consumes them.
 */
class Preset(
    val name: String,
    /** Scale applied to the previous frame each frame. Above 1 pulls inward, below pushes out. */
    val zoom: Float,
    /** Radians the previous frame turns each frame. Small numbers; this compounds every frame. */
    val rotate: Float,
    /** How far the sampling of the previous frame is bent by the sine field. */
    val warp: Float,
    /** How many bends across the screen. Low is a slow swell, high is a churn. */
    val warpScale: Float,
    /** Fraction of brightness the previous frame keeps. This sets how long trails live. */
    val decay: Float,
    val waveStyle: Int,
    /** Ribbon half-width, in units of the short screen edge. */
    val thickness: Float,
    /** How far the waveform swings. */
    val amplitude: Float,
    /** Colour at the start of the wave, and at the end; it runs between them along its length. */
    val fromColor: FloatArray,
    val toColor: FloatArray,
    /** Extra zoom per unit of bass — what makes the picture pump on the beat. */
    val bassZoom: Float,
    /** Extra rotation per unit of treble, which makes cymbals spin the frame. */
    val trebleSpin: Float,
) {
    companion object {
        /** Interpolates every numeric field. [waveStyle] snaps at the half-way point. */
        fun between(a: Preset, b: Preset, t: Float): Preset = Preset(
            name = if (t < 0.5f) a.name else b.name,
            zoom = mix(a.zoom, b.zoom, t),
            rotate = mix(a.rotate, b.rotate, t),
            warp = mix(a.warp, b.warp, t),
            warpScale = mix(a.warpScale, b.warpScale, t),
            decay = mix(a.decay, b.decay, t),
            waveStyle = if (t < 0.5f) a.waveStyle else b.waveStyle,
            thickness = mix(a.thickness, b.thickness, t),
            amplitude = mix(a.amplitude, b.amplitude, t),
            fromColor = mix(a.fromColor, b.fromColor, t),
            toColor = mix(a.toColor, b.toColor, t),
            bassZoom = mix(a.bassZoom, b.bassZoom, t),
            trebleSpin = mix(a.trebleSpin, b.trebleSpin, t),
        )

        private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

        private fun mix(a: FloatArray, b: FloatArray, t: Float) =
            FloatArray(3) { mix(a[it], b[it], t) }
    }
}

/**
 * The presets this cycles through.
 *
 * Chosen to be *different from each other* rather than individually clever: a tunnel, a bloom, a
 * churn, a spin. Rotating between eight variations on the same idea would look like one
 * visualiser having a long think, which is exactly the complaint about a row of bars going up and
 * down.
 *
 * Colours are deliberately not the game's cyan and pink. The track and the arrows own those, and
 * a background in the same two colours makes it harder to find the one thing that has to be read.
 */
object Presets {

    private fun rgb(r: Int, g: Int, b: Int) = floatArrayOf(r / 255f, g / 255f, b / 255f)

    val all = listOf(
        Preset(
            name = "Tunnel",
            zoom = 1.021f, rotate = 0.0022f, warp = 0.004f, warpScale = 5f, decay = 0.965f,
            waveStyle = WaveStyle.CIRCLE, thickness = 0.006f, amplitude = 0.22f,
            fromColor = rgb(90, 200, 255), toColor = rgb(255, 120, 60),
            bassZoom = 0.045f, trebleSpin = 0.004f,
        ),
        Preset(
            name = "Bloom",
            zoom = 0.984f, rotate = -0.0009f, warp = 0.010f, warpScale = 3f, decay = 0.955f,
            waveStyle = WaveStyle.LINE, thickness = 0.009f, amplitude = 0.42f,
            fromColor = rgb(255, 210, 90), toColor = rgb(255, 60, 140),
            bassZoom = -0.05f, trebleSpin = 0.002f,
        ),
        Preset(
            name = "Churn",
            zoom = 1.006f, rotate = 0.0f, warp = 0.030f, warpScale = 9f, decay = 0.972f,
            waveStyle = WaveStyle.MIRROR, thickness = 0.005f, amplitude = 0.30f,
            fromColor = rgb(120, 255, 170), toColor = rgb(60, 110, 255),
            bassZoom = 0.03f, trebleSpin = -0.006f,
        ),
        Preset(
            name = "Vortex",
            zoom = 1.014f, rotate = 0.010f, warp = 0.008f, warpScale = 4f, decay = 0.968f,
            waveStyle = WaveStyle.SPIRAL, thickness = 0.004f, amplitude = 0.34f,
            fromColor = rgb(255, 255, 255), toColor = rgb(150, 60, 255),
            bassZoom = 0.05f, trebleSpin = 0.010f,
        ),
        Preset(
            name = "Slipstream",
            zoom = 1.030f, rotate = -0.0035f, warp = 0.006f, warpScale = 7f, decay = 0.950f,
            waveStyle = WaveStyle.LINE, thickness = 0.012f, amplitude = 0.26f,
            fromColor = rgb(255, 90, 60), toColor = rgb(255, 220, 120),
            bassZoom = 0.07f, trebleSpin = -0.003f,
        ),
        Preset(
            name = "Lantern",
            zoom = 0.992f, rotate = 0.0015f, warp = 0.018f, warpScale = 2.2f, decay = 0.978f,
            waveStyle = WaveStyle.CIRCLE, thickness = 0.010f, amplitude = 0.16f,
            fromColor = rgb(255, 170, 40), toColor = rgb(80, 40, 200),
            bassZoom = -0.03f, trebleSpin = 0.001f,
        ),
        Preset(
            name = "Filament",
            zoom = 1.002f, rotate = 0.0f, warp = 0.045f, warpScale = 13f, decay = 0.982f,
            waveStyle = WaveStyle.SPIRAL, thickness = 0.003f, amplitude = 0.40f,
            fromColor = rgb(60, 255, 240), toColor = rgb(255, 255, 255),
            bassZoom = 0.02f, trebleSpin = 0.008f,
        ),
        Preset(
            name = "Undertow",
            zoom = 1.010f, rotate = 0.0045f, warp = 0.014f, warpScale = 6f, decay = 0.960f,
            waveStyle = WaveStyle.MIRROR, thickness = 0.007f, amplitude = 0.36f,
            fromColor = rgb(180, 60, 255), toColor = rgb(0, 200, 255),
            bassZoom = 0.055f, trebleSpin = -0.004f,
        ),
    )
}
