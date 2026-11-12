package org.sonora.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.launch
import uniffi.sonora_mobile.PlaybackState
import uniffi.sonora_mobile.Status

/**
 * Mantiene viva la música con la app cerrada: notificación con controles, pantalla de bloqueo,
 * botones de audífonos y Bluetooth, foco de audio (pausa con llamadas) y pausa al desconectar
 * los audífonos.
 */
class PlaybackService : Service() {
    private lateinit var media: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focus: AudioFocusRequest? = null
    private var foreground = false
    private var resumeAfterFocus = false
    private var artworkUrl: String? = null
    private var artwork: Bitmap? = null

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) Store.engine.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        media = MediaSessionCompat(this, "Sonora").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = Store.engine.play()
                override fun onPause() = Store.engine.pause()
                override fun onStop() = Store.engine.pause()
                override fun onSkipToNext() = Store.engine.next()
                override fun onSkipToPrevious() = Store.engine.previous()
                override fun onSeekTo(pos: Long) = Store.engine.seek(pos.coerceAtLeast(0).toULong())
            })
            isActive = true
        }
        ContextCompat.registerReceiver(
            this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        requestFocus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            Store.shutdown()
            return START_NOT_STICKY
        }
        MediaButtonReceiver.handleIntent(media, intent)
        Store.state?.let { show(it, starting = true) } ?: showPlaceholder()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Al quitar Sonora de las apps recientes, se cierra del todo. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Store.shutdown()
    }

    override fun onDestroy() {
        instance = null
        unregisterReceiver(noisy)
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        media.isActive = false
        media.release()
        super.onDestroy()
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        resumeAfterFocus = false
                        Store.engine.pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        resumeAfterFocus = Store.state?.status == Status.PLAYING
                        Store.engine.pause()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (resumeAfterFocus) Store.engine.play()
                        resumeAfterFocus = false
                    }
                }
            }
            .build()
        focus = request
        audioManager.requestAudioFocus(request)
    }

    /** Android exige mostrar la notificación en cuanto arranca el servicio. */
    private fun showPlaceholder() {
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sonora")
            .setSilent(true)
            .build()
        goForeground(notification)
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, notification)
        }
        foreground = true
    }

    fun show(state: PlaybackState, starting: Boolean = false) {
        val track = state.track
        if (track == null) {
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
            return
        }
        if (track.cover != artworkUrl) loadArtwork(track.cover)
        val playing = state.status == Status.PLAYING || state.status == Status.LOADING

        media.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artists)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs.toLong())
                .apply { artwork?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        media.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    state.positionMs.toLong(),
                    if (state.status == Status.PLAYING) 1f else 0f,
                )
                .build(),
        )

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(track.title)
            .setContentText(if (state.remote) "En la sesión · ${track.artists}" else track.artists)
            .setLargeIcon(artwork)
            .setContentIntent(open)
            .setSilent(true)
            .setOngoing(playing)
            .setDeleteIntent(close)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_notification_previous, "Anterior", button(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(
                if (playing) R.drawable.ic_notification_pause else R.drawable.ic_notification_play,
                if (playing) "Pausar" else "Reproducir",
                button(PlaybackStateCompat.ACTION_PLAY_PAUSE),
            )
            .addAction(R.drawable.ic_notification_next, "Siguiente", button(PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .addAction(R.drawable.ic_notification_close, "Cerrar Sonora", close)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(media.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(close),
            )
            .build()
        // Android exige pasar a primer plano en cuanto arranca el servicio, aunque esté en pausa.
        if (playing || starting) goForeground(notification)
        if (!playing) {
            // En pausa la notificación se puede quitar (y quitarla cierra Sonora).
            if (foreground) stopForeground(STOP_FOREGROUND_DETACH)
            foreground = false
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification)
        }
    }

    private val close: PendingIntent
        get() = PendingIntent.getService(
            this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun button(action: Long) = MediaButtonReceiver.buildMediaButtonPendingIntent(this, action)

    private fun loadArtwork(url: String?) {
        artworkUrl = url
        artwork = null
        if (url == null) return
        Store.scope.launch {
            val request = ImageRequest.Builder(this@PlaybackService).data(fixUrl(url)).allowHardware(false).build()
            val image = SingletonImageLoader.get(this@PlaybackService).execute(request).image ?: return@launch
            if (artworkUrl != url) return@launch
            artwork = image.toBitmap()
            Store.state?.let { show(it) }
        }
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val ID = 1
        private const val ACTION_CLOSE = "org.sonora.mobile.CLOSE"
        var instance: PlaybackService? = null
            private set

        /** Arranca el servicio al empezar a sonar algo y lo mantiene al día. */
        fun update(context: Context, state: PlaybackState) {
            val running = instance
            if (running != null) {
                running.show(state)
                return
            }
            if (state.track != null && (state.status == Status.PLAYING || state.status == Status.LOADING)) {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            }
        }
    }
}

/** Algunos servicios dan direcciones sin esquema o http; Android prefiere https. */
fun fixUrl(url: String): String = when {
    url.startsWith("//") -> "https:$url"
    url.startsWith("http://") -> "https://" + url.removePrefix("http://")
    else -> url
}
