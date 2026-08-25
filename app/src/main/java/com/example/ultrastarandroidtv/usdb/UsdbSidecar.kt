package com.example.ultrastarandroidtv.usdb

import org.json.JSONException
import org.json.JSONObject

/**
 * What USDB Syncer left behind next to a song it downloaded.
 *
 * Every folder the desktop tool made carries one `.usdb` file — JSON, named after a YouTube id,
 * holding the song's USDB number and the meta tags from its chart. **All nineteen of the
 * audio-less folders on this card have one**, and it is the only place the missing music's video
 * id survives: those charts have no `#VIDEO:` header at all, because USDB Syncer moves the meta
 * tags out of the chart and into here.
 *
 * That makes this the difference between a broken song being repairable and being rubbish. It
 * costs one file read and no account, against the twenty-four-second throttle that fetching the
 * chart again would cost.
 *
 * Read leniently and never written. The format belongs to another program, which is free to change
 * it, add to it and use it for things this app knows nothing about — so an unfamiliar shape means
 * "no answer", not an error, and this app writes its own provenance into the chart instead of
 * producing a half-filled file that USDB Syncer would then believe.
 */
data class UsdbSidecar(
    /** USDB's own id for the song, or null when the file does not say. */
    val songId: Int?,
    /** The meta tags as they were in the chart — which is where the media ids live. */
    val metaTags: UsdbMetaTags,
)

/** Reads a `.usdb` file, or null when it is not one. */
fun readUsdbSidecar(text: String): UsdbSidecar? {
    val root = try {
        JSONObject(text)
    } catch (e: JSONException) {
        return null
    }
    val songId = root.optInt("song_id", -1).takeIf { it > 0 }
    val tags = root.optString("meta_tags").takeIf { it.isNotBlank() }
    if (songId == null && tags == null) return null
    return UsdbSidecar(
        songId = songId,
        metaTags = tags?.let { metaTagsFrom(it) } ?: UsdbMetaTags(),
    )
}

/**
 * The header this app writes to record where a song came from.
 *
 * **Not a `.usdb` file.** Writing one would mean filling in another program's format with
 * timestamps and per-file statuses this app does not track, and USDB Syncer would then act on
 * them. One header line says the one thing worth saying, in a file this app already owns and
 * already rewrites.
 */
const val USDB_ID_HEADER = "USDBID"
