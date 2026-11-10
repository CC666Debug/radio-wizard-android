package io.github.cc666debug.radiowizard

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/** Casts with Google's standard speaker player, the same one the web app uses. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setResumeSavedSession(true)
            // Closing the app leaves the speaker playing (like YouTube Music); the app's ✕ and "Stop casting" still stop it.
            .setStopReceiverApplicationWhenEndingSession(false)
            // No Google media session or notification: RW's own (MediaKeys + CastKeepAliveService) does that, so the
            // headphone buttons, lock screen and notification all go through RW's player (next station, next song...).
            // (Before 1.12 the page in its WebView had no session, so Android sent the headphone buttons to YouTube Music.)
            .setCastMediaOptions(CastMediaOptions.Builder().setMediaSessionEnabled(false).setNotificationOptions(null).build())
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
