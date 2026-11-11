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
        Task { @MainActor [weak self] in self?.store?.problem = message }
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
    /// El proveedor en el que se busca.
    @Published var selected: String = "youtube"

    init() {
        let bridge = Bridge()
        self.bridge = bridge
        engine = Engine(listener: bridge)
        state = engine.state()
        bridge.store = self
        nowPlaying.attach(engine: engine)
        minter.start()
        refresh()
        restoreStored()
    }

    var signedIn: [ProviderInfo] { providers.filter(\.signedIn) }

    func refresh() {
        providers = engine.providers()
        if !signedIn.contains(where: { $0.slug == selected }), let first = signedIn.first {
            selected = first.slug
        }
    }

    func account(_ slug: String) -> Account? { engine.account(slug: slug) }

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

    func save(_ kind: SavedKind, id: String, from slug: String, saved: Bool = true) {
        Task {
            switch await run({ try $0.setSaved(slug: slug, kind: kind, id: id, saved: saved) }) {
            case .success:
                problem = saved ? "Guardado en tu biblioteca." : "Quitado de tu biblioteca."
            case .failure(let error):
                problem = describe(error)
            }
        }
    }

    func prompted(slug: String, prompt: Prompt) {
        self.prompt = PendingPrompt(slug: slug, prompt: prompt)
    }

    func apply(_ state: PlaybackState) {
        self.state = state
        nowPlaying.update(state)
    }
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
