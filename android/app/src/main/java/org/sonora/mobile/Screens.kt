@file:OptIn(ExperimentalMaterial3Api::class)

package org.sonora.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import uniffi.sonora_mobile.PlaylistItem
import uniffi.sonora_mobile.GenreTile
import uniffi.sonora_mobile.Mix
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import uniffi.sonora_mobile.AlbumItem
import uniffi.sonora_mobile.MediaItem
import uniffi.sonora_mobile.PlaylistPage
import uniffi.sonora_mobile.SavedKind
import uniffi.sonora_mobile.SearchResults
import uniffi.sonora_mobile.Shelf
import uniffi.sonora_mobile.TrackItem

// ── Inicio ─────────────────────────────────────────────────────────────────────────────────

/** Lo que muestra Inicio: el feed del servicio y lo propio (me gusta y playlists). */
private class HomeData(val shelves: List<Shelf>, val liked: List<TrackItem>, val playlists: List<PlaylistItem>)

/** El tamaño de las tarjetas de Inicio: caben tres y asoma la cuarta. */
private val homeCard = 124.dp

@Composable
fun HomeScreen() {
    val slug = Store.selected
    Box(Modifier.fillMaxSize()) {
        // el color del tema se asoma arriba, detrás del saludo
        Box(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), Color.Transparent),
                    ),
                ),
        )
        Column(Modifier.fillMaxSize()) {
            if (Store.signedIn.isEmpty()) {
                Greeting()
                NeedsAccount()
                return@Column
            }
            Loader(key = "home-$slug-${Store.libraryRevision}", load = { engine ->
                HomeData(
                    shelves = engine.home(slug),
                    liked = runCatching { engine.libraryTracks(slug) }.getOrDefault(emptyList()),
                    playlists = runCatching { engine.libraryPlaylists(slug) }.getOrDefault(emptyList()),
                )
            }) { data ->
                val pick = remember(data) { recommendation(data.shelves) }
                LazyColumn(Modifier.fillMaxSize()) {
                    item { Greeting() }
                    item { ProviderPicker() }
                    pick?.let { item { Hero(it, data.shelves, slug) } }
                    item { MixesRow(slug) }
                    if (data.liked.isNotEmpty()) {
                        item { SectionTitle("Tus me gusta") }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                itemsIndexed(data.liked.take(30)) { index, track ->
                                    MediaCard(MediaItem.Track(track), slug, homeCard) { Store.play(data.liked, index, slug) }
                                }
                            }
                        }
                    }
                    if (data.playlists.isNotEmpty()) {
                        item { SectionTitle("Tus playlists") }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(data.playlists) { MediaCard(MediaItem.Playlist(it), slug, homeCard) {} }
                            }
                        }
                    }
                    if (data.shelves.isEmpty() && data.liked.isEmpty() && data.playlists.isEmpty()) {
                        item { Text("Este servicio no tiene página de inicio para esta cuenta.", Modifier.padding(16.dp)) }
                    }
                    items(data.shelves) { shelf -> ShelfRow(shelf, slug) }
                    item { Spacer(Modifier.size(16.dp)) }
                }
            }
        }
    }
}

/** «Hola, …» con el nombre de Ajustes › Personalización, y un saludo según la hora. */
@Composable
private fun Greeting() {
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val moment = when (hour) {
        in 5..11 -> "Buenos días"
        in 12..18 -> "Buenas tardes"
        else -> "Buenas noches"
    }
    val name = Store.userName
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
        Text(
            if (name.isEmpty()) "Hola 👋" else "Hola, $name",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(moment, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Lo que se recomienda en grande: al azar entre los álbumes y playlists del principio del feed. */
private fun recommendation(shelves: List<Shelf>): MediaItem? {
    val near = shelves.take(3).flatMap { it.items }
    val big = near.filter { it is MediaItem.Album || it is MediaItem.Playlist }
    return big.randomOrNull() ?: near.firstOrNull { it is MediaItem.Track }
}

/** La tarjeta grande de arriba: portada de fondo, título y un botón para escuchar. */
@Composable
private fun Hero(item: MediaItem, shelves: List<Shelf>, slug: String) {
    val nav = LocalNav.current
    val (cover, title, subtitle) = when (item) {
        is MediaItem.Album -> Triple(item.album.coverLarge ?: item.album.cover, item.album.title, item.album.artists)
        is MediaItem.Playlist -> Triple(item.playlist.cover, item.playlist.title, item.playlist.owner)
        is MediaItem.Track -> Triple(item.track.cover, item.track.title, item.track.artists)
        else -> return
    }
    fun open() {
        when (item) {
            is MediaItem.Album -> nav.navigate(Routes.album(slug, item.album.id))
            is MediaItem.Playlist -> nav.navigate(Routes.playlist(slug, item.playlist.id))
            is MediaItem.Track -> {
                val tracks = shelves.flatMap { shelf -> shelf.items.mapNotNull { (it as? MediaItem.Track)?.track } }
                Store.play(tracks, tracks.indexOfFirst { it.id == item.track.id }.coerceAtLeast(0), slug)
            }
            else -> {}
        }
    }
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .focusRing(RoundedCornerShape(20.dp))
            .clickable { open() },
    ) {
        // la portada más grande que dé el servicio, para que no se vea pixelada
        val big = remember(cover, slug) { cover?.let { runCatching { Store.engine.coverFull(slug, it) }.getOrDefault(it) } }
        big?.let {
            AsyncImage(
                model = fixUrl(it),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))),
            ),
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Te recomendamos", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            FilledIconButton(onClick = { open() }, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.PlayArrow, "Escuchar", Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun ShelfRow(shelf: Shelf, slug: String) {
    SectionTitle(shelf.title)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(shelf.items) { index, item ->
            MediaCard(item, slug, homeCard) {
                // reproduce las canciones del estante, empezando por la que se tocó
                val tracks = shelf.items.mapNotNull { (it as? MediaItem.Track)?.track }
                val tapped = (shelf.items[index] as? MediaItem.Track)?.track ?: return@MediaCard
                val start = tracks.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0)
                Store.play(tracks, start, slug)
            }
        }
    }
}

/** Los mixes del día: una fila de tarjetas que tocan el mix entero. */
@Composable
private fun MixesRow(slug: String) {
    var mixes by remember(slug) { mutableStateOf<List<Mix>?>(null) }
    LaunchedEffect(slug) {
        mixes = Store.run { it.dailyMixes(slug) }.getOrDefault(emptyList())
    }
    val found = mixes
    if (found != null && found.isEmpty()) return
    SectionTitle("Mixes para ti")
    if (found == null) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Armando tus mixes…", Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(found) { mix ->
            Column(
                Modifier
                    .width(156.dp)
                    .focusRing(RoundedCornerShape(16.dp))
                    .clickable { Store.play(mix.tracks, 0, slug) },
            ) {
                Box(Modifier.size(156.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    mix.cover?.let {
                        AsyncImage(model = fixUrl(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
                            ),
                        ),
                    )
                    Text(
                        mix.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    )
                }
                if (mix.subtitle.isNotEmpty()) {
                    Text(
                        mix.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** Colores para las tarjetas de Explorar, elegidos por el nombre del género. */
private val tileColors = listOf(
    Color(0xFFE8742C), Color(0xFF1E88E5), Color(0xFF2E7D32), Color(0xFF7E57C2),
    Color(0xFFD81B60), Color(0xFF00897B), Color(0xFF6D4C41), Color(0xFF3949AB),
)

/** Explorar: los géneros y estados de ánimo del servicio, de dos en dos. */
private fun androidx.compose.foundation.lazy.LazyListScope.explore(genres: List<GenreTile>, slug: String) {
    if (genres.isEmpty()) return
    item { SectionTitle("Explorar géneros y estados de ánimo") }
    items(genres.chunked(2)) { pair ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { genre -> GenreTileCard(genre, slug, Modifier.weight(1f)) }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun GenreTileCard(genre: GenreTile, slug: String, modifier: Modifier) {
    val nav = LocalNav.current
    val color = tileColors[Math.floorMod(genre.title.hashCode(), tileColors.size)]
    Box(
        modifier
            .height(92.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .focusRing(RoundedCornerShape(14.dp))
            .clickable { nav.navigate(Routes.genre(slug, genre.id, genre.title)) },
    ) {
        genre.cover?.let {
            AsyncImage(
                model = fixUrl(it),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 14.dp, y = 6.dp)
                    .size(72.dp)
                    .graphicsLayer { rotationZ = 22f }
                    .clip(RoundedCornerShape(6.dp)),
            )
        }
        Text(
            genre.title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp).fillMaxWidth(0.75f),
        )
    }
}

/** La página de un género: filas de playlists y álbumes. */
@Composable
fun GenreScreen(slug: String, id: String, title: String) {
    Column(Modifier.fillMaxSize()) {
        Bar(title.ifEmpty { "Explorar" }, back = true)
        Loader(key = "genre-$slug-$id", load = { it.genre(slug, id) }) { shelves ->
            LazyColumn(Modifier.fillMaxSize()) {
                if (shelves.isEmpty()) {
                    item { Text("Este género no tiene nada para mostrar.", Modifier.padding(16.dp)) }
                }
                items(shelves) { ShelfRow(it, slug) }
                item { Spacer(Modifier.size(16.dp)) }
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
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Explorar: los géneros del servicio elegido, para cuando no se está buscando nada
    var genres by remember { mutableStateOf(listOf<GenreTile>()) }
    val exploring = Store.selected
    LaunchedEffect(exploring) {
        genres = if (Store.signedIn.none { it.slug == exploring }) emptyList()
        else Store.run { it.genres(exploring) }.getOrDefault(emptyList())
    }

    suspend fun find(text: String, slug: String, loud: Boolean) {
        searching = true
        try {
            Store.run { it.searchAll(slug, text) }
                .onSuccess {
                    results = it
                    searchedIn = slug
                    failure = null
                }
                .onFailure {
                    // mientras se escribe, el error se ve en la lista en vez de en un aviso
                    failure = describe(it)
                    if (loud) Store.problem = describe(it)
                }
        } finally {
            searching = false
        }
    }

    fun run() {
        val text = query.trim()
        if (text.isEmpty() || Store.signedIn.isEmpty()) return
        Store.remember(text)
        val slug = Store.selected
        scope.launch { find(text, slug, loud = true) }
    }

    // Sugerencias de YouTube Music mientras se escribe, como en su app.
    var suggestions by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(query) {
        val text = query.trim()
        if (text.isEmpty() || Store.signedIn.isEmpty()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(150)
        val slug = Store.selected
        suggestions = withContext(Dispatchers.IO) { runCatching { Store.engine.suggestions(slug, text) }.getOrDefault(emptyList()) }
            .filterNot { it.equals(text, ignoreCase = true) }
            .distinct()
            .take(5)
    }

    // Búsqueda predictiva: los resultados se actualizan mientras se escribe, tras una pausa corta.
    val selected = Store.selected
    LaunchedEffect(query, selected) {
        val text = query.trim()
        if (text.length < 2 || Store.signedIn.isEmpty()) {
            if (text.isEmpty()) results = null
            return@LaunchedEffect
        }
        delay(350)
        find(text, selected, loud = false)
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
                if (query.isBlank() && Store.recent.isNotEmpty()) {
                    item { SectionTitle("Búsquedas recientes") }
                    items(Store.recent, key = { "recent-$it" }) { text ->
                        Row(
                            Modifier.fillMaxWidth().focusRing().clickable { query = text }.padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text, Modifier.weight(1f).padding(horizontal = 16.dp), maxLines = 1)
                            IconButton(onClick = { Store.forget(text) }) { Icon(Icons.Filled.Close, "Quitar") }
                        }
                    }
                }
                if (query.isBlank()) explore(genres, Store.selected)
                if (query.isNotBlank()) {
                    items(suggestions, key = { "suggestion-$it" }) { text ->
                        Row(
                            Modifier.fillMaxWidth().focusRing().clickable {
                                query = text
                                run()
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text, Modifier.weight(1f).padding(start = 16.dp), maxLines = 1)
                        }
                    }
                }
                failure?.takeIf { query.isNotBlank() }?.let { message ->
                    item {
                        Text(
                            "No se pudo buscar: $message",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                if (found != null && query.isNotBlank()) {
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
                        trackItems(found.tracks, searchedIn, similar = true)
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
