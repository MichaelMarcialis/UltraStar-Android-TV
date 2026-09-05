package com.example.ultrastarandroidtv.download

import android.content.Context

/**
 * Repairs that have already been tried and came back with nothing.
 *
 * ## Why this has to be remembered at all
 *
 * A repair is offered on the strength of what the *card* says is missing, which is only half the
 * question. Whether the missing part can actually be *got* is the other half, and nothing in the
 * folder can answer it: a song with no artwork is offered a repair, iTunes turns out to have no
 * artwork for it either, and the song is counted again on the next look at the card — for ever.
 *
 * Reported from the sofa as exactly that: "Repair 7 songs", which then ran through seven songs
 * saying nothing could be found and offered the same seven again straight afterwards. An action
 * that cannot succeed must not keep asking to be pressed.
 *
 * ## What is remembered, and what un-remembers it
 *
 * The song, and a description of what was wanted — see [RepairPlan.signature]. Anything the card
 * gains or loses later changes that description and the song is offered again, because a plan for
 * a song missing its music is a different plan from one missing only its picture. A repair that
 * fetches anything at all forgets the song outright.
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
