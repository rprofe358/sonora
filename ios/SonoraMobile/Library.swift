import SwiftUI

// MARK: - Navegación

/// Las páginas a las que se puede ir desde cualquier pestaña.
enum Route: Hashable {
    case album(slug: String, id: String)
    case playlist(slug: String, id: String)
    case artist(slug: String, id: String)
}

private struct OpenRouteKey: EnvironmentKey {
    static let defaultValue: (Route) -> Void = { _ in }
}

extension EnvironmentValues {
    /// Abre una página en la pestaña actual (para botones y menús, donde un enlace no sirve).
    var openRoute: (Route) -> Void {
        get { self[OpenRouteKey.self] }
        set { self[OpenRouteKey.self] = newValue }
    }
}

/// Una pila de navegación que sabe abrir álbumes, playlists y artistas.
struct RoutedStack<Content: View>: View {
    @State private var path = NavigationPath()
    @ViewBuilder let content: () -> Content

    var body: some View {
        NavigationStack(path: $path) {
            content()
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .album(let slug, let id): AlbumView(slug: slug, id: id)
                    case .playlist(let slug, let id): PlaylistView(slug: slug, id: id)
                    case .artist(let slug, let id): ArtistView(slug: slug, id: id)
                    }
                }
        }
        .environment(\.openRoute, { route in path.append(route) })
    }
}

/// Carga algo del motor fuera del hilo principal y lo muestra, con espera y reintento.
struct Loader<Value, Content: View>: View {
    @EnvironmentObject private var store: Store
    let key: String
    let load: (Engine) throws -> Value
    @ViewBuilder let content: (Value) -> Content
    @State private var value: Value?
    @State private var loaded: String?
    @State private var failure: String?

    var body: some View {
        Group {
            if let value, loaded == key {
                content(value)
            } else if let failure {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.largeTitle)
                        .foregroundStyle(.secondary)
                    Text(failure)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                    Button("Reintentar") { Task { await fetch() } }
                }
                .padding()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: key) { await fetch() }
    }

    private func fetch() async {
        failure = nil
        let wanted = key
        switch await store.run(load) {
        case .success(let found):
            value = found
            loaded = wanted
        case .failure(let error):
            failure = describe(error)
        }
    }
}

struct ProviderPicker: View {
    @EnvironmentObject private var store: Store

    var body: some View {
        if store.signedIn.count > 1 {
            Picker("Servicio", selection: $store.selected) {
                ForEach(store.signedIn, id: \.slug) { provider in
                    Text(provider.name).tag(provider.slug)
                }
            }
            .pickerStyle(.segmented)
        }
    }
}

struct NeedsAccount: View {
    var body: some View {
        Text("Primero entra en la pestaña **Cuentas** y elige YouTube Music (se puede sin cuenta) o Spotify.")
            .foregroundStyle(.secondary)
            .padding()
    }
}

// MARK: - Menú de canción

extension View {
    /// El menú que aparece al mantener pulsada una canción.
    func trackMenu(_ track: TrackItem, slug: String) -> some View {
        modifier(TrackMenu(track: track, slug: slug))
    }
}

struct TrackMenu: ViewModifier {
    @EnvironmentObject private var store: Store
    @Environment(\.openRoute) private var open
    let track: TrackItem
    let slug: String

    func body(content: Content) -> some View {
        let store = self.store
        let open = self.open
        let track = self.track
        let slug = self.slug
        return content.contextMenu {
            Button {
                store.enqueue([track], next: true, from: slug)
            } label: {
                Label("Reproducir a continuación", systemImage: "text.line.first.and.arrowtriangle.forward")
            }
            Button {
                store.enqueue([track], next: false, from: slug)
            } label: {
                Label("Agregar a la cola", systemImage: "text.append")
            }
            Button {
                store.startRadio(track, from: slug)
            } label: {
                Label("Iniciar radio", systemImage: "dot.radiowaves.left.and.right")
            }
            Button {
                store.save(.track, id: track.id, from: slug)
            } label: {
                Label("Guardar en tu biblioteca", systemImage: "heart")
            }
            Button {
                store.addingToPlaylist = PendingAdd(slug: slug, tracks: [track])
            } label: {
                Label("Agregar a una playlist…", systemImage: "text.badge.plus")
            }
            if let album = track.albumId {
                Button {
                    open(.album(slug: slug, id: album))
                } label: {
                    Label("Ir al álbum", systemImage: "square.stack")
                }
            }
            if let artist = track.artistId {
                Button {
                    open(.artist(slug: slug, id: artist))
                } label: {
                    Label("Ir al artista", systemImage: "music.mic")
                }
            }
        }
    }
}

// MARK: - Tarjetas y filas

struct MediaCard: View {
    @Environment(\.openRoute) private var open
    let item: MediaItem
    let slug: String
    var size: CGFloat = 140
    let onPlay: () -> Void

    var body: some View {
        switch item {
        case .track(let track):
            Button(action: onPlay) {
                card(track.cover, track.title, track.artists)
            }
            .buttonStyle(.plain)
            .trackMenu(track, slug: slug)
        case .album(let album):
            Button { open(.album(slug: slug, id: album.id)) } label: {
                card(album.cover, album.title, subtitle(album))
            }
            .buttonStyle(.plain)
        case .artist(let artist):
            Button { open(.artist(slug: slug, id: artist.id)) } label: {
                card(artist.cover, artist.name, "Artista", round: true)
            }
            .buttonStyle(.plain)
        case .playlist(let playlist):
            Button { open(.playlist(slug: slug, id: playlist.id)) } label: {
                card(playlist.cover, playlist.title, playlist.owner)
            }
            .buttonStyle(.plain)
        case .genre(_, let title, let cover):
            card(cover, title, "")
                .opacity(0.6)
        }
    }

    private func card(_ cover: String?, _ title: String, _ subtitle: String, round: Bool = false) -> some View {
        VStack(alignment: round ? .center : .leading, spacing: 4) {
            Cover(url: cover, size: size, round: round)
            Text(title)
                .font(.subheadline.weight(.medium))
                .lineLimit(2)
                .multilineTextAlignment(round ? .center : .leading)
            if !subtitle.isEmpty {
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(width: size, alignment: round ? .center : .leading)
        .contentShape(Rectangle())
    }

    private func subtitle(_ album: AlbumItem) -> String {
        album.year > 0 ? "\(album.kind) · \(album.year)" : album.artists
    }
}

struct PlaylistRow: View {
    let playlist: PlaylistItem
    let slug: String

    var body: some View {
        NavigationLink(value: Route.playlist(slug: slug, id: playlist.id)) {
            HStack(spacing: 12) {
                Cover(url: playlist.cover, size: 52)
                VStack(alignment: .leading, spacing: 2) {
                    Text(playlist.title).lineLimit(1)
                    Text(playlist.trackCount > 0 ? "\(playlist.owner) · \(playlist.trackCount) canciones" : playlist.owner)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
    }
}

struct AlbumRow: View {
    let album: AlbumItem
    let slug: String

    var body: some View {
        NavigationLink(value: Route.album(slug: slug, id: album.id)) {
            HStack(spacing: 12) {
                Cover(url: album.cover, size: 52)
                VStack(alignment: .leading, spacing: 2) {
                    Text(album.title).lineLimit(1)
                    Text(album.year > 0 ? "\(album.artists) · \(album.year)" : album.artists)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
    }
}

struct ArtistRow: View {
    let artist: ArtistItem
    let slug: String

    var body: some View {
        NavigationLink(value: Route.artist(slug: slug, id: artist.id)) {
            HStack(spacing: 12) {
                Cover(url: artist.cover, size: 52, round: true)
                Text(artist.name).lineLimit(1)
            }
        }
    }
}

/// Una fila horizontal de álbumes, para la página de artista.
struct AlbumShelf: View {
    let title: String
    let albums: [AlbumItem]
    let slug: String

    var body: some View {
        if !albums.isEmpty {
            Section(title) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 12) {
                        ForEach(albums, id: \.id) { album in
                            MediaCard(item: .album(album: album), slug: slug) {}
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Inicio

/// Lo que muestra Inicio: el feed del servicio y lo propio (me gusta y playlists).
struct HomeData {
    let shelves: [Shelf]
    let liked: [TrackItem]
    let playlists: [PlaylistItem]
}

/// El tamaño de las tarjetas de Inicio: caben tres y asoma la cuarta.
private let homeCard: CGFloat = 112

struct HomeView: View {
    @EnvironmentObject private var store: Store

    var body: some View {
        RoutedStack {
            ZStack(alignment: .top) {
                // el color del tema se asoma arriba, detrás del saludo
                LinearGradient(
                    colors: [store.palette.color.opacity(0.35), .clear],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .frame(height: 320)
                .ignoresSafeArea(edges: .top)
                if store.signedIn.isEmpty {
                    VStack(alignment: .leading) {
                        Greeting()
                        NeedsAccount()
                    }
                } else {
                    let slug = store.selected
                    Loader(key: "home-\(slug)-\(store.libraryRevision)", load: { engine in
                        HomeData(
                            shelves: try engine.home(slug: slug),
                            liked: (try? engine.libraryTracks(slug: slug)) ?? [],
                            playlists: (try? engine.libraryPlaylists(slug: slug)) ?? []
                        )
                    }) { data in
                        HomeFeed(data: data, slug: slug)
                    }
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .withPlayerBar()
        }
    }
}

private struct HomeFeed: View {
    @EnvironmentObject private var store: Store
    let data: HomeData
    let slug: String
    @State private var pick: MediaItem?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 24) {
                Greeting()
                ProviderPicker().padding(.horizontal)
                if let pick {
                    Hero(item: pick, shelves: data.shelves, slug: slug)
                }
                if !data.liked.isEmpty {
                    row("Tus me gusta") {
                        ForEach(Array(data.liked.prefix(30).enumerated()), id: \.offset) { index, track in
                            MediaCard(item: .track(track: track), slug: slug, size: homeCard) {
                                store.play(data.liked, at: index, from: slug)
                            }
                        }
                    }
                }
                if !data.playlists.isEmpty {
                    row("Tus playlists") {
                        ForEach(data.playlists, id: \.id) { playlist in
                            MediaCard(item: .playlist(playlist: playlist), slug: slug, size: homeCard) {}
                        }
                    }
                }
                if data.shelves.isEmpty && data.liked.isEmpty && data.playlists.isEmpty {
                    Text("Este servicio no tiene página de inicio para esta cuenta.")
                        .foregroundStyle(.secondary)
                        .padding()
                }
                ForEach(Array(data.shelves.enumerated()), id: \.offset) { _, shelf in
                    ShelfView(shelf: shelf, slug: slug, size: homeCard)
                }
            }
            .padding(.bottom)
        }
        .onAppear {
            if pick == nil { pick = recommendation(data.shelves) }
        }
    }

    private func row<Cards: View>(_ title: String, @ViewBuilder cards: () -> Cards) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.title3.bold()).padding(.horizontal)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 10) { cards() }
                    .padding(.horizontal)
            }
        }
    }

    /// Lo que se recomienda en grande: al azar entre los álbumes y playlists del principio del feed.
    private func recommendation(_ shelves: [Shelf]) -> MediaItem? {
        let near = shelves.prefix(3).flatMap(\.items)
        let big = near.filter {
            switch $0 {
            case .album, .playlist: true
            default: false
            }
        }
        if let chosen = big.randomElement() { return chosen }
        return near.first {
            if case .track = $0 { return true }
            return false
        }
    }
}

/// «Hola, …» con el nombre de Ajustes › Personalización, y un saludo según la hora.
struct Greeting: View {
    @EnvironmentObject private var store: Store

    var body: some View {
        let hour = Calendar.current.component(.hour, from: Date())
        let moment = switch hour {
        case 5..<12: "Buenos días"
        case 12..<19: "Buenas tardes"
        default: "Buenas noches"
        }
        VStack(alignment: .leading, spacing: 2) {
            Text(store.userName.isEmpty ? "Hola 👋" : "Hola, \(store.userName)")
                .font(.largeTitle.bold())
            Text(moment).foregroundStyle(.secondary)
        }
        .padding(.horizontal)
        .padding(.top, 12)
    }
}

/// La tarjeta grande de arriba: portada de fondo, título y un botón para escuchar.
private struct Hero: View {
    @EnvironmentObject private var store: Store
    @Environment(\.openRoute) private var open
    let item: MediaItem
    let shelves: [Shelf]
    let slug: String
    @State private var image: UIImage?

    var body: some View {
        let (cover, title, subtitle) = details
        Button(action: go) {
            ZStack(alignment: .bottomLeading) {
                Color(.secondarySystemBackground)
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                }
                LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .center, endPoint: .bottom)
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Te recomendamos")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.white.opacity(0.8))
                        Text(title)
                            .font(.title2.bold())
                            .foregroundStyle(.white)
                            .lineLimit(2)
                        if !subtitle.isEmpty {
                            Text(subtitle).foregroundStyle(.white.opacity(0.8)).lineLimit(1)
                        }
                    }
                    Spacer()
                    Image(systemName: "play.fill")
                        .font(.title2)
                        .foregroundStyle(.black)
                        .frame(width: 52, height: 52)
                        .background(.white, in: Circle())
                }
                .padding(16)
            }
            .frame(height: 210)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(.plain)
        .padding(.horizontal)
        .task(id: cover) {
            guard let cover else { return }
            image = ImagePipeline.shared.cached(cover)
            if image == nil { image = await ImagePipeline.shared.image(cover) }
        }
    }

    private var details: (String?, String, String) {
        switch item {
        case .album(let album): (album.coverLarge ?? album.cover, album.title, album.artists)
        case .playlist(let playlist): (playlist.cover, playlist.title, playlist.owner)
        case .track(let track): (track.cover, track.title, track.artists)
        default: (nil, "", "")
        }
    }

    private func go() {
        switch item {
        case .album(let album): open(.album(slug: slug, id: album.id))
        case .playlist(let playlist): open(.playlist(slug: slug, id: playlist.id))
        case .track(let track):
            let tracks = shelves.flatMap(\.items).compactMap { item -> TrackItem? in
                if case .track(let track) = item { return track }
                return nil
            }
            store.play(tracks, at: tracks.firstIndex(where: { $0.id == track.id }) ?? 0, from: slug)
        default: break
        }
    }
}

struct ShelfView: View {
    @EnvironmentObject private var store: Store
    let shelf: Shelf
    let slug: String
    var size: CGFloat = 140

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(shelf.title)
                .font(.title3.bold())
                .padding(.horizontal)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(Array(shelf.items.enumerated()), id: \.offset) { index, item in
                        MediaCard(item: item, slug: slug, size: size) { play(from: index) }
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    /// Reproduce las canciones del estante, empezando por la que se tocó.
    private func play(from index: Int) {
        let tracks = shelf.items.compactMap { item -> TrackItem? in
            if case .track(let track) = item { return track }
            return nil
        }
        guard case .track(let tapped) = shelf.items[index],
              let start = tracks.firstIndex(where: { $0.id == tapped.id })
        else { return }
        store.play(tracks, at: start, from: slug)
    }
}

// MARK: - Biblioteca

enum LibrarySection: String, CaseIterable, Identifiable {
    case playlists = "Playlists"
    case tracks = "Canciones"
    case albums = "Álbumes"
    case artists = "Artistas"

    var id: String { rawValue }
}

struct LibraryView: View {
    @EnvironmentObject private var store: Store
    @State private var section = LibrarySection.playlists
    @State private var creating = false
    @State private var newName = ""

    var body: some View {
        RoutedStack {
            Group {
                if store.signedIn.isEmpty {
                    NeedsAccount()
                } else {
                    VStack(spacing: 8) {
                        ProviderPicker().padding(.horizontal)
                        Picker("Sección", selection: $section) {
                            ForEach(LibrarySection.allCases) { Text($0.rawValue).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        .padding(.horizontal)
                        LibrarySectionView(section: section, slug: store.selected)
                    }
                }
            }
            .navigationTitle("Biblioteca")
            .toolbar {
                if !store.signedIn.isEmpty {
                    ToolbarItem(placement: .primaryAction) {
                        Button {
                            newName = ""
                            creating = true
                        } label: {
                            Image(systemName: "plus")
                        }
                    }
                }
            }
            .alert("Nueva playlist", isPresented: $creating) {
                TextField("Nombre", text: $newName)
                Button("Crear") { store.createPlaylist(newName, in: store.selected) }
                Button("Cancelar", role: .cancel) {}
            }
            .withPlayerBar()
        }
    }
}

struct LibrarySectionView: View {
    @EnvironmentObject private var store: Store
    let section: LibrarySection
    let slug: String

    var body: some View {
        switch section {
        case .playlists:
            Loader(key: "playlists-\(slug)-\(store.libraryRevision)", load: { try $0.libraryPlaylists(slug: slug) }) { playlists in
                List(playlists, id: \.id) { PlaylistRow(playlist: $0, slug: slug) }
                    .listStyle(.plain)
                    .overlay { if playlists.isEmpty { Empty() } }
            }
        case .tracks:
            Loader(key: "tracks-\(slug)", load: { try $0.libraryTracks(slug: slug) }) { tracks in
                List {
                    if !tracks.isEmpty {
                        PlayButtons(tracks: tracks, slug: slug)
                    }
                    TrackList(tracks: tracks, slug: slug)
                }
                .listStyle(.plain)
                .overlay { if tracks.isEmpty { Empty() } }
            }
        case .albums:
            Loader(key: "albums-\(slug)", load: { try $0.libraryAlbums(slug: slug) }) { albums in
                List(albums, id: \.id) { AlbumRow(album: $0, slug: slug) }
                    .listStyle(.plain)
                    .overlay { if albums.isEmpty { Empty() } }
            }
        case .artists:
            Loader(key: "artists-\(slug)", load: { try $0.libraryArtists(slug: slug) }) { artists in
                List(artists, id: \.id) { ArtistRow(artist: $0, slug: slug) }
                    .listStyle(.plain)
                    .overlay { if artists.isEmpty { Empty() } }
            }
        }
    }

    private struct Empty: View {
        var body: some View {
            Text("Nada guardado aquí todavía.").foregroundStyle(.secondary)
        }
    }
}

/// "Reproducir" y "Aleatorio" para una lista de canciones.
struct PlayButtons: View {
    @EnvironmentObject private var store: Store
    let tracks: [TrackItem]
    let slug: String

    var body: some View {
        HStack(spacing: 12) {
            Button {
                store.play(tracks, at: 0, from: slug)
            } label: {
                Label("Reproducir", systemImage: "play.fill").frame(maxWidth: .infinity)
            }
            Button {
                store.play(tracks.shuffled(), at: 0, from: slug)
            } label: {
                Label("Aleatorio", systemImage: "shuffle").frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(.bordered)
        .listRowSeparator(.hidden)
        .disabled(tracks.isEmpty)
    }
}

// MARK: - Páginas

struct AlbumView: View {
    @EnvironmentObject private var store: Store
    let slug: String
    let id: String

    var body: some View {
        Loader(key: id, load: { try $0.album(slug: slug, id: id) }) { page in
            List {
                VStack(spacing: 8) {
                    Cover(url: page.album.coverLarge, size: 220)
                        .shadow(radius: 8, y: 4)
                    Text(page.album.title)
                        .font(.title3.bold())
                        .multilineTextAlignment(.center)
                    if let artist = page.album.artistId {
                        NavigationLink(value: Route.artist(slug: slug, id: artist)) {
                            Text(page.album.artists).foregroundStyle(Color.accentColor)
                        }
                        .buttonStyle(.plain)
                    } else {
                        Text(page.album.artists).foregroundStyle(.secondary)
                    }
                    Text(page.album.year > 0 ? "\(page.album.kind) · \(page.album.year)" : page.album.kind)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .listRowSeparator(.hidden)
                PlayButtons(tracks: page.tracks, slug: slug)
                TrackList(tracks: page.tracks, slug: slug, numbered: true, covers: false)
                if !page.label.isEmpty || !page.releaseDate.isEmpty {
                    Text([page.releaseDate, page.label].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .listRowSeparator(.hidden)
                }
            }
            .listStyle(.plain)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        store.save(.album, id: id, from: slug)
                    } label: {
                        Image(systemName: "heart")
                    }
                }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .withPlayerBar()
    }
}

struct PlaylistView: View {
    let slug: String
    let id: String

    var body: some View {
        Loader(key: id, load: { try $0.playlist(slug: slug, id: id) }) { page in
            PlaylistBody(page: page, slug: slug)
        }
        .navigationBarTitleDisplayMode(.inline)
        .withPlayerBar()
    }
}

struct PlaylistBody: View {
    @EnvironmentObject private var store: Store
    let page: PlaylistPage
    let slug: String
    @State private var tracks: [TrackItem]
    @State private var continuation: String?
    @State private var loading = false
    @State private var title: String
    @State private var renaming = false
    @State private var newName = ""
    @State private var confirmDelete = false
    @Environment(\.dismiss) private var dismiss

    init(page: PlaylistPage, slug: String) {
        self.page = page
        self.slug = slug
        _tracks = State(initialValue: page.tracks)
        _continuation = State(initialValue: page.continuation)
        _title = State(initialValue: page.playlist.title)
    }

    var body: some View {
        List {
            VStack(spacing: 8) {
                Cover(url: page.playlist.cover, size: 220)
                    .shadow(radius: 8, y: 4)
                Text(title)
                    .font(.title3.bold())
                    .multilineTextAlignment(.center)
                Text(page.playlist.owner).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
            .listRowSeparator(.hidden)
            PlayButtons(tracks: tracks, slug: slug)
            TrackList(tracks: tracks, slug: slug, onRemove: removal)
            if let continuation {
                Button(loading ? "Cargando…" : "Cargar más") { more(continuation) }
                    .disabled(loading)
                    .frame(maxWidth: .infinity)
            }
        }
        .listStyle(.plain)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                if page.playlist.owned {
                    Menu {
                        Button {
                            newName = title
                            renaming = true
                        } label: {
                            Label("Renombrar", systemImage: "pencil")
                        }
                        Button(role: .destructive) {
                            confirmDelete = true
                        } label: {
                            Label("Eliminar playlist", systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                } else {
                    Button {
                        store.save(.playlist, id: page.playlist.id, from: slug)
                    } label: {
                        Image(systemName: "heart")
                    }
                }
            }
        }
        .alert("Renombrar playlist", isPresented: $renaming) {
            TextField("Nombre", text: $newName)
            Button("Guardar") {
                let name = newName
                Task {
                    if await store.renamePlaylist(page.playlist.id, to: name, in: slug) { title = name }
                }
            }
            Button("Cancelar", role: .cancel) {}
        }
        .confirmationDialog("¿Eliminar «\(title)»?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Eliminar", role: .destructive) {
                Task {
                    if await store.deletePlaylist(page.playlist.id, in: slug) { dismiss() }
                }
            }
        } message: {
            Text("Se borra también en el servicio.")
        }
    }

    /// Solo las playlists propias dejan quitar canciones.
    private var removal: ((Int) -> Void)? {
        guard page.playlist.owned else { return nil }
        return { index in remove(index) }
    }

    private func remove(_ index: Int) {
        guard tracks.indices.contains(index) else { return }
        let track = tracks[index]
        tracks.remove(at: index)
        Task {
            if !(await store.removeFromPlaylist(page.playlist.id, track: track.id, in: slug)) {
                tracks.insert(track, at: min(index, tracks.count))
            }
        }
    }

    private func more(_ next: String) {
        loading = true
        let slug = self.slug
        Task {
            switch await store.run({ try $0.playlistMore(slug: slug, continuation: next) }) {
            case .success(let page):
                tracks += page.tracks
                continuation = page.continuation
            case .failure(let error):
                store.problem = describe(error)
            }
            loading = false
        }
    }
}

struct ArtistView: View {
    @EnvironmentObject private var store: Store
    let slug: String
    let id: String
    @State private var biography = false

    var body: some View {
        Loader(key: id, load: { try $0.artist(slug: slug, id: id) }) { page in
            List {
                VStack(spacing: 8) {
                    Cover(url: page.cover, size: 180, round: true)
                        .shadow(radius: 8, y: 4)
                    Text(page.name).font(.title2.bold()).multilineTextAlignment(.center)
                    if let listeners = page.monthlyListeners {
                        Text("\(listeners.formatted()) oyentes mensuales")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    HStack(spacing: 12) {
                        Button {
                            playAll()
                        } label: {
                            Label("Reproducir", systemImage: "play.fill").frame(maxWidth: .infinity)
                        }
                        Button {
                            store.save(.artist, id: id, from: slug)
                        } label: {
                            Label("Seguir", systemImage: "plus").frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.bordered)
                }
                .frame(maxWidth: .infinity)
                .listRowSeparator(.hidden)

                if !page.topTracks.isEmpty {
                    Section("Populares") {
                        TrackList(tracks: Array(page.topTracks.prefix(10)), slug: slug)
                    }
                }
                AlbumShelf(title: "Álbumes", albums: page.albums, slug: slug)
                AlbumShelf(title: "Sencillos y EP", albums: page.singles, slug: slug)
                AlbumShelf(title: "Aparece en", albums: page.appearsOn, slug: slug)
                if let text = page.biography, !text.isEmpty {
                    Section("Biografía") {
                        Text(text)
                            .font(.callout)
                            .lineLimit(biography ? nil : 4)
                            .onTapGesture { biography.toggle() }
                    }
                }
            }
            .listStyle(.plain)
        }
        .navigationBarTitleDisplayMode(.inline)
        .withPlayerBar()
    }

    private func playAll() {
        let slug = self.slug
        let id = self.id
        Task {
            switch await store.run({ try $0.artistTracks(slug: slug, id: id) }) {
            case .success(let tracks) where !tracks.isEmpty:
                store.play(tracks, at: 0, from: slug)
            case .success:
                store.problem = "Este artista no tiene canciones para reproducir."
            case .failure(let error):
                store.problem = describe(error)
            }
        }
    }
}
