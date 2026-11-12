import SwiftUI

// MARK: - Audio

struct AudioSettingsView: View {
    @EnvironmentObject private var store: Store
    @State private var presets: [EqualizerPreset] = []

    private static let frequencies = ["31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"]

    var body: some View {
        List {
            Section {
                Picker("Calidad", selection: binding(\.quality)) {
                    Text("Baja").tag(AudioQuality.low)
                    Text("Normal").tag(AudioQuality.normal)
                    Text("Alta").tag(AudioQuality.high)
                    Text("Sin pérdida").tag(AudioQuality.lossless)
                    Text("Hi-Res").tag(AudioQuality.hiRes)
                }
                Toggle("Igualar el volumen entre canciones", isOn: binding(\.normalisation))
                Toggle("Pasar sin silencios entre canciones", isOn: binding(\.gapless))
            } header: {
                Text("Reproducción")
            } footer: {
                Text("Cada servicio da la calidad más cercana que tenga a la elegida. Cambiar estos ajustes reinicia la canción donde iba.")
            }

            Section {
                Toggle("Ecualizador", isOn: binding(\.equalizer))
                Menu {
                    ForEach(presets, id: \.id) { preset in
                        Button(preset.name) {
                            var settings = store.audio
                            settings.bands = preset.gains
                            settings.equalizer = true
                            store.applyAudio(settings)
                        }
                    }
                } label: {
                    LabeledContent("Ajuste predefinido", value: currentPreset)
                }
                ForEach(0..<10, id: \.self) { band in
                    HStack {
                        Text(Self.frequencies[band])
                            .font(.caption.monospacedDigit())
                            .frame(width: 34, alignment: .leading)
                        Slider(value: bandBinding(band), in: -12...12, step: 0.5) { editing in
                            if !editing { store.applyAudio(store.audio) }
                        }
                        Text(String(format: "%+.1f", gain(band)))
                            .font(.caption.monospacedDigit())
                            .frame(width: 40, alignment: .trailing)
                    }
                    .disabled(!store.audio.equalizer)
                }
            } header: {
                Text("Ecualizador")
            } footer: {
                Text("Diez bandas en decibelios, de graves (izquierda arriba) a agudos.")
            }
        }
        .navigationTitle("Audio")
        .task { presets = store.engine.equalizerPresets() }
    }

    private var currentPreset: String {
        presets.first(where: { matches($0.gains, store.audio.bands) })?.name ?? "Personalizado"
    }

    private func matches(_ a: [Double], _ b: [Double]) -> Bool {
        a.count == b.count && zip(a, b).allSatisfy { abs($0 - $1) < 0.1 }
    }

    private func gain(_ band: Int) -> Double {
        store.audio.bands.indices.contains(band) ? store.audio.bands[band] : 0
    }

    /// Un ajuste que se guarda y se aplica al cambiarlo.
    private func binding<Value>(_ path: WritableKeyPath<AudioSettings, Value>) -> Binding<Value> {
        Binding(
            get: { store.audio[keyPath: path] },
            set: { value in
                var settings = store.audio
                settings[keyPath: path] = value
                store.applyAudio(settings)
            }
        )
    }

    /// Mientras se arrastra solo cambia lo que se ve; se aplica al soltar.
    private func bandBinding(_ band: Int) -> Binding<Double> {
        Binding(
            get: { gain(band) },
            set: { value in
                var bands = store.audio.bands
                while bands.count < 10 { bands.append(0) }
                bands[band] = value
                store.audio.bands = bands
            }
        )
    }
}

// MARK: - Scrobbling

struct ScrobbleView: View {
    @EnvironmentObject private var store: Store
    @State private var services: [ScrobbleService] = []

    var body: some View {
        List {
            Section {
                ForEach(services, id: \.id) { service in
                    NavigationLink {
                        ScrobbleServiceView(service: service) { reload() }
                    } label: {
                        HStack {
                            Text(service.name)
                            Spacer()
                            if service.linked {
                                Text(service.enabled ? (service.accountName.isEmpty ? "Vinculado" : service.accountName) : "Pausado")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            } footer: {
                Text("Una canción cuenta al escucharla la mitad o cuatro minutos, y nunca si dura menos de treinta segundos. En modo «Solo controlar» anota el PC, no el iPhone.")
            }
        }
        .navigationTitle("Scrobbling")
        .onAppear(perform: reload)
    }

    private func reload() {
        services = store.engine.scrobbleServices()
    }
}

struct ScrobbleServiceView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let service: ScrobbleService
    let onChange: () -> Void
    @State private var key = ""
    @State private var secret = ""
    @State private var token = ""
    @State private var server = ""
    @State private var enabled = true
    @State private var linking = false

    var body: some View {
        List {
            if service.linked {
                Section {
                    LabeledContent("Cuenta", value: service.accountName.isEmpty ? "Vinculada" : service.accountName)
                    Toggle("Anotar lo que escucho", isOn: $enabled)
                        .onChange(of: enabled) { value in
                            store.engine.scrobbleSetEnabled(id: service.id, enabled: value)
                            onChange()
                        }
                }
                Section {
                    Button("Desvincular", role: .destructive) {
                        store.engine.scrobbleDisconnect(id: service.id)
                        onChange()
                        dismiss()
                    }
                }
            } else {
                Section {
                    switch service.link {
                    case .browser:
                        Text("Pulsa «Vincular» y aprueba el acceso en la página que se abre.")
                    case .keys:
                        TextField("API key", text: $key)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        SecureField("Secreto compartido", text: $secret)
                        Text("Después de pulsar «Vincular» se abre Last.fm para aprobar el acceso.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    case .token:
                        SecureField("Token de usuario", text: $token)
                    case .server:
                        TextField("Dirección del servidor", text: $server)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                        SecureField("API key del servidor", text: $key)
                    }
                    if let signup = service.signup, let url = URL(string: signup) {
                        Link(service.link == .keys ? "Crear una API key en Last.fm" : "Dónde consigo esto", destination: url)
                    }
                }
                Section {
                    Button {
                        link()
                    } label: {
                        HStack {
                            Text(linking ? "Vinculando…" : "Vincular")
                            if linking { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(linking || !ready)
                }
            }
        }
        .navigationTitle(service.name)
        .onAppear { enabled = service.enabled }
    }

    private var ready: Bool {
        switch service.link {
        case .browser: return true
        case .keys: return !key.isEmpty && !secret.isEmpty
        case .token: return !token.isEmpty
        case .server: return !server.isEmpty && !key.isEmpty
        }
    }

    private func link() {
        linking = true
        Task {
            let done = await store.linkScrobble(service.id, key: key, secret: secret, token: token, server: server)
            linking = false
            onChange()
            if done { dismiss() }
        }
    }
}

// MARK: - Agregar a una playlist

struct PlaylistPicker: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let adding: PendingAdd

    var body: some View {
        NavigationStack {
            Loader(key: "picker-\(adding.slug)-\(store.libraryRevision)", load: { try $0.libraryPlaylists(slug: adding.slug) }) { playlists in
                let own = playlists.filter(\.owned)
                List {
                    if own.isEmpty {
                        Text("No tienes playlists propias en este servicio. Crea una en Biblioteca → +.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(own, id: \.id) { playlist in
                        Button {
                            store.addToPlaylist(playlist, adding: adding)
                        } label: {
                            HStack(spacing: 12) {
                                Cover(url: playlist.cover, size: 44)
                                Text(playlist.title).foregroundStyle(.primary)
                            }
                        }
                    }
                }
                .listStyle(.plain)
            }
            .navigationTitle("Agregar a una playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") { dismiss() }
                }
            }
        }
    }
}
