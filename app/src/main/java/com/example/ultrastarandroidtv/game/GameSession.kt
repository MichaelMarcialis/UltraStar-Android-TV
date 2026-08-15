package com.example.ultrastarandroidtv.game

import android.content.Context
import com.example.ultrastarandroidtv.mic.OpenMic
import com.example.ultrastarandroidtv.mic.UsbMicSession
import com.example.ultrastarandroidtv.pitch.PitchTracker
import com.example.ultrastarandroidtv.playback.SongPlayer
import com.example.ultrastarandroidtv.playback.SyncCalibration
import com.example.ultrastarandroidtv.score.PlayerScorer
import com.example.ultrastarandroidtv.score.ScoreSnapshot
import com.example.ultrastarandroidtv.score.ScoringConfig
import com.example.ultrastarandroidtv.song.BeatTimeConverter
import com.example.ultrastarandroidtv.song.UltraStarSong
import java.nio.ByteBuffer

/** Quiet tail after the last note before the song is called finished. */
private const val TAIL_SECONDS = 2.0

/**
 * Readings behind the arrow's median filter. Five at ~47 readings a second is a touch over a
 * tenth of a second — long enough to swallow a lone bad reading, short enough that a deliberate
 * slide between notes still looks like one.
 */
private const val MEDIAN_WINDOW = 5

/**
 * One playthrough: the song, the player, the mics, and a scorer per singer.
 *
 * This is where the pieces built separately finally meet, and its whole job is to keep two
 * clocks straight. A pitch reading describes audio that has been out through HDMI, across the
 * room, back in over USB and through an analysis window, so it is scored against
 * [SyncCalibration.songTimeFor]. What is *drawn* has only made the first half of that trip, so
 * it uses [drawTimeSeconds] — the singer's ears are behind the player, but not as far behind as
 * their voice is. Rendering at the raw player position would push singers early and then mark
 * them down for it.
 *
 * Threading: each singer's scorer is written only by that singer's own capture thread, so there
 * is exactly one writer per scorer and no lock is needed on the audio path. The UI thread only
 * reads, once a frame.
 */
class GameSession(
    context: Context,
    val song: UltraStarSong,
    private val audioUri: String,
    val calibration: SyncCalibration = SyncCalibration(),
    /** Also decides how tall a note is drawn — see [ScoringConfig.toleranceSemitones]. */
    val scoring: ScoringConfig = ScoringConfig(),
    /** How many people are singing. Extra microphones are ignored rather than given a score. */
    val playerCount: Int = 2,
    /** How loud a voice must be to count. See `GameSettings.DEFAULT_MIC_THRESHOLD`. */
    micThreshold: Float = 0.06f,
) {
    val beats = BeatTimeConverter(song.metadata)
    val player = SongPlayer(context)

    /**
     * A song with separate P1/P2 parts *and* two people to sing them.
     *
     * One player never gets a duet: splitting the screen would show them a part nobody is
     * singing and score them against half a song.
     */
    val isDuet: Boolean = song.voiceParts.size >= 2 && playerCount >= 2

    /** One singer, which in practice means one microphone. */
    class Singer internal constructor(
        val index: Int,
        val name: String,
        /** The voice part scored against — always 0 unless the song is a duet. */
        val partIndex: Int,
        val scorer: PlayerScorer,
        private val mic: OpenMic,
        /** One tracker per mic, only ever touched from that mic's capture thread. */
        internal val tracker: PitchTracker,
    ) {

        /**
         * What this singer is producing *right now*, as fractional MIDI, or NaN for silence.
         *
         * Separate from the per-beat record the scorer keeps, and deliberately so: the scorer
         * answers "what did this beat earn", which is only settled once the beat has passed,
         * while the pitch arrow has to answer "where is this voice this instant" — including
         * between notes and during rests, where there is no beat to attach it to.
         *
         * **Median-filtered, and only for display.** Raw YIN on a real voice is honest rather
         * than tidy — a held note still wanders, and the detector occasionally throws a single
         * wild reading. Drawn literally that becomes a twitching arrow that looks like the
         * detector is unsure when it is not. A median of the last few readings removes exactly
         * the lone outliers without blunting a real slide, at the cost of about a frame of lag.
         *
         * Scoring never sees this: [PlayerScorer] is fed the raw reading, so the filter cannot
         * quietly change what a performance is worth.
         *
         * Written on the capture thread, read by the draw pass. A single 32-bit field, so a
         * frame can be one reading behind but never sees a value that was never produced.
         */
        @Volatile
        var currentMidi: Float = Float.NaN
            private set

        private val recent = FloatArray(MEDIAN_WINDOW) { Float.NaN }
        private var recentIndex = 0
        private val sorting = FloatArray(MEDIAN_WINDOW)

        /** Called on the capture thread, once per reading. */
        internal fun observe(midi: Float) {
            recent[recentIndex] = midi
            recentIndex = (recentIndex + 1) % recent.size

            var count = 0
            for (value in recent) if (!value.isNaN()) sorting[count++] = value

            // A voice has to be mostly present across the window to count as singing, so one
            // stray voiced frame in a rest cannot flash the arrow into existence.
            currentMidi = if (count * 2 <= recent.size) {
                Float.NaN
            } else {
                sorting.sort(0, count)
                sorting[count / 2]
            }
        }

        internal fun forgetPitch() {
            recent.fill(Float.NaN)
            currentMidi = Float.NaN
        }

        val micStatus: String get() = mic.status
        val micLabel: String get() = mic.label

        /**
         * The running score. Read from the UI thread while the capture thread scores, so an
         * individual field can be a beat stale — which on a number that climbs all song is not
         * something anyone can see, and is worth more than locking the audio path to prevent.
         */
        fun snapshot(): ScoreSnapshot = scorer.snapshot()
    }

    private val byPort = mutableMapOf<String, Singer>()

    private val micSession = UsbMicSession(
        context = context,
        onAudio = { mic, buffer, count -> byPort[mic.portId]?.let { feed(it, buffer, count) } },
    )

    val singers: List<Singer> = micSession.mics.take(playerCount).map { mic ->
        val partIndex = if (isDuet) mic.index.coerceAtMost(song.voiceParts.size - 1) else 0
        Singer(
            index = mic.index,
            name = nameFor(mic.index, partIndex),
            partIndex = partIndex,
            scorer = PlayerScorer(song.voiceParts[partIndex], beats, scoring),
            mic = mic,
            tracker = PitchTracker(minLevel = micThreshold),
        )
    }.onEach { byPort[micSession.mics[it.index].portId] = it }

    /** One line on what was found, for when a mic is missing and nobody can tell why. */
    val micSummary: String get() = micSession.summary

    /** When the last note of any part has finished, plus a little quiet. */
    val songEndSeconds: Double = song.voiceParts
        .flatMap { part -> part.lines.flatMap { it.notes } }
        .maxOfOrNull { beats.beatToSeconds(it.startBeat + it.durationBeats) }
        ?.plus(TAIL_SECONDS)
        ?: TAIL_SECONDS

    /**
     * Backing-track volume, 0..1.
     *
     * Turning it down does not make the singers quieter: the mics never pass through the
     * player. It also cannot remove the original vocal, which is mixed into the same file.
     */
    var volume: Float
        get() = player.volume
        set(value) {
            player.volume = value
        }

    /** Called on the main thread if playback fails. */
    var onError: ((String) -> Unit)?
        get() = player.onError
        set(value) {
            player.onError = value
        }

    /** Where the player has got to, unadjusted. */
    fun playerPositionSeconds(): Double = player.clock.positionAt(System.nanoTime())

    /**
     * Song time to draw at: what the singer is hearing right now.
     *
     * Only the output leg is taken off, because the sound has reached the room but has not been
     * back through capture and analysis.
     */
    fun drawTimeSeconds(): Double = calibration.heardSongTimeFor(playerPositionSeconds())

    val isFinished: Boolean get() = playerPositionSeconds() >= songEndSeconds

    fun start() {
        micSession.start()
        player.load(audioUri)
    }

    fun play() = player.play()

    fun pause() = player.pause()

    /** Back to the top. The scorers walk forwards only, so each is rebuilt rather than rewound. */
    fun restart() {
        singers.forEach {
            it.scorer.reset()
            it.forgetPitch()
        }
        player.seekTo(0.0)
        player.play()
    }

    fun release() {
        micSession.stop()
        player.release()
    }

    private fun feed(singer: Singer, buffer: ByteBuffer, count: Int) {
        singer.tracker.process(buffer, count) { reading ->
            singer.observe(if (reading.voiced) reading.midi else Float.NaN)
            // Read the clock per reading rather than per buffer: a buffer can carry more than
            // one analysis window, and they did not happen at the same moment. The scorer gets
            // the raw reading, never the smoothed one.
            singer.scorer.update(calibration.songTimeFor(playerPositionSeconds()), reading)
        }
    }

    private fun nameFor(index: Int, partIndex: Int): String {
        if (isDuet) {
            val named =
                if (partIndex == 0) song.metadata.duetSingerP1 else song.metadata.duetSingerP2
            if (!named.isNullOrBlank()) return named
        }
        return "Player ${index + 1}"
    }
}
