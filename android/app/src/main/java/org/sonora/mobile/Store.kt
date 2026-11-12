package org.sonora.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.sonora_mobile.AudioQuality
import uniffi.sonora_mobile.AudioSettings
import uniffi.sonora_mobile.Engine
import uniffi.sonora_mobile.EngineListener
import uniffi.sonora_mobile.PlaybackState
import uniffi.sonora_mobile.PlaylistItem
import uniffi.sonora_mobile.Prompt
import uniffi.sonora_mobile.ProviderInfo
import uniffi.sonora_mobile.SavedKind
import uniffi.sonora_mobile.SessionInfo
import uniffi.sonora_mobile.SignInMethod
import uniffi.sonora_mobile.TrackItem

/** Un aviso del motor que espera respuesta durante el inicio de sesión. */
data class PendingPrompt(val slug: String, val prompt: Prompt, val id: Long = System.nanoTime())

/** Canciones que esperan elegir a qué playlist van. */
data class PendingAdd(val slug: String, val tracks: List<TrackItem>)

/**
 * El estado de la app: cuentas, reproducción y avisos. Los avisos del motor llegan desde hilos
 * de Rust y se pasan al hilo principal; todo lo que bloquea al motor va en Dispatchers.IO.
 */
object Store : EngineListener {
    private val main = Handler(Looper.getMainLooper())
    val scope = MainScope()
    lateinit var engine: Engine
        private set
    lateinit var app: Context
        private set
    val minter = PoTokenMinter()

    var providers by mutableStateOf(listOf<ProviderInfo>())
    var state by mutableStateOf<PlaybackState?>(null)
    var prompt by mutableStateOf<PendingPrompt?>(null)
    var problem by mutableStateOf<String?>(null)
    var signingIn by mutableStateOf<String?>(null)
    var selected by mutableStateOf("youtube")
    var session by mutableStateOf<SessionInfo?>(null)
    var sessionBusy by mutableStateOf(false)
    var audio by mutableStateOf<AudioSettings?>(null)
    var adding by mutableStateOf<PendingAdd?>(null)
    var libraryRevision by mutableIntStateOf(0)

    val signedIn: List<ProviderInfo> get() = providers.filter { it.signedIn }

    fun start(context: Context) {
        if (::engine.isInitialized) return
        app = context.applicationContext
        engine = Engine(this)
        state = engine.state()
        val saved = savedAudio()
        if (saved != null) engine.setAudioSettings(saved)
        audio = saved ?: engine.audioSettings()
        refresh()
        restoreStored()
        if (preferences.getBoolean("local.on", false) && canReadMusic()) openLocal(quiet = true)
        minter.start()
    }

    fun refresh() {
        providers = engine.providers()
        if (signedIn.none { it.slug == selected }) signedIn.firstOrNull()?.let { selected = it.slug }
    }

    /** Corre algo que bloquea al motor fuera del hilo principal. */
    suspend fun <T> run(work: (Engine) -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching { work(engine) } }

    private fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    private fun restoreStored() {
        for (provider in providers.filter { it.stored }) {
            launch {
                run { it.restore(provider.slug) }.onFailure { problem = "${provider.name}: ${describe(it)}" }
                refresh()
            }
        }
    }

    // ── Cuentas ────────────────────────────────────────────────────────────────────────────

    fun signIn(provider: ProviderInfo, method: SignInMethod) {
        signingIn = provider.slug
        launch {
            val result = run { it.signIn(provider.slug, method) }
            signingIn = null
            prompt = null
            result.onSuccess {
                selected = provider.slug
                comeBack()
            }.onFailure { problem = describe(it) }
            refresh()
        }
    }

    /** Tras iniciar sesión en el navegador, intenta traer Sonora de vuelta al frente. */
    private fun comeBack() {
        runCatching {
            app.startActivity(
                android.content.Intent(app, MainActivity::class.java).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }
    }

    fun cancelSignIn() {
        engine.cancelSignIn()
        prompt = null
    }

    fun answer(text: String) = engine.sendInput(text)

    fun signOut(slug: String) {
        engine.signOut(slug)
        refresh()
    }

    // ── Reproducción ───────────────────────────────────────────────────────────────────────

    fun play(tracks: List<TrackItem>, index: Int, slug: String) = launch {
        run { it.playQueue(slug, tracks, index.toUInt()) }.onFailure { problem = describe(it) }
    }

    fun enqueue(tracks: List<TrackItem>, next: Boolean, slug: String) = launch {
        run { it.enqueue(slug, tracks, next) }.onFailure { problem = describe(it) }
    }

    fun startRadio(track: TrackItem, slug: String) = launch {
        run { it.radio(slug, track.id) }
            .onSuccess { found ->
                val queue = if (found.any { it.id == track.id }) found else listOf(track) + found
                play(queue, 0, slug)
            }
            .onFailure { problem = describe(it) }
    }

    fun save(kind: SavedKind, id: String, slug: String, saved: Boolean = true, quiet: Boolean = false) = launch {
        run { it.setSaved(slug, kind, id, saved) }
            .onSuccess {
                if (!quiet) problem = if (saved) "Guardado en tu biblioteca." else "Quitado de tu biblioteca."
            }
            .onFailure { problem = describe(it) }
    }

    // ── Playlists ──────────────────────────────────────────────────────────────────────────

    fun createPlaylist(name: String, slug: String) = launch {
        val clean = name.trim()
        if (clean.isEmpty()) return@launch
        run { it.createPlaylist(slug, clean) }
            .onSuccess { libraryRevision++ }
            .onFailure { problem = describe(it) }
    }

    suspend fun renamePlaylist(id: String, name: String, slug: String): Boolean =
        run { it.renamePlaylist(slug, id, name.trim()) }
            .onSuccess { libraryRevision++ }
            .onFailure { problem = describe(it) }
            .isSuccess

    suspend fun deletePlaylist(id: String, slug: String): Boolean =
        run { it.deletePlaylist(slug, id) }
            .onSuccess { libraryRevision++ }
            .onFailure { problem = describe(it) }
            .isSuccess

    fun addToPlaylist(playlist: PlaylistItem, pending: PendingAdd) = launch {
        adding = null
        run { it.addToPlaylist(pending.slug, playlist.id, pending.tracks.map { track -> track.id }) }
            .onSuccess {
                problem = "Agregada a «${playlist.title}»."
                libraryRevision++
            }
            .onFailure { problem = describe(it) }
    }

    suspend fun removeFromPlaylist(playlist: String, track: String, slug: String): Boolean =
        run { it.removeFromPlaylist(slug, playlist, track) }
            .onFailure { problem = describe(it) }
            .isSuccess

    // ── Sesiones ───────────────────────────────────────────────────────────────────────────

    fun startSession(slug: String = selected) {
        sessionBusy = true
        launch {
            run { it.sessionStart(slug) }.onFailure {
                problem = describe(it)
                sessionBusy = false
            }
        }
    }

    fun joinSession(code: String, remote: Boolean) {
        sessionBusy = true
        launch {
            run { it.sessionJoin(code, remote) }.onFailure {
                problem = describe(it)
                sessionBusy = false
            }
        }
    }

    /** Cierra Sonora del todo: sale de la sesión, para la música y termina el proceso. */
    fun shutdown() {
        runCatching {
            if (session != null) engine.sessionLeave()
            engine.pause()
        }
        PlaybackService.instance?.let {
            it.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            it.stopSelf()
        }
        main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
    }

    // ── Canciones del teléfono ─────────────────────────────────────────────────────────────

    var localBusy by mutableStateOf(false)

    /** El permiso para leer la música del teléfono (cambia de nombre en Android 13). */
    val musicPermission: String
        get() = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    fun canReadMusic() = androidx.core.content.ContextCompat.checkSelfPermission(app, musicPermission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Las carpetas donde buscar: Música y Descargas, más las que se agreguen. */
    val localFolders: List<String>
        get() = preferences.getStringSet("local.folders", null)?.sorted() ?: listOf(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC).path,
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path,
        )

    fun addLocalFolder(path: String) {
        preferences.edit().putStringSet("local.folders", (localFolders + path).toSet()).apply()
        openLocal()
    }

    fun removeLocalFolder(path: String) {
        preferences.edit().putStringSet("local.folders", (localFolders - path).toSet()).apply()
        if (localFolders.isEmpty()) closeLocal() else openLocal()
    }

    /** Busca (o vuelve a buscar) las canciones del teléfono. */
    fun openLocal(quiet: Boolean = false) {
        localBusy = true
        val folders = localFolders
        launch {
            run { it.openLocal(folders) }
                .onSuccess { preferences.edit().putBoolean("local.on", true).apply() }
                .onFailure { if (!quiet) problem = describe(it) }
            localBusy = false
            libraryRevision++
            refresh()
        }
    }

    fun closeLocal() {
        preferences.edit().putBoolean("local.on", false).apply()
        signOut("local")
    }

    // ── Scrobbling ─────────────────────────────────────────────────────────────────────────

    suspend fun linkScrobble(id: String, key: String, secret: String, token: String, server: String): Boolean {
        val result = run { it.scrobbleConnect(id, key, secret, token, server) }
        prompt = null
        result.onFailure { problem = describe(it) }
        return result.isSuccess
    }

    // ── Ajustes de audio ───────────────────────────────────────────────────────────────────

    private val preferences get() = app.getSharedPreferences("sonora", Context.MODE_PRIVATE)

    private fun savedAudio(): AudioSettings? {
        val prefs = preferences
        if (!prefs.contains("audio.quality")) return null
        val quality = runCatching { AudioQuality.valueOf(prefs.getString("audio.quality", "HIGH")!!) }
            .getOrDefault(AudioQuality.HIGH)
        val bands = prefs.getString("audio.bands", "")!!
            .split(",").mapNotNull { it.toDoubleOrNull() }
            .let { if (it.size == 10) it else List(10) { 0.0 } }
        return AudioSettings(
            quality = quality,
            normalisation = prefs.getBoolean("audio.normalisation", true),
            gapless = prefs.getBoolean("audio.gapless", true),
            equalizer = prefs.getBoolean("audio.equalizer", false),
            bands = bands,
        )
    }

    fun applyAudio(settings: AudioSettings) {
        audio = settings
        preferences.edit()
            .putString("audio.quality", settings.quality.name)
            .putBoolean("audio.normalisation", settings.normalisation)
            .putBoolean("audio.gapless", settings.gapless)
            .putBoolean("audio.equalizer", settings.equalizer)
            .putString("audio.bands", settings.bands.joinToString(","))
            .apply()
        launch { run { it.setAudioSettings(settings) } }
    }

    // ── Avisos del motor (llegan desde hilos de Rust) ──────────────────────────────────────

    override fun onPrompt(slug: String, prompt: Prompt) {
        main.post { this.prompt = PendingPrompt(slug, prompt) }
    }

    override fun onState(state: PlaybackState) {
        main.post {
            this.state = state
            PlaybackService.update(app, state)
        }
    }

    override fun onProblem(message: String) {
        main.post {
            problem = message
            sessionBusy = false
        }
    }

    override fun onSession(session: SessionInfo?) {
        main.post {
            this.session = session
            if (session != null) sessionBusy = false
        }
    }
}

/** El mensaje de un error del motor. */
fun describe(error: Throwable): String = error.message ?: error.toString()

/** `1:05` para 65 000 ms. */
fun clock(milliseconds: ULong): String {
    val seconds = (milliseconds / 1000u).toLong()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
