package com.boykohub.looppractice

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps playback alive in the background and gives you the media notification,
 * lock-screen controls and headphone buttons. Next / previous jump between loops.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = (application as LoopApp).engine

        val loopPlayer = object : ForwardingPlayer(engine.player) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()

            override fun isCommandAvailable(command: Int): Boolean =
                availableCommands.contains(command)

            override fun seekToNext() = engine.nextLoop()
            override fun seekToNextMediaItem() = engine.nextLoop()
            override fun seekToPrevious() = engine.prevLoop()
            override fun seekToPreviousMediaItem() = engine.prevLoop()
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val s = MediaSession.Builder(this, loopPlayer)
            .setSessionActivity(openApp)
            .build()
        session = s
        addSession(s) // show the notification even before any controller connects
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from recents: keep going if music is playing, otherwise shut down.
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            release()
        }
        session = null
        super.onDestroy()
    }
}
