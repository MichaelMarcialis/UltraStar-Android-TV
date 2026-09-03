package com.example.ultrastarandroidtv.download

import android.content.Context

/**
 * Repairs that have already been tried and came back with nothing.
 *
 * ## Why this has to be remembered at all
 *
 * A repair is offered on the strength of what the *card* says is missing, and for music and video
 * that is the whole story — the folder either has the file or it does not. Artwork is different:
 * a cover under [MIN_COVER_PIXELS] is a thumbnail standing in for artwork, and whether anything
 * better exists cannot be known without asking iTunes. So the survey counts those songs as worth
 * repairing, the repair asks, iTunes has nothing bigger, and the song is counted again next time.
 *
 * Reported from the sofa exactly as it behaves: "Repair 7 songs" with every filter saying nothing
 * was missing, which then ran through seven songs saying nothing could be found — and offered the
 * same seven again straight afterwards. An action that cannot succeed must not keep asking to be
 * pressed.
 *
 * ## What is remembered, and what un-remembers it
 *
 * The song, and a description of what was wanted — see [RepairPlan.signature]. That description
 * carries the cover's own size, so replacing the artwork by any other route changes the signature
 * and the song is offered again. Anything the card gains later does the same, because a plan for
 * a song missing its music is a different plan from one wanting a better picture.
 *
 * ## Where it is applied
 *
 * The **batch** only. "Repair 7 songs" means seven songs there is something new to try for; a
 * song's own page still offers whatever its plan says, because asking again deliberately for one
 * song is a reasonable thing to want and costs a few seconds. Suppressing it there would take
 * away the only way to overrule this.
 */
class RepairMemory(context: Context) {

    private val prefs = context.getSharedPreferences("repair_memory", Context.MODE_PRIVATE)

    /** Whether this exact repair has been tried already and found nothing. */
    fun triedInVain(textId: String, plan: RepairPlan): Boolean =
        prefs.getString(textId, null) == plan.signature

    /** Records that there was nothing to be had for this song, as things stand. */
    fun rememberNothing(textId: String, plan: RepairPlan) {
        prefs.edit().putString(textId, plan.signature).apply()
    }

    /** Forgets a song, so it is offered again. Called whenever a repair actually fetches something. */
    fun forget(textId: String) {
        prefs.edit().remove(textId).apply()
    }
}
