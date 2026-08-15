package com.example.ultrastarandroidtv.settings

import com.example.ultrastarandroidtv.settings.GameSettings.Companion.sensitivityOf
import com.example.ultrastarandroidtv.settings.GameSettings.Companion.thresholdOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicSensitivityTest {

    private val range = SettingsRange.micThreshold

    @Test
    fun `more sensitive means a lower gate, not a higher one`() {
        // The whole point. The stored value is a gate — how loud a sound must be before it
        // counts — so raising it makes the microphone hear less. A slider labelled
        // "sensitivity" has to run the other way, or it contradicts the word on it.
        assertTrue(thresholdOf(0.9f) < thresholdOf(0.1f))
        assertTrue(sensitivityOf(0.02f) > sensitivityOf(0.18f))
    }

    @Test
    fun `full sensitivity hears everything the gate allows`() {
        assertEquals(range.start, thresholdOf(1f), 1e-6f)
        assertEquals(1f, sensitivityOf(range.start), 1e-6f)
    }

    @Test
    fun `no sensitivity needs the loudest voice`() {
        assertEquals(range.endInclusive, thresholdOf(0f), 1e-6f)
        assertEquals(0f, sensitivityOf(range.endInclusive), 1e-6f)
    }

    @Test
    fun `the two conversions are exact inverses`() {
        // They are applied in both directions every time the screen is drawn and every time the
        // dial moves, so any drift between them would walk the setting on its own.
        for (step in 0..20) {
            val sensitivity = step / 20f
            assertEquals(sensitivity, sensitivityOf(thresholdOf(sensitivity)), 1e-5f)
        }
    }

    @Test
    fun `values outside the dial are clamped rather than wrapped`() {
        assertEquals(range.start, thresholdOf(5f), 1e-6f)
        assertEquals(range.endInclusive, thresholdOf(-5f), 1e-6f)
        assertEquals(1f, sensitivityOf(-1f), 1e-6f)
        assertEquals(0f, sensitivityOf(99f), 1e-6f)
    }

    @Test
    fun `the shipped default sits high enough to be usable and low enough to be picky`() {
        // 0.06 against a 0.01 noise floor: well above "hears the whole room", well short of
        // "only hears shouting".
        val default = sensitivityOf(GameSettings.DEFAULT_MIC_THRESHOLD)

        assertTrue("was $default", default in 0.5f..0.9f)
    }
}
