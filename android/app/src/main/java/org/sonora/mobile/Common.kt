@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package org.sonora.mobile

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explicit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import uniffi.sonora_mobile.AlbumItem
import uniffi.sonora_mobile.ArtistItem
import uniffi.sonora_mobile.Engine
import uniffi.sonora_mobile.MediaItem
import uniffi.sonora_mobile.PlaylistItem
import uniffi.sonora_mobile.SavedKind
import uniffi.sonora_mobile.TrackItem

/** La navegación de la pestaña actual, para abrir álbumes, playlists y artistas. */
val LocalNav = staticCompositionLocalOf<NavHostController> { error("sin navegación") }

/** Se usa con control remoto (Android TV, proyectores): sin tocar, con flechas. */
val LocalTv = staticCompositionLocalOf { false }

/** La pantalla es ancha (TV, tablet): menú lateral y reproductor a dos columnas. */
val LocalWide = staticCompositionLocalOf { false }

/**
 * Un recuadro bien visible alrededor de lo que tiene el foco, para moverse con las flechas del
 * control. Va antes de `clickable` para enterarse del foco de ese elemento.
 */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(10.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    this
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused) Modifier.border(3.dp, color, shape).background(color.copy(alpha = 0.12f), shape)
            else Modifier,
        )
}

object Routes {
    fun album(slug: String, id: String) = "album/${Uri.encode(slug)}/${Uri.encode(id)}"
    fun playlist(slug: String, id: String) = "playlist/${Uri.encode(slug)}/${Uri.encode(id)}"
    fun artist(slug: String, id: String) = "artist/${Uri.encode(slug)}/${Uri.encode(id)}"
}

/** Una portada. `under` (la miniatura ya cargada) se ve mientras llega `url`, la grande. */
@Composable
fun Cover(url: String?, size: Dp, round: Boolean = false, under: String? = null) {
    val shape = if (round) CircleShape else RoundedCornerShape(size * 0.08f)
    Box(
        Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (round) Icons.Filled.Mic else Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (under != null && under != url) {
            AsyncImage(
                model = fixUrl(under),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (url != null) {
            AsyncImage(
                model = fixUrl(url),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Una barra superior con título y, si se puede volver, flecha atrás. */
@Composable
fun Bar(title: String, back: Boolean = false, actions: @Composable () -> Unit = {}) {
    val nav = LocalNav.current
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (back) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                }
            }
        },
        actions = { actions() },
    )
}

private object Unset

/** Carga algo del motor fuera del hilo principal y lo muestra, con espera y reintento. */
@Composable
fun <T> Loader(key: Any, load: (Engine) -> T, content: @Composable (T) -> Unit) {
    var value by remember(key) { mutableStateOf<Any?>(Unset) }
    var failure by remember(key) { mutableStateOf<String?>(null) }
    var attempt by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, attempt) {
        failure = null
        Store.run(load)
            .onSuccess { value = it }
            .onFailure { failure = describe(it) }
    }
    val current = value
    when {
        current !== Unset -> {
            @Suppress("UNCHECKED_CAST")
            content(current as T)
        }
        failure != null -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.size(12.dp))
            Text(failure ?: "", textAlign = TextAlign.Center)
            TextButton(onClick = { attempt++ }) { Text("Reintentar") }
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

@Composable
fun ProviderPicker() {
    val signedIn = Store.signedIn
    if (signedIn.size < 2) return
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        signedIn.forEachIndexed { index, provider ->
            SegmentedButton(
                selected = Store.selected == provider.slug,
                onClick = { Store.selected = provider.slug },
                shape = SegmentedButtonDefaults.itemShape(index, signedIn.size),
            ) { Text(provider.name) }
        }
    }
}

@Composable
fun NeedsAccount() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "Primero entra en Ajustes y elige YouTube Music (se puede sin cuenta) o Spotify.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Canciones ──────────────────────────────────────────────────────────────────────────────

/** Una lista de canciones que se reproduce desde la que se toque. */
fun LazyListScope.trackItems(
    tracks: List<TrackItem>,
    slug: String,
    numbered: Boolean = false,
    covers: Boolean = true,
    onRemove: ((Int) -> Unit)? = null,
    /** Tocar una suena seguida de parecidas (búsqueda), en vez de seguir con esta lista. */
    similar: Boolean = false,
) {
    itemsIndexed(tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
        val state = Store.state
        TrackRow(
            track = track,
            slug = slug,
            current = state?.provider == slug && state.track?.id == track.id,
            number = if (numbered) index + 1 else null,
            cover = covers,
            onClick = { if (similar) Store.playSimilar(track, slug) else Store.play(tracks, index, slug) },
            onRemove = onRemove?.let { { it(index) } },
        )
    }
}

@Composable
fun TrackRow(
    track: TrackItem,
    slug: String,
    current: Boolean,
    number: Int? = null,
    cover: Boolean = true,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .focusRing()
                .combinedClickable(enabled = track.playable, onClick = onClick, onLongClick = { menu = true })
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                .alpha(if (track.playable) 1f else 0.4f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (number != null) {
                Text("$number", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
            }
            if (cover) Cover(track.cover, 48.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        track.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (track.explicit) {
                        Icon(
                            Icons.Filled.Explicit, contentDescription = "Explícita",
                            modifier = Modifier.size(16.dp).padding(start = 4.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    track.artists.ifEmpty { track.album },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(clock(track.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // con el control remoto no se puede mantener pulsado
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más opciones") }
        }
        TrackMenu(track, slug, menu, onDismiss = { menu = false }, onRemove = onRemove)
    }
}

/** El menú que aparece al mantener pulsada una canción. */
@Composable
fun TrackMenu(track: TrackItem, slug: String, open: Boolean, onDismiss: () -> Unit, onRemove: (() -> Unit)? = null) {
    val nav = LocalNav.current
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        fun pick(action: () -> Unit) {
            onDismiss()
            action()
        }
        DropdownMenuItem(
            text = { Text("Reproducir a continuación") },
            leadingIcon = { Icon(Icons.Filled.SkipNext, null) },
            onClick = { pick { Store.enqueue(listOf(track), true, slug) } },
        )
        DropdownMenuItem(
            text = { Text("Agregar a la cola") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
            onClick = { pick { Store.enqueue(listOf(track), false, slug) } },
        )
        DropdownMenuItem(
            text = { Text("Iniciar radio") },
            leadingIcon = { Icon(Icons.Filled.Radio, null) },
            onClick = { pick { Store.startRadio(track, slug) } },
        )
        DropdownMenuItem(
            text = { Text("Guardar en tu biblioteca") },
            leadingIcon = { Icon(Icons.Filled.Favorite, null) },
            onClick = { pick { Store.save(SavedKind.TRACK, track.id, slug) } },
        )
        DropdownMenuItem(
            text = { Text("Agregar a una playlist…") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
            onClick = { pick { Store.adding = PendingAdd(slug, listOf(track)) } },
        )
        track.albumId?.let { album ->
            DropdownMenuItem(
                text = { Text("Ir al álbum") },
                leadingIcon = { Icon(Icons.Filled.Album, null) },
                onClick = { pick { nav.navigate(Routes.album(slug, album)) } },
            )
        }
        track.artistId?.let { artist ->
            DropdownMenuItem(
                text = { Text("Ir al artista") },
                leadingIcon = { Icon(Icons.Filled.Person, null) },
                onClick = { pick { nav.navigate(Routes.artist(slug, artist)) } },
            )
        }
        onRemove?.let { remove ->
            DropdownMenuItem(
                text = { Text("Quitar de esta playlist") },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { pick(remove) },
            )
        }
    }
}

/** "Reproducir" y "Aleatorio" para una lista de canciones. */
@Composable
fun PlayButtons(tracks: List<TrackItem>, slug: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = { Store.play(tracks, 0, slug) }, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.PlayArrow, null)
            Text("Reproducir")
        }
        FilledTonalButton(onClick = { Store.play(tracks.shuffled(), 0, slug) }, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Shuffle, null)
            Text("Aleatorio")
        }
    }
}

// ── Tarjetas y filas ───────────────────────────────────────────────────────────────────────

@Composable
fun MediaCard(item: MediaItem, slug: String, size: Dp = 140.dp, onPlay: () -> Unit) {
    val nav = LocalNav.current
    var menu by remember { mutableStateOf(false) }
    when (item) {
        is MediaItem.Track -> Box {
            Card(item.track.cover, item.track.title, item.track.artists,
                Modifier.focusRing().combinedClickable(onClick = onPlay, onLongClick = { menu = true }), size = size)
            TrackMenu(item.track, slug, menu, onDismiss = { menu = false })
        }
        is MediaItem.Album -> Card(item.album.cover, item.album.title, subtitle(item.album),
            Modifier.focusRing().clickable { nav.navigate(Routes.album(slug, item.album.id)) }, size = size)
        is MediaItem.Artist -> Card(item.artist.cover, item.artist.name, "Artista",
            Modifier.focusRing().clickable { nav.navigate(Routes.artist(slug, item.artist.id)) }, round = true, size = size)
        is MediaItem.Playlist -> Card(item.playlist.cover, item.playlist.title, item.playlist.owner,
            Modifier.focusRing().clickable { nav.navigate(Routes.playlist(slug, item.playlist.id)) }, size = size)
        is MediaItem.Genre -> Card(item.cover, item.title, "", Modifier.alpha(0.6f), size = size)
    }
}

@Composable
fun Card(cover: String?, title: String, subtitle: String, modifier: Modifier, round: Boolean = false, size: Dp = 140.dp) {
    Column(
        modifier.width(size).padding(4.dp),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Cover(cover, size - 8.dp, round)
        Spacer(Modifier.size(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (round) TextAlign.Center else TextAlign.Start,
        )
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun subtitle(album: AlbumItem): String = if (album.year > 0) "${album.kind} · ${album.year}" else album.artists

@Composable
fun ListRow(cover: String?, title: String, subtitle: String, round: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusRing().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(cover, 52.dp, round)
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun PlaylistRow(playlist: PlaylistItem, slug: String) {
    val nav = LocalNav.current
    val detail = if (playlist.trackCount > 0u) "${playlist.owner} · ${playlist.trackCount} canciones" else playlist.owner
    ListRow(playlist.cover, playlist.title, detail) { nav.navigate(Routes.playlist(slug, playlist.id)) }
}

@Composable
fun AlbumRow(album: AlbumItem, slug: String) {
    val nav = LocalNav.current
    val detail = if (album.year > 0) "${album.artists} · ${album.year}" else album.artists
    ListRow(album.cover, album.title, detail) { nav.navigate(Routes.album(slug, album.id)) }
}

@Composable
fun ArtistRow(artist: ArtistItem, slug: String) {
    val nav = LocalNav.current
    ListRow(artist.cover, artist.name, "", round = true) { nav.navigate(Routes.artist(slug, artist.id)) }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}
