@file:OptIn(ExperimentalMaterial3Api::class)

package org.sonora.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uniffi.sonora_mobile.AlbumItem
import uniffi.sonora_mobile.MediaItem
import uniffi.sonora_mobile.PlaylistPage
import uniffi.sonora_mobile.SavedKind
import uniffi.sonora_mobile.SearchResults
import uniffi.sonora_mobile.Shelf
import uniffi.sonora_mobile.TrackItem

// ── Inicio ─────────────────────────────────────────────────────────────────────────────────

@Composable
fun HomeScreen() {
    Column(Modifier.fillMaxSize()) {
        Bar("Inicio")
        if (Store.signedIn.isEmpty()) {
            NeedsAccount()
            return@Column
        }
        val slug = Store.selected
        Loader(key = slug, load = { it.home(slug) }) { shelves ->
            LazyColumn(Modifier.fillMaxSize()) {
                item { ProviderPicker() }
                if (shelves.isEmpty()) {
                    item { Text("Este servicio no tiene página de inicio para esta cuenta.", Modifier.padding(16.dp)) }
                }
                items(shelves) { shelf -> ShelfRow(shelf, slug) }
                item { Spacer(Modifier.size(16.dp)) }
            }
        }
    }
}

@Composable
private fun ShelfRow(shelf: Shelf, slug: String) {
    SectionTitle(shelf.title)
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(shelf.items) { index, item ->
            MediaCard(item, slug) {
                // reproduce las canciones del estante, empezando por la que se tocó
                val tracks = shelf.items.mapNotNull { (it as? MediaItem.Track)?.track }
                val tapped = (shelf.items[index] as? MediaItem.Track)?.track ?: return@MediaCard
                val start = tracks.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0)
                Store.play(tracks, start, slug)
            }
        }
    }
}

// ── Buscar ─────────────────────────────────────────────────────────────────────────────────

@Composable
fun SearchScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var searchedIn by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun run() {
        val text = query.trim()
        if (text.isEmpty() || Store.signedIn.isEmpty()) return
        val slug = Store.selected
        searching = true
        scope.launch {
            Store.run { it.searchAll(slug, text) }
                .onSuccess {
                    results = it
                    searchedIn = slug
                }
                .onFailure { Store.problem = describe(it) }
            searching = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Bar("Buscar")
        if (Store.signedIn.isEmpty()) {
            NeedsAccount()
            return@Column
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Canciones, artistas, álbumes") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Borrar") }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        ProviderPicker()
        Box(Modifier.fillMaxSize()) {
            val found = results
            LazyColumn(Modifier.fillMaxSize()) {
                if (found != null) {
                    if (found.artists.isNotEmpty()) {
                        item { SectionTitle("Artistas") }
                        item {
                            LazyRow(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(found.artists) { MediaCard(MediaItem.Artist(it), searchedIn) {} }
                            }
                        }
                    }
                    if (found.tracks.isNotEmpty()) {
                        item { SectionTitle("Canciones") }
                        trackItems(found.tracks, searchedIn)
                    }
                    if (found.albums.isNotEmpty()) {
                        item { SectionTitle("Álbumes") }
                        item {
                            LazyRow(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(found.albums) { MediaCard(MediaItem.Album(it), searchedIn) {} }
                            }
                        }
                    }
                    if (found.playlists.isNotEmpty()) {
                        item { SectionTitle("Playlists") }
                        items(found.playlists) { PlaylistRow(it, searchedIn) }
                    }
                }
            }
            if (searching) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

// ── Biblioteca ─────────────────────────────────────────────────────────────────────────────

private val sections = listOf("Playlists", "Canciones", "Álbumes", "Artistas")

@Composable
fun LibraryScreen() {
    var section by rememberSaveable { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Scaffold(
        topBar = { Bar("Biblioteca") },
        floatingActionButton = {
            if (Store.signedIn.isNotEmpty() && section == 0) {
                FloatingActionButton(onClick = {
                    name = ""
                    creating = true
                }) { Icon(Icons.Filled.Add, "Nueva playlist") }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (Store.signedIn.isEmpty()) {
                NeedsAccount()
                return@Column
            }
            ProviderPicker()
            TabRow(selectedTabIndex = section) {
                sections.forEachIndexed { index, title ->
                    Tab(selected = section == index, onClick = { section = index }, text = { Text(title) })
                }
            }
            val slug = Store.selected
            val revision = Store.libraryRevision
            when (section) {
                0 -> Loader(key = "playlists-$slug-$revision", load = { it.libraryPlaylists(slug) }) { list ->
                    if (list.isEmpty()) Empty()
                    LazyColumn(Modifier.fillMaxSize()) { items(list) { PlaylistRow(it, slug) } }
                }
                1 -> Loader(key = "tracks-$slug-$revision", load = { it.libraryTracks(slug) }) { list ->
                    if (list.isEmpty()) Empty()
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (list.isNotEmpty()) item { PlayButtons(list, slug) }
                        trackItems(list, slug)
                    }
                }
                2 -> Loader(key = "albums-$slug-$revision", load = { it.libraryAlbums(slug) }) { list ->
                    if (list.isEmpty()) Empty()
                    LazyColumn(Modifier.fillMaxSize()) { items(list) { AlbumRow(it, slug) } }
                }
                else -> Loader(key = "artists-$slug-$revision", load = { it.libraryArtists(slug) }) { list ->
                    if (list.isEmpty()) Empty()
                    LazyColumn(Modifier.fillMaxSize()) { items(list) { ArtistRow(it, slug) } }
                }
            }
        }
    }
    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Nueva playlist") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    creating = false
                    Store.createPlaylist(name, Store.selected)
                }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Empty() {
    Text("Nada guardado aquí todavía.", Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
}

// ── Álbum ──────────────────────────────────────────────────────────────────────────────────

@Composable
fun AlbumScreen(slug: String, id: String) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize()) {
        Bar("Álbum", back = true) {
            IconButton(onClick = { Store.save(SavedKind.ALBUM, id, slug) }) { Icon(Icons.Filled.FavoriteBorder, "Guardar") }
        }
        Loader(key = id, load = { it.album(slug, id) }) { page ->
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Header(page.album.coverLarge, page.album.title) {
                        val artist = page.album.artistId
                        TextButton(onClick = { artist?.let { nav.navigate(Routes.artist(slug, it)) } }, enabled = artist != null) {
                            Text(page.album.artists)
                        }
                        Text(subtitleOf(page.album), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { PlayButtons(page.tracks, slug) }
                trackItems(page.tracks, slug, numbered = true, covers = false)
                val footer = listOf(page.releaseDate, page.label).filter { it.isNotEmpty() }.joinToString(" · ")
                if (footer.isNotEmpty()) {
                    item { Text(footer, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

private fun subtitleOf(album: AlbumItem) = if (album.year > 0) "${album.kind} · ${album.year}" else album.kind

@Composable
private fun Header(cover: String?, title: String, round: Boolean = false, extra: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Cover(cover, 220.dp, round)
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        extra()
    }
}

// ── Playlist ───────────────────────────────────────────────────────────────────────────────

@Composable
fun PlaylistScreen(slug: String, id: String) {
    Loader(key = id, load = { it.playlist(slug, id) }) { page -> PlaylistBody(page, slug) }
}

@Composable
private fun PlaylistBody(page: PlaylistPage, slug: String) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf(page.tracks) }
    var continuation by remember { mutableStateOf(page.continuation) }
    var loading by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(page.playlist.title) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    fun remove(index: Int) {
        val track = tracks.getOrNull(index) ?: return
        tracks = tracks.toMutableList().also { it.removeAt(index) }
        scope.launch {
            if (!Store.removeFromPlaylist(page.playlist.id, track.id, slug)) {
                tracks = tracks.toMutableList().also { it.add(index.coerceAtMost(it.size), track) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Bar("Playlist", back = true) {
            if (page.playlist.owned) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Opciones") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Renombrar") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = {
                            menu = false
                            name = title
                            renaming = true
                        })
                        DropdownMenuItem(text = { Text("Eliminar playlist") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = {
                            menu = false
                            deleting = true
                        })
                    }
                }
            } else {
                IconButton(onClick = { Store.save(SavedKind.PLAYLIST, page.playlist.id, slug) }) {
                    Icon(Icons.Filled.FavoriteBorder, "Guardar")
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Header(page.playlist.cover, title) {
                    Text(page.playlist.owner, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { PlayButtons(tracks, slug) }
            trackItems(tracks, slug, onRemove = if (page.playlist.owned) ::remove else null)
            continuation?.let { next ->
                item {
                    TextButton(
                        onClick = {
                            loading = true
                            scope.launch {
                                Store.run { it.playlistMore(slug, next) }
                                    .onSuccess {
                                        tracks = tracks + it.tracks
                                        continuation = it.continuation
                                    }
                                    .onFailure { Store.problem = describe(it) }
                                loading = false
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (loading) "Cargando…" else "Cargar más") }
                }
            }
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Renombrar playlist") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    val wanted = name
                    scope.launch { if (Store.renamePlaylist(page.playlist.id, wanted, slug)) title = wanted }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancelar") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("¿Eliminar «$title»?") },
            text = { Text("Se borra también en el servicio.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch { if (Store.deletePlaylist(page.playlist.id, slug)) nav.popBackStack() }
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } },
        )
    }
}

// ── Artista ────────────────────────────────────────────────────────────────────────────────

@Composable
fun ArtistScreen(slug: String, id: String) {
    val scope = rememberCoroutineScope()
    var biography by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Bar("Artista", back = true)
        Loader(key = id, load = { it.artist(slug, id) }) { page ->
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Header(page.cover, page.name, round = true) {
                        page.monthlyListeners?.let {
                            Text("%,d oyentes mensuales".format(it.toLong()), style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.size(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    Store.run { it.artistTracks(slug, id) }
                                        .onSuccess { tracks ->
                                            if (tracks.isEmpty()) Store.problem = "Este artista no tiene canciones para reproducir."
                                            else Store.play(tracks, 0, slug)
                                        }
                                        .onFailure { Store.problem = describe(it) }
                                }
                            }) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Text("Reproducir")
                            }
                            FilledTonalButton(onClick = { Store.save(SavedKind.ARTIST, id, slug) }) {
                                Icon(Icons.Filled.PersonAdd, null)
                                Text("Seguir")
                            }
                        }
                    }
                }
                if (page.topTracks.isNotEmpty()) {
                    item { SectionTitle("Populares") }
                    trackItems(page.topTracks.take(10), slug)
                }
                albumShelf("Álbumes", page.albums, slug)
                albumShelf("Sencillos y EP", page.singles, slug)
                albumShelf("Aparece en", page.appearsOn, slug)
                page.biography?.takeIf { it.isNotBlank() }?.let { text ->
                    item { SectionTitle("Biografía") }
                    item {
                        Text(
                            text,
                            maxLines = if (biography) Int.MAX_VALUE else 4,
                            modifier = Modifier.padding(horizontal = 16.dp).clickable { biography = !biography },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item { Spacer(Modifier.size(24.dp)) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.albumShelf(title: String, albums: List<AlbumItem>, slug: String) {
    if (albums.isEmpty()) return
    item { SectionTitle(title) }
    item {
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums) { MediaCard(MediaItem.Album(it), slug) {} }
        }
    }
}

// ── Agregar a una playlist ─────────────────────────────────────────────────────────────────

@Composable
fun PlaylistPicker(pending: PendingAdd) {
    AlertDialog(
        onDismissRequest = { Store.adding = null },
        title = { Text("Agregar a una playlist") },
        text = {
            Box(Modifier.size(width = 320.dp, height = 360.dp)) {
                Loader(key = "picker-${pending.slug}-${Store.libraryRevision}", load = { it.libraryPlaylists(pending.slug) }) { all ->
                    val own = all.filter { it.owned }
                    if (own.isEmpty()) {
                        Text("No tienes playlists propias en este servicio. Crea una en Biblioteca con el botón +.")
                    } else {
                        LazyColumn {
                            items(own) { playlist ->
                                ListRow(playlist.cover, playlist.title, "") { Store.addToPlaylist(playlist, pending) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { Store.adding = null }) { Text("Cancelar") } },
    )
}
