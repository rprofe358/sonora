import SwiftUI

extension TrackItem: Identifiable {}

// MARK: - Buscar

struct SearchView: View {
    @EnvironmentObject private var store: Store
    @State private var query = ""
    @State private var results: SearchResults?
    @State private var searchedIn = ""
    @State private var searching = false

    var body: some View {
        RoutedStack {
            List {
                if store.signedIn.isEmpty {
                    NeedsAccount()
                } else {
                    ProviderPicker().listRowBackground(Color.clear)
                }
                if let results {
                    if !results.artists.isEmpty {
                        Section("Artistas") {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(alignment: .top, spacing: 12) {
                                    ForEach(results.artists, id: \.id) { artist in
                                        MediaCard(item: .artist(artist: artist), slug: searchedIn) {}
                                    }
                                }
                            }
                        }
                    }
                    if !results.tracks.isEmpty {
                        Section("Canciones") {
                            TrackList(tracks: results.tracks, slug: searchedIn)
                        }
                    }
                    if !results.albums.isEmpty {
                        Section("Álbumes") {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(alignment: .top, spacing: 12) {
                                    ForEach(results.albums, id: \.id) { album in
                                        MediaCard(item: .album(album: album), slug: searchedIn) {}
                                    }
                                }
                            }
                        }
                    }
                    if !results.playlists.isEmpty {
                        Section("Playlists") {
                            ForEach(results.playlists, id: \.id) { playlist in
                                PlaylistRow(playlist: playlist, slug: searchedIn)
                            }
                        }
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if searching { ProgressView() }
            }
            .navigationTitle("Buscar")
            .searchable(text: $query, prompt: "Canciones, artistas, álbumes")
            .onSubmit(of: .search, run)
            .withPlayerBar()
        }
    }

    private func run() {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !store.signedIn.isEmpty else { return }
        let slug = store.selected
        searching = true
        Task {
            let found = await store.run { try $0.searchAll(slug: slug, query: text) }
            switch found {
            case .success(let value):
                results = value
                searchedIn = slug
            case .failure(let error):
                store.problem = describe(error)
            }
            searching = false
        }
    }
}

/// Una lista de canciones que se reproduce desde la que se toque.
struct TrackList: View {
    @EnvironmentObject private var store: Store
    let tracks: [TrackItem]
    let slug: String
    var numbered = false
    var covers = true

    var body: some View {
        ForEach(Array(tracks.enumerated()), id: \.offset) { index, track in
            Button {
                store.play(tracks, at: index, from: slug)
            } label: {
                TrackRow(
                    track: track,
                    current: store.state.provider == slug && store.state.track?.id == track.id,
                    number: numbered ? index + 1 : nil,
                    cover: covers
                )
            }
            .buttonStyle(.plain)
            .disabled(!track.playable)
            .trackMenu(track, slug: slug)
        }
    }
}

struct TrackRow: View {
    let track: TrackItem
    let current: Bool
    var number: Int? = nil
    var cover = true

    var body: some View {
        HStack(spacing: 12) {
            if let number {
                Text("\(number)")
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(minWidth: 24)
            }
            if cover {
                Cover(url: track.cover, size: 48)
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(track.title)
                        .font(.body)
                        .foregroundStyle(current ? Color.accentColor : .primary)
                        .lineLimit(1)
                    if track.explicit {
                        Image(systemName: "e.square.fill")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Text(track.artists.isEmpty ? track.album : track.artists)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(clock(track.durationMs))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
        .opacity(track.playable ? 1 : 0.4)
    }
}

struct Cover: View {
    let url: String?
    let size: CGFloat
    var round = false
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Color.secondary.opacity(0.15)
                Image(systemName: round ? "music.mic" : "music.note").foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: round ? size / 2 : size * 0.08))
        .task(id: url) {
            image = url.flatMap { ImagePipeline.shared.cached($0) }
            guard image == nil, let url else { return }
            image = await ImagePipeline.shared.image(url)
        }
    }
}

/// Descarga y guarda en memoria las portadas. A diferencia de `AsyncImage`, una descarga que
/// empezó termina aunque la fila salga de la pantalla, y se reintenta si falla.
/// Las portadas ya descargadas. NSCache se puede leer desde cualquier hilo.
private let coverCache: NSCache<NSString, UIImage> = {
    let cache = NSCache<NSString, UIImage>()
    cache.countLimit = 400
    return cache
}()

actor ImagePipeline {
    static let shared = ImagePipeline()

    private var running: [String: Task<UIImage?, Never>] = [:]

    nonisolated func cached(_ url: String) -> UIImage? {
        coverCache.object(forKey: url as NSString)
    }

    func image(_ url: String) async -> UIImage? {
        if let hit = cached(url) { return hit }
        if let task = running[url] { return await task.value }
        let task = Task<UIImage?, Never> { await Self.download(url) }
        running[url] = task
        let image = await task.value
        running[url] = nil
        if let image { coverCache.setObject(image, forKey: url as NSString) }
        return image
    }

    private static func download(_ url: String) async -> UIImage? {
        guard let address = normalized(url) else { return nil }
        for attempt in 0..<3 {
            if let (data, response) = try? await URLSession.shared.data(from: address) {
                if let image = UIImage(data: data) {
                    return image.preparingForDisplay() ?? image
                }
                if let http = response as? HTTPURLResponse, (400..<500).contains(http.statusCode) {
                    return nil
                }
            }
            try? await Task.sleep(nanoseconds: UInt64(attempt + 1) * 500_000_000)
        }
        return nil
    }

    /// iOS solo deja cargar por https, y algunos servicios dan direcciones sin esquema o http.
    private static func normalized(_ url: String) -> URL? {
        var text = url.trimmingCharacters(in: .whitespaces)
        if text.hasPrefix("//") { text = "https:" + text }
        if text.hasPrefix("http://") { text = "https://" + text.dropFirst("http://".count) }
        return URL(string: text)
    }
}

// MARK: - Cuentas

struct AccountsView: View {
    @EnvironmentObject private var store: Store
    @State private var outputs: [String] = []

    var body: some View {
        NavigationStack {
            List {
                ForEach(store.providers, id: \.slug) { provider in
                    Section {
                        rows(for: provider)
                    } header: {
                        Text(provider.name)
                    } footer: {
                        if provider.slug == "spotify" {
                            Text("Spotify necesita una cuenta Premium para reproducir.")
                        } else if provider.slug == "youtube" {
                            Text("Sin cuenta puedes buscar y escuchar; con tu cuenta de Google ves tu biblioteca (fase 2).")
                        }
                    }
                }
                Section("Diagnóstico") {
                    LabeledContent("Motor Sonora", value: engineVersion())
                    ForEach(outputs, id: \.self) { output in
                        Label(output, systemImage: "hifispeaker")
                    }
                }
            }
            .navigationTitle("Cuentas")
            .task { outputs = await Task.detached { audioOutputs() }.value }
            .withPlayerBar()
        }
    }

    @ViewBuilder
    private func rows(for provider: ProviderInfo) -> some View {
        if provider.signedIn, let account = store.account(provider.slug) {
            Label(
                account.name.isEmpty ? (account.authenticated ? "Sesión iniciada" : "Invitado") : account.name,
                systemImage: account.authenticated ? "person.crop.circle.fill.badge.checkmark" : "person.crop.circle"
            )
            Button("Cerrar sesión", role: .destructive) { store.signOut(provider.slug) }
        } else if store.signingIn == provider.slug {
            HStack(spacing: 12) {
                ProgressView()
                Text("Iniciando sesión…")
            }
            Button("Cancelar", role: .cancel) { store.cancelSignIn() }
        } else {
            ForEach(provider.methods, id: \.self) { method in
                Button(label(method, provider)) { store.signIn(provider, method: method) }
                    .disabled(store.signingIn != nil)
            }
        }
    }

    private func label(_ method: SignInMethod, _ provider: ProviderInfo) -> String {
        switch method {
        case .anonymous: return "Usar sin cuenta (invitado)"
        case .secret: return "Iniciar sesión con Google"
        default: return "Iniciar sesión en \(provider.name)"
        }
    }
}

// MARK: - Reproductor

extension View {
    /// Muestra el mini reproductor abajo mientras haya algo en la cola.
    func withPlayerBar() -> some View { modifier(PlayerBarModifier()) }
}

struct PlayerBarModifier: ViewModifier {
    @EnvironmentObject private var store: Store
    @State private var open = false

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .bottom) {
                if let track = store.state.track {
                    PlayerBar(track: track, state: store.state) { open = true }
                }
            }
            .sheet(isPresented: $open) {
                PlayerView().environmentObject(store)
            }
    }
}

struct PlayerBar: View {
    @EnvironmentObject private var store: Store
    let track: TrackItem
    let state: PlaybackState
    let onOpen: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ProgressView(value: progress)
                .progressViewStyle(.linear)
                .scaleEffect(x: 1, y: 0.5, anchor: .center)
            HStack(spacing: 12) {
                Cover(url: track.cover, size: 40)
                VStack(alignment: .leading, spacing: 1) {
                    Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(state.remote ? "En la sesión · \(track.artists)" : track.artists)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Button { store.engine.previous() } label: {
                    Image(systemName: "backward.fill").font(.system(size: 20))
                }
                .buttonStyle(.plain)
                PlayButton(status: state.status, size: 22) { store.engine.toggle() }
                Button { store.engine.next() } label: {
                    Image(systemName: "forward.fill").font(.system(size: 20))
                }
                .buttonStyle(.plain)
                .disabled(state.index + 1 >= state.queueLength)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
        .background(.bar)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
    }

    private var progress: Double {
        guard state.durationMs > 0 else { return 0 }
        return min(1, Double(state.positionMs) / Double(state.durationMs))
    }
}

struct PlayButton: View {
    let status: Status
    let size: CGFloat
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Group {
                if status == .loading {
                    ProgressView()
                } else {
                    Image(systemName: status == .playing ? "pause.fill" : "play.fill")
                        .font(.system(size: size))
                }
            }
            .frame(width: size * 1.6, height: size * 1.6)
        }
        .buttonStyle(.plain)
    }
}

struct PlayerView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var scrub: Double?
    @State private var volume: Double = 1
    @State private var showQueue = false
    @State private var showLyrics = false

    var body: some View {
        let state = store.state
        VStack(spacing: 24) {
            Capsule().fill(.secondary.opacity(0.4)).frame(width: 40, height: 5).padding(.top, 8)
            Spacer(minLength: 0)
            Cover(url: state.track?.cover, size: 300)
                .shadow(radius: 12, y: 6)
            VStack(spacing: 4) {
                Text(state.track?.title ?? "")
                    .font(.title3.weight(.semibold))
                    .multilineTextAlignment(.center)
                Text(state.track?.artists ?? "")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal)

            VStack(spacing: 4) {
                Slider(
                    value: Binding(
                        get: { scrub ?? Double(state.positionMs) },
                        set: { scrub = $0 }
                    ),
                    in: 0...max(1, Double(state.durationMs))
                ) { editing in
                    if !editing, let target = scrub {
                        store.engine.seek(positionMs: UInt64(target))
                        scrub = nil
                    }
                }
                HStack {
                    Text(clock(UInt64(scrub ?? Double(state.positionMs))))
                    Spacer()
                    Text(clock(state.durationMs))
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 24)

            HStack(spacing: 44) {
                Button { store.engine.previous() } label: {
                    Image(systemName: "backward.fill").font(.system(size: 30))
                }
                PlayButton(status: state.status, size: 44) { store.engine.toggle() }
                Button { store.engine.next() } label: {
                    Image(systemName: "forward.fill").font(.system(size: 30))
                }
                .disabled(state.index + 1 >= state.queueLength)
            }
            .buttonStyle(.plain)

            if state.remote {
                Label("Controlas la sesión: esto suena en los otros dispositivos.", systemImage: "slider.horizontal.3")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 24)
            } else {
                HStack {
                    Image(systemName: "speaker.fill").foregroundStyle(.secondary)
                    Slider(value: $volume, in: 0...1) { editing in
                        if !editing { store.engine.setVolume(level: Float(volume)) }
                    }
                    Image(systemName: "speaker.wave.3.fill").foregroundStyle(.secondary)
                }
                .padding(.horizontal, 24)
            }

            HStack(spacing: 28) {
                Button {
                    showLyrics = true
                } label: {
                    Label("Letra", systemImage: "quote.bubble")
                }
                Button {
                    showQueue = true
                } label: {
                    Label("Cola · \(state.index + 1) de \(state.queueLength)", systemImage: "list.bullet")
                }
            }
            .font(.footnote)
            Text(providerName(state.provider))
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .onAppear { volume = Double(state.volume) }
        .sheet(isPresented: $showQueue) {
            QueueView().environmentObject(store)
        }
        .sheet(isPresented: $showLyrics) {
            LyricsView().environmentObject(store)
        }
    }

    private func providerName(_ slug: String?) -> String {
        store.providers.first(where: { $0.slug == slug })?.name ?? ""
    }
}
