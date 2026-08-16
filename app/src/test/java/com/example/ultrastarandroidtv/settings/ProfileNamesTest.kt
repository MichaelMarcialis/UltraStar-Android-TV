package com.example.ultrastarandroidtv.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileNamesTest {

    private val everyone = listOf("Mia", "Robin", "Casey", "Sam")

    @Test
    fun `nobody singing yet means every name is offered`() {
        assertEquals(everyone, namesAvailable(everyone, emptySet()))
    }

    @Test
    fun `the other singer's name is not offered`() {
        assertEquals(listOf("Mia", "Casey", "Sam"), namesAvailable(everyone, setOf("Robin")))
    }

    @Test
    fun `order is preserved, since most-recently-used first is the whole point`() {
        assertEquals(listOf("Mia", "Robin", "Sam"), namesAvailable(everyone, setOf("Casey")))
    }

    @Test
    fun `case is ignored, the same way Profiles folds a name onto an existing one`() {
        // Profiles.use("robin") selects the existing "Robin" rather than making a second
        // profile, so anything that decides whether "robin" is taken has to agree with it —
        // otherwise typing a name in the wrong case walks straight past this rule.
        assertEquals(listOf("Mia", "Casey", "Sam"), namesAvailable(everyone, setOf("robin")))
        assertTrue(isNameTaken("ROBIN", setOf("Robin")))
        assertTrue(isNameTaken("Robin", setOf("robin")))
    }

    @Test
    fun `surrounding space does not smuggle a duplicate through`() {
        assertTrue(isNameTaken("  Robin ", setOf("Robin")))
    }

    @Test
    fun `a different name is not taken`() {
        assertFalse(isNameTaken("Robyn", setOf("Robin")))
        assertFalse(isNameTaken("Rob", setOf("Robin")))
    }

    @Test
    fun `an empty list of names stays empty rather than failing`() {
        assertEquals(emptyList<String>(), namesAvailable(emptyList(), setOf("Robin")))
    }

    @Test
    fun `nothing is taken when nobody has been named yet`() {
        assertFalse(isNameTaken("Robin", emptySet()))
    }
}
