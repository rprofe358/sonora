import SwiftUI

extension TrackItem: Identifiable {}

// MARK: - Buscar

struct SearchView: View {
    @EnvironmentObject private var store: Store
    @State private var query = ""
    @State private var results: [TrackItem] = []
    @State private var searchedIn = ""
    @State private var searching = false

    var body: some View {
        NavigationStack {
            List {
                if store.signedIn.isEmpty {
                    Section {
                        Text("Primero entra en la pestaña **Cuentas** y elige YouTube Music (se puede sin cuenta) o Spotify.")
                            .foregroundStyle(.secondary)
                    }
                } else if store.signedIn.count > 1 {
                    Picker("Servicio", selection: $store.selected) {
                        ForEach(store.signedIn, id: \.slug) { provider in
                            Text(provider.name).tag(provider.slug)
                        }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                }
                ForEach(Array(results.enumerated()), id: \.offset) { index, track in
                    Button {
                        store.play(results, at: index, from: searchedIn)
                    } label: {
                        TrackRow(
                            track: track,
                            current: store.state.provider == searchedIn && store.state.track?.id == track.id
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(!track.playable)
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
            results = await store.search(text, in: slug)
            searchedIn = slug
            searching = false
        }
    }
}

struct TrackRow: View {
    let track: TrackItem
    let current: Bool

    var body: some View {
        HStack(spacing: 12) {
            Cover(url: track.cover, size: 48)
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

    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { image in
            image.resizable().scaledToFill()
        } placeholder: {
            ZStack {
                Color.secondary.opacity(0.15)
                Image(systemName: "music.note").foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.12))
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
                    Text(track.artists).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 8)
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

            HStack {
                Image(systemName: "speaker.fill").foregroundStyle(.secondary)
                Slider(value: $volume, in: 0...1) { editing in
                    if !editing { store.engine.setVolume(level: Float(volume)) }
                }
                Image(systemName: "speaker.wave.3.fill").foregroundStyle(.secondary)
            }
            .padding(.horizontal, 24)

            Text("Canción \(state.index + 1) de \(state.queueLength) · \(providerName(state.provider))")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .onAppear { volume = Double(state.volume) }
    }

    private func providerName(_ slug: String?) -> String {
        store.providers.first(where: { $0.slug == slug })?.name ?? ""
    }
}
