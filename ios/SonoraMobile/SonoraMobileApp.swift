import SwiftUI

@main
struct SonoraMobileApp: App {
    @StateObject private var store = Store()
    @State private var splash = true

    var body: some Scene {
        WindowGroup {
            ZStack {
                ContentView()
                if splash {
                    SplashView(showing: $splash)
                        .transition(.opacity)
                        .zIndex(1)
                }
            }
            .environmentObject(store)
            .tint(store.palette.color)
            .preferredColorScheme(store.appearance.scheme)
        }
    }
}

/// Las secciones de la app: pestañas abajo en el iPhone, menú lateral en el iPad.
enum AppSection: String, CaseIterable, Identifiable {
    case home, search, library, session, settings

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: "Inicio"
        case .search: "Buscar"
        case .library: "Biblioteca"
        case .session: "Sesión"
        case .settings: "Ajustes"
        }
    }

    var icon: String {
        switch self {
        case .home: "house"
        case .search: "magnifyingglass"
        case .library: "books.vertical"
        case .session: "person.2.wave.2"
        case .settings: "gearshape"
        }
    }

    @ViewBuilder
    var screen: some View {
        switch self {
        case .home: HomeView()
        case .search: SearchView()
        case .library: LibraryView()
        case .session: SessionView()
        case .settings: AccountsView()
        }
    }
}

struct ContentView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.horizontalSizeClass) private var width
    @State private var section: AppSection = .home

    var body: some View {
        Group {
            if width == .regular {
                sidebar
            } else {
                TabView(selection: $section) {
                    ForEach(AppSection.allCases) { section in
                        section.screen
                            .tabItem { Label(section.title, systemImage: section.icon) }
                            .tag(section)
                    }
                }
            }
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

    /// En el iPad (y en una ventana ancha), un menú lateral fijo en vez de las pestañas.
    private var sidebar: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                Text("TK-Music")
                    .font(.title2.bold())
                    .padding(.horizontal, 12)
                    .padding(.vertical, 16)
                ForEach(AppSection.allCases) { item in
                    Button {
                        section = item
                    } label: {
                        Label(item.title, systemImage: item.icon)
                            .font(.body.weight(section == item ? .semibold : .regular))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .background(
                                section == item ? Color.accentColor.opacity(0.15) : .clear,
                                in: RoundedRectangle(cornerRadius: 10)
                            )
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(section == item ? Color.accentColor : .primary)
                }
                Spacer()
            }
            .padding(.horizontal, 10)
            .frame(width: 220)
            .background(Color(.secondarySystemBackground))
            Divider()
            section.screen
                .id(section)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}
