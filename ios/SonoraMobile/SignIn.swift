import SafariServices
import SwiftUI
import WebKit

/// Lo que se muestra mientras un proveedor pide algo durante el inicio de sesión.
struct PromptView: View {
    @EnvironmentObject private var store: Store
    let pending: PendingPrompt

    var body: some View {
        switch pending.prompt {
        case .openUrl(let url):
            if let address = URL(string: url) {
                // Spotify: se autoriza en Safari y vuelve a un servidor local del propio motor.
                SafariView(url: address) { store.cancelSignIn() }
                    .ignoresSafeArea()
            }
        case .showCode(let code, let url):
            VStack(spacing: 20) {
                Text("Escribe este código").font(.headline)
                Text(code).font(.system(.largeTitle, design: .monospaced).weight(.bold))
                if let address = URL(string: url) {
                    Link("Abrir \(address.host ?? url)", destination: address)
                }
                Button("Cancelar", role: .cancel) { store.cancelSignIn() }
            }
            .padding()
        case .webCookies(let url, _, let domain, let proof, let agent):
            NavigationStack {
                if let address = URL(string: url) {
                    CookieWebView(url: address, domain: domain, proof: proof, agent: agent) { header in
                        store.answer(header)
                    }
                    .ignoresSafeArea(edges: .bottom)
                    .navigationTitle("Iniciar sesión")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancelar") { store.cancelSignIn() }
                        }
                    }
                }
            }
        case .chooseAccount(let options):
            NavigationStack {
                List(options, id: \.id) { option in
                    Button {
                        store.answer(option.id)
                    } label: {
                        VStack(alignment: .leading) {
                            Text(option.name)
                            if let detail = option.detail {
                                Text(detail).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .navigationTitle("Elige la cuenta")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancelar") { store.cancelSignIn() }
                    }
                }
            }
        }
    }
}

struct SafariView: UIViewControllerRepresentable {
    let url: URL
    let onDone: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onDone: onDone) }

    func makeUIViewController(context: Context) -> SFSafariViewController {
        let controller = SFSafariViewController(url: url)
        controller.delegate = context.coordinator
        controller.dismissButtonStyle = .cancel
        return controller
    }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}

    final class Coordinator: NSObject, SFSafariViewControllerDelegate {
        let onDone: () -> Void
        init(onDone: @escaping () -> Void) { self.onDone = onDone }
        func safariViewControllerDidFinish(_ controller: SFSafariViewController) { onDone() }
    }
}

/// Una vista web aislada (no guarda nada al cerrarse) que espera a que el usuario inicie sesión
/// y entonces entrega las cookies del dominio como una cabecera `Cookie`.
struct CookieWebView: UIViewRepresentable {
    let url: URL
    let domain: String
    let proof: [String]
    let agent: String?
    let onCookies: (String) -> Void

    /// Google rechaza el inicio de sesión en vistas web que no se presentan como Safari.
    private static let safari =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.customUserAgent = agent ?? Self.safari
        view.navigationDelegate = context.coordinator
        configuration.websiteDataStore.httpCookieStore.add(context.coordinator)
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKHTTPCookieStoreObserver {
        let parent: CookieWebView
        private var sent = false

        init(parent: CookieWebView) { self.parent = parent }

        func cookiesDidChange(in cookieStore: WKHTTPCookieStore) { check(cookieStore) }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            check(webView.configuration.websiteDataStore.httpCookieStore)
        }

        private func check(_ store: WKHTTPCookieStore) {
            store.getAllCookies { cookies in
                guard !self.sent else { return }
                let matching = cookies.filter { Self.matches($0.domain, self.parent.domain) }
                let proven = matching.contains { cookie in
                    self.parent.proof.contains(cookie.name) && !cookie.value.isEmpty
                }
                guard proven else { return }
                self.sent = true
                let header = matching.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
                self.parent.onCookies(header)
            }
        }

        private static func matches(_ stored: String, _ domain: String) -> Bool {
            let stored = stored.hasPrefix(".") ? String(stored.dropFirst()) : stored
            return stored == domain || stored.hasSuffix("." + domain)
        }
    }
}
