import UIKit
import WebKit

/// Acuña los tokens de origen que YouTube pide para algunos streams, en una vista web oculta,
/// igual que la app de escritorio (`state/src/potoken.rs`). Sin esto YouTube Music solo suena
/// con el token de arranque, que en algunas redes no alcanza para la canción entera.
@MainActor
final class PoTokenMinter: NSObject {
    /// Cuánto tiene una página nueva para pasar la verificación de Google y responder.
    private static let patience: TimeInterval = 45
    /// Cuánto tiene una página ya verificada para responder otra petición.
    private static let answerTime: TimeInterval = 10
    /// Cuánto queda abierta la página tras su última respuesta.
    private static let linger: TimeInterval = 20

    private var timer: Timer?
    private var web: WKWebView?
    private var warm = false
    private var asked: (binding: String, at: Date)?
    private var used = Date()
    private let cookie = potokenCookie()

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    private func tick() {
        if web == nil {
            if let binding = potokenWanted() { open(for: binding) }
        } else if let asked {
            poll(asked)
        } else if Date().timeIntervalSince(used) > Self.linger {
            close()
        } else if let binding = potokenWanted() {
            let data = (try? JSONEncoder().encode(binding)) ?? Data()
            let quoted = String(data: data, encoding: .utf8) ?? "\"\""
            web?.evaluateJavaScript("window.sonoraPoToken && window.sonoraPoToken(\(quoted))")
            self.asked = (binding, Date())
        }
    }

    private func open(for binding: String) {
        guard let url = URL(string: potokenPage(binding: binding)) else {
            potokenFailed()
            return
        }
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        configuration.userContentController.addUserScript(WKUserScript(
            source: potokenScript(), injectionTime: .atDocumentStart, forMainFrameOnly: true
        ))
        let view = WKWebView(frame: CGRect(x: 0, y: 0, width: 2, height: 2), configuration: configuration)
        view.alpha = 0.01
        view.isUserInteractionEnabled = false
        // Dentro de la ventana para que iOS no frene sus temporizadores de JavaScript.
        if let window = UIApplication.shared.connectedScenes
            .compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first {
            window.addSubview(view)
        }
        view.load(URLRequest(url: url))
        web = view
        warm = false
        asked = (binding, Date())
        used = Date()
    }

    private func poll(_ asked: (binding: String, at: Date)) {
        guard let web else { return }
        web.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] cookies in
            Task { @MainActor in
                guard let self, let current = self.asked, current.binding == asked.binding else { return }
                let value = cookies.first(where: { $0.name == self.cookie })?.value
                let reply = value.flatMap { potokenReply(cookie: $0, binding: asked.binding) }
                switch reply {
                case .minted(let token):
                    potokenGive(binding: asked.binding, token: token)
                    self.warm = true
                    self.asked = nil
                    self.used = Date()
                case .failed(let reason):
                    print("potoken: la página no pudo acuñar: \(reason)")
                    self.fail()
                case nil:
                    let patience = self.warm ? Self.answerTime : Self.patience
                    if Date().timeIntervalSince(asked.at) > patience {
                        print("potoken: la página no respondió a tiempo")
                        self.fail()
                    }
                }
            }
        }
    }

    private func fail() {
        close()
        potokenFailed()
    }

    private func close() {
        web?.stopLoading()
        web?.removeFromSuperview()
        web = nil
        asked = nil
        warm = false
        potokenClosed()
    }
}
