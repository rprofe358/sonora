import SwiftUI

@main
struct SonoraMobileApp: App {
    @StateObject private var store = Store()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
        }
    }
}

struct ContentView: View {
    @EnvironmentObject private var store: Store

    var body: some View {
        TabView {
            SearchView()
                .tabItem { Label("Buscar", systemImage: "magnifyingglass") }
            AccountsView()
                .tabItem { Label("Cuentas", systemImage: "person.crop.circle") }
        }
        .sheet(item: $store.prompt, onDismiss: store.promptDismissed) { pending in
            PromptView(pending: pending)
                .environmentObject(store)
        }
        .alert(
            "Aviso",
            isPresented: Binding(
                get: { store.problem != nil },
                set: { if !$0 { store.problem = nil } }
            )
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(store.problem ?? "")
        }
    }
}
