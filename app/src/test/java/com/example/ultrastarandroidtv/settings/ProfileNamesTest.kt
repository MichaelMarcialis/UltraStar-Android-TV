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

    // ---- The rules Profiles applies when the list is edited ------------------------------------
    //
    // Profiles itself needs a Context, so what is pinned here is the reasoning it uses: whether a
    // proposed edit collides with an existing name. The rules are stated once and read the same
    // way from the profiles screen and from the claim screen.

    @Test
    fun `a rename that only changes capitalisation is not a collision with itself`() {
        // "robin" -> "Robin" has to be allowed, which is why the check has to exclude the name
        // being renamed rather than simply asking whether the new one already exists.
        val others = everyone.filterNot { it.equals("Robin", ignoreCase = true) }.toSet()
        assertFalse(isNameTaken("ROBIN", others))
    }

    @Test
    fun `a rename onto somebody else is a collision`() {
        val others = everyone.filterNot { it.equals("Robin", ignoreCase = true) }.toSet()
        assertTrue(isNameTaken("Casey", others))
        assertTrue(isNameTaken("casey", others))
    }

    @Test
    fun `adding a name already on the list is a collision whatever the case`() {
        assertTrue(isNameTaken("SAM", everyone.toSet()))
        assertTrue(isNameTaken(" sam ", everyone.toSet()))
        assertFalse(isNameTaken("Sammy", everyone.toSet()))
    }
}
