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
            HomeView()
                .tabItem { Label("Inicio", systemImage: "house") }
            SearchView()
                .tabItem { Label("Buscar", systemImage: "magnifyingglass") }
            LibraryView()
                .tabItem { Label("Biblioteca", systemImage: "books.vertical") }
            SessionView()
                .tabItem { Label("Sesión", systemImage: "person.2.wave.2") }
            AccountsView()
                .tabItem { Label("Ajustes", systemImage: "gearshape") }
        }
        .sheet(item: $store.addingToPlaylist) { adding in
            PlaylistPicker(adding: adding).environmentObject(store)
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
