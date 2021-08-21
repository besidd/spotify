package com.app.spotify.exoplayer

import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.MediaMetadataCompat.*
import androidx.core.net.toUri
import com.app.spotify.data.entities.Song
import com.app.spotify.data.remote.MusicDatabase
import com.app.spotify.exoplayer.State.*
import com.google.android.exoplayer2.source.ConcatenatingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class FirebaseMusicSource @Inject constructor(private val musicDatabase: MusicDatabase) {

    var songs = emptyList<MediaMetadataCompat>()

    private val onReadyListeners = mutableListOf<(Boolean) -> Unit>()

    suspend fun fetchMediaData() = withContext(Dispatchers.IO) {
        state = STATE_INITIALIZING
        val allSongs = musicDatabase.getSongsFromFireStore()
       songs = allSongs.map { song ->
           MediaMetadataCompat.Builder()
               .putString(METADATA_KEY_ARTIST, song.subTitle)
               .putString(METADATA_KEY_MEDIA_ID, song.mediaId)
               .putString(METADATA_KEY_TITLE, song.title)
               .putString(METADATA_KEY_DISPLAY_TITLE, song.title)
               .putString(METADATA_KEY_DISPLAY_SUBTITLE, song.subTitle)
               .putString(METADATA_KEY_DISPLAY_ICON_URI, song.imageUrl)
               .putString(METADATA_KEY_MEDIA_URI, song.songUrl)
               .putString(METADATA_KEY_ALBUM_ART_URI, song.imageUrl)
               .putString(METADATA_KEY_DISPLAY_DESCRIPTION, song.subTitle)
               .build()
        }
        state = STATE_INITIALIZED
    }

    fun asMediaSource(dataSourceFactory: DefaultDataSourceFactory): ConcatenatingMediaSource {
        val concatenatingMediaSource = ConcatenatingMediaSource()
        songs.forEach {
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(it.getString(METADATA_KEY_MEDIA_URI).toUri())

            concatenatingMediaSource.addMediaSource(mediaSource)
        }
        return concatenatingMediaSource
    }

    fun asMediaItem() = songs.map {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaUri(it.getString(METADATA_KEY_MEDIA_URI).toUri())
            .setTitle(it.description.title)
            .setSubtitle(it.description.subtitle)
            .setMediaId(it.description.mediaId)
            .setIconUri(it.description.iconUri)
            .build()
        MediaBrowserCompat.MediaItem(desc, FLAG_PLAYABLE)
    }.toMutableList()

    private var state: State = STATE_CREATED
        set(value) {
            if (value == STATE_INITIALIZED || value == STATE_ERROR) {
                synchronized(onReadyListeners) {
                    field = value // field refers to the current value of state
                    onReadyListeners.forEach { listeners ->
                        listeners(state == STATE_INITIALIZED)
                    }
                }
            } else {
                field = value
            }
        }

    fun whenReady(action: (Boolean) -> Unit): Boolean {
        return if (state == STATE_CREATED || state == STATE_INITIALIZING) {
            onReadyListeners += action
            false
        } else {
            action(state == STATE_INITIALIZED) // calling the function to tell the current resource is ready
            true
        }
    }

}

private enum class State {
    STATE_CREATED,
    STATE_INITIALIZED,
    STATE_INITIALIZING,
    STATE_ERROR
}