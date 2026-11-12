package org.sonora.mobile

import android.net.Uri
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.sonora_mobile.TrackItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Lo que Android Auto muestra para navegar: la cola, y por cada servicio con sesión iniciada sus
 * canciones guardadas y sus playlists. También resuelve qué tocar al elegir algo o al pedirlo por
 * voz («pon … en Sonora»).
 *
 * Ids: `queue`, `saved/<servicio>`, `playlists/<servicio>`, `playlist/<servicio>/<id>` para las
 * carpetas; `q/<índice>` para una canción de la cola y `t/<carpeta>/<índice>` para una canción de
 * una lista. Cada parte va codificada, así que un id con `/` no rompe nada.
 */
class AutoBrowser {
    /** Las canciones de cada lista que ya se mostró, para tocar la elegida con las demás detrás. */
    private val lists = ConcurrentHashMap<String, Pair<String, List<TrackItem>>>()

    /** Lo que hay dentro de una carpeta. Nunca falla: si el servicio no responde, sale vacía. */
    suspend fun children(parent: String): List<MediaItem> = withContext(Dispatchers.IO) {
        runCatching { load(parent) }.getOrElse { emptyList() }
    }

    private fun load(parent: String): List<MediaItem> {
        val parts = parent.split("/").map(Uri::decode)
        return when (parts.first()) {
            ROOT -> buildList {
                val state = Store.state
                if (state?.track != null) add(folder("queue", "Cola", "Lo que está sonando y lo que viene"))
                for (provider in Store.signedIn) {
                    add(folder(id("saved", provider.slug), "Tus canciones", provider.name))
                    add(folder(id("playlists", provider.slug), "Playlists", provider.name))
                }
            }
            "queue" -> Store.engine.queue().mapIndexed { index, track ->
                playable("q/$index", track)
            }
            "saved" -> tracks(parent, parts[1], Store.engine.libraryTracks(parts[1]))
            "playlists" -> Store.engine.libraryPlaylists(parts[1]).map { playlist ->
                folder(id("playlist", parts[1], playlist.id), playlist.title, playlist.owner, playlist.cover)
            }
            "playlist" -> tracks(parent, parts[1], Store.engine.playlist(parts[1], parts[2]).tracks)
            else -> emptyList()
        }
    }

    private fun tracks(parent: String, slug: String, tracks: List<TrackItem>): List<MediaItem> {
        lists[parent] = slug to tracks
        return tracks.mapIndexed { index, track -> playable(id("t", parent, index.toString()), track) }
    }

    /** Busca en el servicio elegido en la app (o el primero con sesión). */
    suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val slug = slug() ?: return@withContext emptyList()
        val found = runCatching { Store.engine.search(slug, query) }.getOrDefault(emptyList())
        tracks(SEARCH, slug, found)
    }

    /** Toca lo elegido en la pantalla del carro. */
    fun play(mediaId: String) {
        val parts = mediaId.split("/").map(Uri::decode)
        when (parts.first()) {
            "q" -> parts.getOrNull(1)?.toUIntOrNull()?.let { Store.engine.jump(it) }
            "t" -> {
                val (slug, tracks) = lists[parts.getOrNull(1)] ?: return
                val index = parts.getOrNull(2)?.toIntOrNull() ?: return
                Store.play(tracks, index, slug)
            }
        }
    }

    /** «Pon … en Sonora»: busca y toca lo primero. Sin nada que buscar, sigue con lo que había. */
    fun playSearch(query: String) {
        if (query.isBlank()) {
            Store.engine.play()
            return
        }
        Store.scope.launch {
            val slug = slug() ?: return@launch
            val found = withContext(Dispatchers.IO) {
                runCatching { Store.engine.search(slug, query) }.getOrDefault(emptyList())
            }
            if (found.isEmpty()) Store.problem = "No encontré «$query»." else Store.play(found, 0, slug)
        }
    }

    private fun slug(): String? =
        Store.signedIn.firstOrNull { it.slug == Store.selected }?.slug ?: Store.signedIn.firstOrNull()?.slug

    private fun folder(id: String, title: String, subtitle: String, cover: String? = null) = MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIconUri(icon(cover))
            .build(),
        MediaItem.FLAG_BROWSABLE,
    )

    private fun playable(id: String, track: TrackItem) = MediaItem(
        describe(track, id),
        MediaItem.FLAG_PLAYABLE,
    )

    companion object {
        const val ROOT = "root"
        private const val SEARCH = "search"

        private fun id(vararg parts: String) = parts.joinToString("/") { Uri.encode(it) }

        /** La portada como la puede abrir el carro: una dirección web o un archivo del teléfono. */
        private fun icon(cover: String?): Uri? = cover?.let { if (it.startsWith("file://")) Uri.parse(it) else Uri.parse(fixUrl(it)) }

        fun describe(track: TrackItem, id: String = track.id): MediaDescriptionCompat =
            MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(track.title)
                .setSubtitle(track.artists)
                .setDescription(track.album)
                .setIconUri(icon(track.cover))
                .build()
    }
}
