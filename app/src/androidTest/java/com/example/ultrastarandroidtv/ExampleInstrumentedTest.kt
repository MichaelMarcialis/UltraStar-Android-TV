package com.example.ultrastarandroidtv

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    /**
     * The installed package name, pinned deliberately.
     *
     * This arrived as template boilerplate asserting `com.example.ultrastarandroidtv`, and it
     * quietly went stale the moment the id changed. It is kept rather than deleted because the
     * thing it happens to check turns out to be worth checking: **`applicationId` is the one
     * value in this project that must never change casually.** Android identifies an app by its
     * id, so a new one is a new app -- no update can land on an existing copy, and every user has
     * to uninstall first, losing their song folder grant, their profiles and their high scores.
     *
     * Written out in full rather than compared against `BuildConfig.APPLICATION_ID`, which would
     * be the same value on both sides of the assertion and could therefore never fail. The point
     * is to have the literal string somewhere that objects when it moves.
     */
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.github.michaelmarcialis.ultrastarandroidtv", appContext.packageName)
    }
}