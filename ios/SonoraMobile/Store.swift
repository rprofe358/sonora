import Foundation
import SwiftUI

/// Un aviso del motor que espera respuesta del usuario durante el inicio de sesión.
struct PendingPrompt: Identifiable {
    let id = UUID()
    let slug: String
    let prompt: Prompt
}

/// Recibe los avisos del motor, que llegan desde hilos de Rust, y los pasa al hilo principal.
final class Bridge: EngineListener, @unchecked Sendable {
    weak var store: Store?

    func onPrompt(slug: String, prompt: Prompt) {
        Task { @MainActor [weak self] in self?.store?.prompted(slug: slug, prompt: prompt) }
    }

    func onState(state: PlaybackState) {
        Task { @MainActor [weak self] in self?.store?.apply(state) }
    }

    func onProblem(message: String) {
        Task { @MainActor [weak self] in
            self?.store?.problem = message
            self?.store?.sessionBusy = false
        }
    }

    func onSession(session: SessionInfo?) {
        Task { @MainActor [weak self] in self?.store?.sessionChanged(session) }
    }
}

/// El estado de la app: cuentas, reproducción y avisos. Todas las llamadas que bloquean al motor
/// van fuera del hilo principal.
@MainActor
final class Store: ObservableObject {
    let engine: Engine
    private let bridge: Bridge
    private let nowPlaying = NowPlaying()
    private let minter = PoTokenMinter()

    @Published var providers: [ProviderInfo] = []
    @Published var state: PlaybackState
    @Published var prompt: PendingPrompt?
    @Published var problem: String?
    @Published var signingIn: String?
    /// La Sonora Session en la que está la app.
    @Published var session: SessionInfo?
    /// Se está creando o uniendo a una sesión.
    @Published var sessionBusy = false
    @Published var audio: AudioSettings
    /// Canciones que esperan elegir a qué playlist van.
    @Published var addingToPlaylist: PendingAdd?
    /// Sube cada vez que cambian las playlists, para recargar la biblioteca.
    @Published var libraryRevision = 0
    /// El proveedor en el que se busca.
    @Published var selected: String = "youtube"

    init() {
        let bridge = Bridge()
        self.bridge = bridge
        engine = Engine(listener: bridge)
        state = engine.state()
        audio = engine.audioSettings()
        bridge.store = self
        if let saved = Self.savedAudio() {
            engine.setAudioSettings(settings: saved)
            audio = saved
        }
        nowPlaying.attach(engine: engine)
        minter.start()
        refresh()
        restoreStored()
        if UserDefaults.standard.bool(forKey: "local.on") { openLocal(quiet: true) }
    }

    var signedIn: [ProviderInfo] { providers.filter(\.signedIn) }

    func refresh() {
        providers = engine.providers()
        if !signedIn.contains(where: { $0.slug == selected }), let first = signedIn.first {
            selected = first.slug
        }
    }

    func account(_ slug: String) -> Account? { engine.account(slug: slug) }

    // MARK: Canciones del iPhone

    @Published var localBusy = false

    /// La carpeta de Sonora en Archivos («En mi iPhone › Sonora»): ahí va la música.
    static var musicFolder: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// Busca (o vuelve a buscar) las canciones de la carpeta de Sonora.
    func openLocal(quiet: Bool = false) {
        localBusy = true
        let engine = self.engine
        let folder = Self.musicFolder.path
        Task {
            let result = await Task.detached { Result(catching: { try engine.openLocal(folders: [folder]) }) }.value
            switch result {
            case .success:
                UserDefaults.standard.set(true, forKey: "local.on")
            case .failure(let error):
                if !quiet { problem = describe(error) }
            }
            localBusy = false
            libraryRevision += 1
            refresh()
        }
    }

    func closeLocal() {
        UserDefaults.standard.set(false, forKey: "local.on")
        signOut("local")
    }

    /// Copia a la carpeta de Sonora los archivos elegidos en Archivos y vuelve a buscar.
    func importSongs(_ urls: [URL]) {
        let folder = Self.musicFolder
        var failed = 0
        for url in urls {
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            let target = folder.appendingPathComponent(url.lastPathComponent)
            do {
                if FileManager.default.fileExists(atPath: target.path) {
                    try FileManager.default.removeItem(at: target)
                }
                try FileManager.default.copyItem(at: url, to: target)
            } catch {
                failed += 1
            }
        }
        if failed > 0 { problem = "No pude copiar \(failed) de los archivos." }
        openLocal()
    }

    /// Recupera las sesiones guardadas al abrir la app.
    private func restoreStored() {
        let engine = self.engine
        for provider in providers where provider.stored {
            let slug = provider.slug
            let name = provider.name
            Task.detached { [weak self] in
                let result = Result(catching: { try engine.restore(slug: slug) })
                await MainActor.run {
                    guard let self else { return }
                    if case .failure(let error) = result {
                        self.problem = "\(name): \(describe(error))"
                    }
                    self.refresh()
                }
            }
        }
    }

    func signIn(_ provider: ProviderInfo, method: SignInMethod) {
        signingIn = provider.slug
        let engine = self.engine
        let slug = provider.slug
        Task.detached { [weak self] in
            let result = Result(catching: { try engine.signIn(slug: slug, method: method) })
            await MainActor.run {
                guard let self else { return }
                self.signingIn = nil
                self.prompt = nil
                if case .failure(let error) = result {
                    self.problem = describe(error)
                } else {
                    self.selected = slug
                }
                self.refresh()
            }
        }
    }

    func cancelSignIn() {
        engine.cancelSignIn()
        prompt = nil
    }

    /// Si el usuario cerró la hoja del inicio de sesión a mano, se abandona.
    func promptDismissed() {
        if prompt == nil, signingIn != nil {
            engine.cancelSignIn()
        }
    }

    func answer(_ text: String) {
        engine.sendInput(text: text)
    }

    func signOut(_ slug: String) {
        engine.signOut(slug: slug)
        refresh()
    }

    func search(_ query: String, in slug: String) async -> [TrackItem] {
        let engine = self.engine
        let result = await Task.detached {
            Result(catching: { try engine.search(slug: slug, query: query) })
        }.value
        switch result {
        case .success(let tracks):
            return tracks
        case .failure(let error):
            problem = describe(error)
            return []
        }
    }

    func play(_ tracks: [TrackItem], at index: Int, from slug: String) {
        let engine = self.engine
        Task.detached { [weak self] in
            let result = Result(catching: {
                try engine.playQueue(slug: slug, tracks: tracks, index: UInt32(index))
            })
            if case .failure(let error) = result {
                await MainActor.run { self?.problem = describe(error) }
            }
        }
    }

    /// Corre algo que bloquea al motor fuera del hilo principal.
    func run<T>(_ work: @escaping (Engine) throws -> T) async -> Result<T, Error> {
        let engine = self.engine
        return await Task.detached { Result(catching: { try work(engine) }) }.value
    }

    func enqueue(_ tracks: [TrackItem], next: Bool, from slug: String) {
        Task {
            if case .failure(let error) = await run({ try $0.enqueue(slug: slug, tracks: tracks, next: next) }) {
                problem = describe(error)
            }
        }
    }

    func startRadio(_ track: TrackItem, from slug: String) {
        Task {
            switch await run({ try $0.radio(slug: slug, trackId: track.id) }) {
            case .success(let tracks):
                var queue = tracks
                if !queue.contains(where: { $0.id == track.id }) { queue.insert(track, at: 0) }
                play(queue, at: 0, from: slug)
            case .failure(let error):
                problem = describe(error)
            }
        }
    }

    func save(_ kind: SavedKind, id: String, from slug: String, saved: Bool = true, quiet: Bool = false) {
        Task {
            switch await run({ try $0.setSaved(slug: slug, kind: kind, id: id, saved: saved) }) {
            case .success:
                if !quiet { problem = saved ? "Guardado en tu biblioteca." : "Quitado de tu biblioteca." }
            case .failure(let error):
                problem = describe(error)
            }
        }
    }

    // MARK: Ajustes de audio

    private static let audioKey = "audio-settings"

    private static func savedAudio() -> AudioSettings? {
        guard let saved = UserDefaults.standard.dictionary(forKey: audioKey) else { return nil }
        let quality: AudioQuality
        switch saved["quality"] as? String {
        case "low": quality = .low
        case "normal": quality = .normal
        case "lossless": quality = .lossless
        case "hires": quality = .hiRes
        default: quality = .high
        }
        return AudioSettings(
            quality: quality,
            normalisation: saved["normalisation"] as? Bool ?? true,
            gapless: saved["gapless"] as? Bool ?? true,
            equalizer: saved["equalizer"] as? Bool ?? false,
            bands: saved["bands"] as? [Double] ?? Array(repeating: 0, count: 10)
        )
    }

    func applyAudio(_ settings: AudioSettings) {
        audio = settings
        let quality: String
        switch settings.quality {
        case .low: quality = "low"
        case .normal: quality = "normal"
        case .high: quality = "high"
        case .lossless: quality = "lossless"
        case .hiRes: quality = "hires"
        }
        UserDefaults.standard.set([
            "quality": quality,
            "normalisation": settings.normalisation,
            "gapless": settings.gapless,
            "equalizer": settings.equalizer,
            "bands": settings.bands,
        ] as [String: Any], forKey: Self.audioKey)
        let engine = self.engine
        Task.detached { engine.setAudioSettings(settings: settings) }
    }

    // MARK: Playlists

    func createPlaylist(_ name: String, in slug: String) {
        let name = name.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        Task {
            switch await run({ try $0.createPlaylist(slug: slug, name: name) }) {
            case .success: libraryRevision += 1
            case .failure(let error): problem = describe(error)
            }
        }
    }

    func renamePlaylist(_ id: String, to name: String, in slug: String) async -> Bool {
        let name = name.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return false }
        switch await run({ try $0.renamePlaylist(slug: slug, id: id, name: name) }) {
        case .success:
            libraryRevision += 1
            return true
        case .failure(let error):
            problem = describe(error)
            return false
        }
    }

    func deletePlaylist(_ id: String, in slug: String) async -> Bool {
        switch await run({ try $0.deletePlaylist(slug: slug, id: id) }) {
        case .success:
            libraryRevision += 1
            return true
        case .failure(let error):
            problem = describe(error)
            return false
        }
    }

    func addToPlaylist(_ playlist: PlaylistItem, adding: PendingAdd) {
        addingToPlaylist = nil
        let ids = adding.tracks.map(\.id)
        let slug = adding.slug
        Task {
            switch await run({ try $0.addToPlaylist(slug: slug, playlist: playlist.id, tracks: ids) }) {
            case .success:
                problem = "Agregada a «\(playlist.title)»."
                libraryRevision += 1
            case .failure(let error):
                problem = describe(error)
            }
        }
    }

    func removeFromPlaylist(_ playlist: String, track: String, in slug: String) async -> Bool {
        switch await run({ try $0.removeFromPlaylist(slug: slug, playlist: playlist, track: track) }) {
        case .success: return true
        case .failure(let error):
            problem = describe(error)
            return false
        }
    }

    // MARK: Scrobbling

    /// Vincula un servicio de scrobbling; Last.fm y Libre.fm abren la aprobación en Safari.
    func linkScrobble(_ id: String, key: String, secret: String, token: String, server: String) async -> Bool {
        let result = await run({
            try $0.scrobbleConnect(id: id, key: key, secret: secret, token: token, server: server)
        })
        prompt = nil
        switch result {
        case .success: return true
        case .failure(let error):
            problem = describe(error)
            return false
        }
    }

    func startSession(from slug: String? = nil) {
        sessionBusy = true
        let slug = slug ?? selected
        Task {
            if case .failure(let error) = await run({ try $0.sessionStart(slug: slug) }) {
                problem = describe(error)
                sessionBusy = false
            }
        }
    }

    func joinSession(_ code: String, remote: Bool) {
        sessionBusy = true
        Task {
            if case .failure(let error) = await run({ try $0.sessionJoin(code: code, remote: remote) }) {
                problem = describe(error)
                sessionBusy = false
            }
        }
    }

    func sessionChanged(_ info: SessionInfo?) {
        session = info
        if info != nil { sessionBusy = false }
    }

    func prompted(slug: String, prompt: Prompt) {
        self.prompt = PendingPrompt(slug: slug, prompt: prompt)
    }

    func apply(_ state: PlaybackState) {
        self.state = state
        nowPlaying.update(state)
    }
}

/// Canciones que esperan elegir a qué playlist van.
struct PendingAdd: Identifiable {
    let id = UUID()
    let slug: String
    let tracks: [TrackItem]
}

/// El mensaje de un error del motor, sin el nombre del tipo delante.
func describe(_ error: Error) -> String {
    if error is MobileError {
        let payload = Mirror(reflecting: error).children.first?.value
        if let message = payload as? String {
            return message
        }
        if let payload, let message = Mirror(reflecting: payload).children.first?.value as? String {
            return message
        }
    }
    return String(describing: error)
}

/// `1:05` para 65 000 ms.
func clock(_ milliseconds: UInt64) -> String {
    let seconds = Int(milliseconds / 1000)
    return String(format: "%d:%02d", seconds / 60, seconds % 60)
}
