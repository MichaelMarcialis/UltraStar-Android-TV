package com.example.ultrastarandroidtv.game

import android.content.Context
import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import com.example.ultrastarandroidtv.audio.GainProcessor
import com.example.ultrastarandroidtv.audio.LoudnessCache
import com.example.ultrastarandroidtv.audio.SpectrumTap
import com.example.ultrastarandroidtv.audio.gainFor
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
 * Readings behind the arrow's median filter. One would mean no filter.
 *
 * **Three, because a median is the only thing here that can remove a spike rather than slow it
 * down.** Easing at a fiftieth of a second still travels most of the way to a lone wild reading
 * before turning round, which is exactly the twitch that was still being seen from the sofa;
 * a median discards that reading outright, and being a median rather than a mean it does not
 * drag a held note off its pitch to do it.
 *
 * **It costs no apparent lag, and that is arithmetic rather than optimism.** A median of three
 * lags the raw reading by one hop ([MEDIAN_LAG_SECONDS], 21 ms), and [arrowLagSeconds] already
 * carries that term — so the arrow is *drawn* 21 ms further left, which is where the audio it
 * is showing actually belongs. The note fill is gated on the same instant (`drawHits` measures
 * `passed` against `arrowNow`), so the fill cannot get ahead of the arrow that earned it.
 *
 * The history is worth keeping because this went the other way once: median-of-five plus a
 * twentieth of a second of easing put the arrow 150 ms behind the voice and notes lit up before
 * the arrow reached them. That was a *lag* problem, and the fix was the lag, not the median.
 */
private const val MEDIAN_WINDOW = 3

/**
 * Delay the median filter itself adds, in seconds.
 *
 * A median of N follows a step once (N+1)/2 of its samples are new, so it lags the raw reading
 * by (N-1)/2 of them. One reading is one of `PitchTracker`'s hops — 1024 samples at 48 kHz —
 * and this must be kept in step with that default, since it is used to decide where on the
 * track the arrow is drawn.
 */
private const val MEDIAN_LAG_SECONDS = ((MEDIAN_WINDOW - 1) / 2) * (1024.0 / 48_000.0)

/**
 * How close to the end of the audio counts as the end.
 *
 * A player's final reported position rarely lands exactly on the duration, and the difference
 * is not worth leaving anyone staring at a finished song waiting for a score.
 */
private const val END_TOLERANCE_SECONDS = 0.3

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
    /** Already open, and shared with the screen where the singers claimed them. */
    private val micSession: UsbMicSession,
    /**
     * Who is singing, in slot order: slot 0 is the first colour, slot 1 the second.
     *
     * Built by the claim screen, where each singer took a microphone by singing into it, so a
     * slot's colour and score belong to a person rather than to whichever USB device Android
     * happened to enumerate first.
     */
    private val lineup: List<SingerSlot>,
    /** How loud a voice must be to count. See `GameSettings.DEFAULT_MIC_THRESHOLD`. */
    micThreshold: Float = 0.06f,
) {
    /** One claimed microphone and the person holding it. */
    data class SingerSlot(val portId: String, val name: String)

    /** How many people are singing. */
    val playerCount: Int get() = lineup.size
    val beats = BeatTimeConverter(song.metadata)

    /**
     * Taps the song's audio for the visualiser.
     *
     * Always fitted, because it has to go in when the player is built and whether anyone will
     * need it is not known by then — a song with a video file that turns out not to decode
     * wants the visualiser after all. It does no work until something switches it on, so the
     * majority of the library, which does have a working video, pays nothing for it.
     */
    val spectrum: SpectrumTap = SpectrumTap()

    /**
     * Brings this recording to the same loudness as every other song in the library.
     *
     * Left at unity until [normalizeVolume] has measured the file, which is deliberate: a gain
     * guessed from the header would be wrong, and wrong in a way nobody could hear the cause of.
     */
    val gain: GainProcessor = GainProcessor()

    /**
     * Gain first, then the tap.
     *
     * The tap drives the visualiser, and the visualiser should move to what the room is actually
     * hearing — so it sees the song after normalisation, not before. It is also the correct order
     * for the rule the tap documents: everything downstream of the gain is still pass-through.
     */
    val player = SongPlayer(context, arrayOf<AudioProcessor>(gain, spectrum))

    private val appContext = context.applicationContext
    private val loudness = LoudnessCache(appContext)

    /**
     * A song with separate P1/P2 parts *and* two people to sing them.
     *
     * One player never gets a duet: splitting the screen would show them a part nobody is
     * singing and score them against half a song.
     */
    val isDuet: Boolean = song.voiceParts.size >= 2 && lineup.size >= 2

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
         * than tidy, and a lone wild reading drawn literally makes the detector look unsure when
         * it is not. The scorer is still fed the raw reading, so the filter cannot quietly change
         * what a performance is worth — the arrow and the score are allowed to disagree by one
         * reading, and never by more.
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

    /**
     * One singer per claimed microphone, in the order they claimed them.
     *
     * The slot index is the identity here — it decides the colour, the score readout and the
     * arrow — so it comes from the lineup rather than from USB enumeration order, which is
     * arbitrary and changes between sessions.
     */
    val singers: List<Singer> = lineup.mapIndexedNotNull { slot, entry ->
        val mic = micSession.mics.firstOrNull { it.portId == entry.portId } ?: return@mapIndexedNotNull null
        val partIndex = if (isDuet) slot.coerceAtMost(song.voiceParts.size - 1) else 0
        Singer(
            index = slot,
            name = entry.name,
            partIndex = partIndex,
            scorer = PlayerScorer(song.voiceParts[partIndex], beats, scoring),
            mic = mic,
            tracker = PitchTracker(minLevel = micThreshold),
        )
    }.onEach { byPort[lineup[it.index].portId] = it }

    /** One line on what was found, for when a mic is missing and nobody can tell why. */
    val micSummary: String get() = micSession.summary

    /**
     * The parts actually on screen, which is what a break is measured against.
     *
     * One singer never sees the second part of a duet, so the second part's notes must not keep
     * the game on screen through a stretch that, for the person holding the microphone, is an
     * instrumental.
     */
    private val playedParts =
        if (isDuet) song.voiceParts else song.voiceParts.take(1)

    /** The long instrumental stretches, where the game gets out of the video's way. */
    val vocalBreaks: VocalBreaks = VocalBreaks(
        playedParts.flatMap { part -> part.lines.flatMap { it.notes } }.map {
            VocalSpan(
                startSeconds = beats.beatToSeconds(it.startBeat),
                endSeconds = beats.beatToSeconds(it.startBeat + it.durationBeats),
            )
        },
    )

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

    /**
     * How far behind the frame being drawn the pitch an arrow shows actually sits.
     *
     * The arrow answers "where is this voice **now**", and the honest answer is that it cannot:
     * the value it has was measured from audio that is already old. Drawn at the sing line it
     * therefore points at a note that has moved on, which during a fast passage is simply the
     * wrong note — and the arrow sitting inside the bar is supposed to mean the same thing as the
     * beat being paid for. So the arrow is drawn where the audio it describes actually is, a
     * little to the left of the line.
     *
     * Every term is derived rather than dialled in, which is what stops it drifting out of truth
     * when something upstream changes:
     *
     *  - [SyncCalibration.captureLatencySeconds] — the reading describes the centre of its
     *    analysis window, not its end.
     *  - [SyncCalibration.displayLeadSeconds] — the notes are already drawn this far *ahead* to
     *    beat the TV's own processing, so the arrow is that much further behind them.
     *  - [MEDIAN_LAG_SECONDS] — the display filter's own delay.
     *
     * Every term here is *fixed*, which is what makes a constant offset the right shape for it.
     * `ArrowMotion`'s easing never belonged in it for exactly that reason — how long that takes
     * depends on how far the pitch just moved, and compensating a variable delay with a constant
     * would be wrong in both directions instead of one. That is moot now that the easing is off
     * by default, and it is the reason to keep it out if it ever comes back.
     */
    val arrowLagSeconds: Double
        get() = calibration.captureLatencySeconds +
            calibration.displayLeadSeconds +
            MEDIAN_LAG_SECONDS

    /**
     * Whether there is nothing left to sing.
     *
     * True through an outro, a fade, or the thirty seconds of guitar a lot of songs end on. The
     * song is *not* over at this point and the results deliberately do not appear — see
     * [isFinished] — but this is the moment a skip becomes worth offering, because from here
     * nothing anyone does can change the score.
     */
    val isVocalFinished: Boolean
        get() = playerPositionSeconds() >= songEndSeconds

    /**
     * Whether the song is over — **the recording, not the chart**.
     *
     * The last note running out used to count, and cutting to the results the instant it did was
     * abrupt: an outro is part of the song, the video is still playing, and being thrown to a
     * scoreboard mid-phrase of the guitar solo reads as a crash. So this now waits for the audio,
     * and [isVocalFinished] covers the stretch in between with a way out for anyone who does not
     * want to sit through it.
     *
     * The audio is the only thing that can be waited on here, and both tests of it are needed. A
     * player's final reported position rarely lands exactly on the duration, and once the audio
     * ends the clock stops advancing — so a position that has frozen just short of the finish
     * line has to count as the finish line. Measured, not guessed: the log read
     * `42.5/42.8 s ended=false`.
     *
     * The fallback matters too. A chart's last note plus its tail can fall *after* the end of the
     * file, and a player that never reports a duration would otherwise leave the results screen
     * unreachable — so a song whose length is unknown falls back to the chart running out.
     */
    val isFinished: Boolean
        get() {
            if (player.isEnded) return true

            val position = playerPositionSeconds()
            val duration = player.durationSeconds
            if (duration <= 0.0) return position >= songEndSeconds
            return position >= duration - END_TOLERANCE_SECONDS
        }

    /**
     * Measures how loud this recording is and sets [gain] to match the rest of the library.
     *
     * **Blocking, and it does real decoding — call it off the main thread**, and preferably
     * before the music starts. It is cheap after the first play of a song, because the answer is
     * kept ([LoudnessCache]), and it is safe to call late: the gain ramps rather than steps.
     */
    fun normalizeVolume() {
        val measured = loudness.measure(appContext, audioUri)
        val factor = gainFor(measured)
        gain.gain = factor
        Log.i(
            "Loudness",
            "%s: %.1f dBFS rms, peak %.2f -> gain %.2fx".format(
                song.metadata.title, measured.rmsDbfs, measured.peak, factor,
            ),
        )
    }

    fun start() {
        // The microphones are already open; this only points them at the scorers.
        micSession.onAudio = { mic, buffer, count -> byPort[mic.portId]?.let { feed(it, buffer, count) } }
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
        // The session outlives the game, so it is detached rather than stopped.
        micSession.onAudio = null
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
}
