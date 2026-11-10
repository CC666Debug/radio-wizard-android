package io.github.cc666debug.radiowizard

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Headphone / Bluetooth buttons (play-pause, next, previous). The page itself plays in a WebView, which gives
 * Android no media session, so Android sent the buttons to the last music app (YouTube Music) instead.
 * This session sends them to RW's own player buttons: window.rwMediaKey('play'|'pause'|'next'|'prev'|'stop').
 * The page reports what's playing through AndroidCast.mediaState().
 */
class MediaKeys(private val context: Context, private val key: (String) -> Unit) {

    /** The speaker's volume while casting (so the volume keys with the screen off still move the speaker). */
    var speakerVolume: () -> Double? = { null }
    var setSpeakerVolume: (Double) -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private val session = MediaSession(context, "RadioWizard")
    private var playing = false
    private var onSpeaker = false
    private var volume: VolumeProvider? = null
    private var metaKey = ""
    private var artUrl: String? = null
    private var art: Bitmap? = null

    init {
        @Suppress("DEPRECATION")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setCallback(object : MediaSession.Callback() {
            // A single headset tap = play/pause, double tap = next (Android does this from the state below).
            override fun onPlay() = key("play")
            override fun onPause() = key("pause")
            override fun onStop() = key("stop")
            override fun onSkipToNext() = key("next")
            override fun onSkipToPrevious() = key("prev")
        })
        session.setSessionActivity(PendingIntent.getActivity(context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        setState()
        token = session.sessionToken
    }

    /** {active, playing, speaker, title, artist, album, art} from the page. */
    fun update(j: JSONObject) {
        val active = j.optBoolean("active")
        val nowPlaying = j.optBoolean("playing")
        val speaker = j.optBoolean("speaker")
        if (!active) { if (session.isActive) { playing = false; setState(); session.isActive = false }; return }
        if (speaker != onSpeaker || (speaker && volume == null)) { onSpeaker = speaker; setRoute() }
        val started = nowPlaying && !playing
        playing = nowPlaying
        setMeta(j)
        setState()
        if (!session.isActive) session.isActive = true
        // On a speaker nothing plays on the phone itself, and Android gives the buttons to the app that last
        // played sound here. A split second of silence makes that RW (once per start, no battery cost).
        if (started && speaker) silentBlip()
    }

    fun speakerVolumeChanged() {
        val v = volume ?: return
        val now = speakerVolume() ?: return
        v.currentVolume = Math.round(now * 20).toInt().coerceIn(0, 20)
    }

    fun release() {
        net.shutdownNow()
        session.isActive = false
        session.release()
    }

    private fun setRoute() {
        if (onSpeaker) {
            val now = speakerVolume() ?: 0.5
            val v = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 20, Math.round(now * 20).toInt().coerceIn(0, 20)) {
                override fun onSetVolumeTo(volume: Int) {
                    val n = volume.coerceIn(0, 20); currentVolume = n; setSpeakerVolume(n / 20.0)
                }
                override fun onAdjustVolume(direction: Int) {
                    if (direction == 0) return
                    val n = (currentVolume + direction).coerceIn(0, 20); currentVolume = n; setSpeakerVolume(n / 20.0)
                }
            }
            volume = v
            session.setPlaybackToRemote(v)
        } else {
            volume = null
            session.setPlaybackToLocal(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        }
    }

    private fun setState() {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(actions)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f)
            .build())
        notePlaying = playing
        CastKeepAliveService.refresh(context)
    }

    private fun setMeta(j: JSONObject) {
        val title = j.optString("title"); val artist = j.optString("artist"); val album = j.optString("album")
        val url = j.optString("art").takeIf { it.startsWith("http") }
        val k = "$title|$artist|$album|$url"
        if (k == metaKey) return
        metaKey = k
        if (url != artUrl) { artUrl = url; art = null; if (url != null) loadArt(url) }
        putMeta(title, artist, album)
    }

    private fun putMeta(title: String, artist: String, album: String) {
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
        art?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
        session.setMetadata(b.build())
        noteTitle = title; noteText = artist; noteArt = art
        CastKeepAliveService.refresh(context)
    }

    private fun loadArt(url: String) = net.execute {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) } ?: return@execute
            val s = 512.0 / maxOf(bmp.width, bmp.height)
            val small = if (s < 1) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
            main.post {
                if (artUrl != url) return@post
                art = small
                val p = metaKey.split("|")
                putMeta(p.getOrElse(0) { "" }, p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
            }
        } catch (e: Exception) {}
    }

    /** 0.3 s of silence through the media stream, so Android counts RW as the app that last played. */
    private fun silentBlip() = net.execute {
        try {
            val rate = 44100
            val frames = rate * 3 / 10
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(frames * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(ShortArray(frames), 0, frames)
            t.play()
            Thread.sleep(450)
            t.stop()
            t.release()
        } catch (e: Exception) {}
    }

    companion object {
        // What the casting notification shows (it uses this session, so its buttons and the lock screen's do the same as the headphones).
        @Volatile var token: MediaSession.Token? = null
        @Volatile var noteTitle = ""
        @Volatile var noteText = ""
        @Volatile var noteArt: Bitmap? = null
        @Volatile var notePlaying = false
    }
}
