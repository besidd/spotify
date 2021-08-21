package com.app.spotify.exoplayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.MediaBrowserServiceCompat
import com.app.spotify.exoplayer.callback.MusicPlayerEventListener
import com.app.spotify.exoplayer.callback.MusicPlayerNotificationListener
import com.app.spotify.exoplayer.callback.MusicPreparerListener
import com.app.spotify.utils.Constants.MEDIA_ROOT_ID
import com.app.spotify.utils.Constants.NETWORK_ERROR
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MusicService"

@AndroidEntryPoint
class MusicService : MediaBrowserServiceCompat() {

    @Inject
    lateinit var datsSourceFactory: DefaultDataSourceFactory

    @Inject
    lateinit var exoPlayer: SimpleExoPlayer

    @Inject
    lateinit var firebaseMusicSource: FirebaseMusicSource

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaSessionConnector: MediaSessionConnector
    private lateinit var musicNotificationManger: MusicNotificationManager
    private var currentPlayingSong: MediaMetadataCompat? = null
    private lateinit var musicPrepareEventListener: MusicPlayerEventListener

    private var isPlayerInitialized = false
    var isForegroundService = false

    companion object {
        var currentSongDuration = 0L
            private set
    }

    override fun onCreate() {
        super.onCreate()

        serviceScope.launch {
            firebaseMusicSource.fetchMediaData()
        }

        val activityIntent = packageManager?.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, 0)
        }

        mediaSession = MediaSessionCompat(this, TAG).apply {
            setSessionActivity(activityIntent)
            isActive = true
        }

        musicNotificationManger = MusicNotificationManager(
            this,
            mediaSession.sessionToken,
            MusicPlayerNotificationListener(this)
        ) {
            currentSongDuration = exoPlayer.duration
        }

        val musicPlayBackPreparer = MusicPreparerListener(firebaseMusicSource) {
            currentPlayingSong = it
            preparePlayer(firebaseMusicSource.songs, it, true)
        }

        // binding session token of the service class with token of media session to get information of the
        // current session whenever required

        sessionToken = mediaSession.sessionToken

        mediaSessionConnector.setPlaybackPreparer(musicPlayBackPreparer)
        mediaSessionConnector = MediaSessionConnector(mediaSession)
        mediaSessionConnector.setQueueNavigator(MusicQueueNavigator())
        mediaSessionConnector.setPlayer(exoPlayer)
        musicPrepareEventListener = MusicPlayerEventListener(this)
        exoPlayer.addListener(musicPrepareEventListener)
        musicNotificationManger.sowNotification(exoPlayer)

    }

    private inner class MusicQueueNavigator : TimelineQueueNavigator(mediaSession) {
        override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat {
            return firebaseMusicSource.songs[windowIndex].description
        }
    }

    private fun preparePlayer(
        songs: List<MediaMetadataCompat>,
        itemToPlay: MediaMetadataCompat,
        playNow: Boolean
    ) {
        val currentSongIndex = if (currentPlayingSong == null) 0 else songs.indexOf(itemToPlay)
        exoPlayer.apply {
            prepare(firebaseMusicSource.asMediaSource(datsSourceFactory))
            seekTo(currentSongIndex, 0L)
            playWhenReady = playNow
        }

    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val resultsSent = firebaseMusicSource.whenReady { initialized ->
            if (initialized) {
                result.sendResult(firebaseMusicSource.asMediaItem())
                if (!isPlayerInitialized && firebaseMusicSource.songs.isNotEmpty()) {
                    preparePlayer(firebaseMusicSource.songs, firebaseMusicSource.songs[0], false)
                    isPlayerInitialized = true
                }
            } else {
                mediaSession.sendSessionEvent(NETWORK_ERROR, null)
                result.sendResult(null)
            }
        }
        if (!resultsSent) { // this means that the results are not ready yet
            result.detach()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        exoPlayer.stop()
    }

    override fun onDestroy() {
        super.onDestroy()

        // ensuring the service job is terminated whenever the services is stopped
        serviceJob.cancel()
        exoPlayer.removeListener(musicPrepareEventListener)
        exoPlayer.release()
    }
}