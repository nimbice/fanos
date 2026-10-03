package io.github.nimbice.fanos.feature.reader.listen

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import io.github.nimbice.fanos.core.data.R
import io.github.nimbice.fanos.core.data.notification.NewChapterNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Listening's notification, lock-screen controls and headset buttons: a media session over [Narrator]. Previous and
 * next step a sentence back or on; a headset's single press plays or pauses, a double press goes to the next chapter
 * and a triple press to the previous one. Tapping the notification opens the chapter being read.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class ListeningService : MediaSessionService() {

    @Inject lateinit var narrator: Narrator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = NarratorPlayer(narrator, mainLooper)
        val built = MediaSession.Builder(this, player).setCallback(Buttons()).build()
        session = built
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification_lamp) })
        // The notification shows the chapter and whether it plays: not each sentence and word.
        scope.launch {
            narrator.state.map { it?.let { state -> listOf(state.chapterTitle, state.novelTitle, state.playing, state.loading, state.problem, state.hasArtwork) } }.distinctUntilChanged().collect { player.refresh() }
        }
        // The notification opens the chapter being read, whichever that is now.
        scope.launch {
            var listened = false
            narrator.state.map { it?.let { state -> state.novelId to state.chapterId } }.distinctUntilChanged().collect { place ->
                if (place != null) {
                    listened = true
                    built.setSessionActivity(openChapter(place.first, place.second))
                } else if (listened) {
                    // Listening over: nothing left to show. (Not before it starts: the service comes up first.)
                    stopSelf()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app swiped away while listening is paused: nothing to keep going for.
        if (narrator.state.value?.playing != true) {
            narrator.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun openChapter(novelId: Long, chapterId: Long): PendingIntent {
        val intent =
            requireNotNull(packageManager.getLaunchIntentForPackage(packageName))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(Uri.parse("fanos://listen/$novelId"))
                .putExtra(NewChapterNotifier.EXTRA_NOVEL_ID, novelId)
                .putExtra(NewChapterNotifier.EXTRA_CHAPTER_ID, chapterId)
        return PendingIntent.getActivity(this, REQUEST_OPEN, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** A headset's one button: once plays or pauses, twice goes to the next chapter, three times to the previous. */
    private inner class Buttons : MediaSession.Callback {
        private var presses = 0
        private var waiting: Job? = null

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            val event =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
            if (event == null || (event.keyCode != KeyEvent.KEYCODE_HEADSETHOOK && event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) return false
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
            presses++
            waiting?.cancel()
            waiting =
                scope.launch {
                    delay(MULTI_PRESS_MS)
                    when (presses) {
                        1 -> narrator.toggle()
                        2 -> narrator.chapterOn()
                        else -> narrator.chapterBack()
                    }
                    presses = 0
                }
            return true
        }
    }

    private companion object {
        /** How long a headset's presses wait for another before they count. */
        const val MULTI_PRESS_MS = 450L
        const val REQUEST_OPEN = -3
    }
}

/**
 * [Narrator] as a player, for the session: playing or not, the chapter as what's playing, and previous and next as a
 * sentence back or on.
 */
@OptIn(UnstableApi::class)
private class NarratorPlayer(private val narrator: Narrator, looper: Looper) : SimpleBasePlayer(looper) {

    fun refresh() = invalidateState()

    override fun getState(): State {
        val commands =
            Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_METADATA,
                ).build()
        val listening = narrator.state.value ?: return State.Builder().setAvailableCommands(commands).setPlaybackState(Player.STATE_IDLE).build()
        val metadata =
            MediaMetadata.Builder()
                .setTitle(listening.chapterTitle)
                .setArtist(listening.novelTitle)
                .setSubtitle(listening.problem)
                .apply { narrator.artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                .build()
        val item = MediaItemData.Builder(listening.chapterId).setMediaItem(MediaItem.Builder().setMediaId(listening.chapterId.toString()).build()).setMediaMetadata(metadata).build()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(item))
            .setPlayWhenReady(listening.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (listening.loading) Player.STATE_BUFFERING else Player.STATE_READY)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) narrator.play() else narrator.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> narrator.sentenceOn()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> narrator.sentenceBack()
        }
        return Futures.immediateVoidFuture()
    }

    // The notification swiped away: listening is over.
    override fun handleStop(): ListenableFuture<*> {
        narrator.stop()
        return Futures.immediateVoidFuture()
    }
}
