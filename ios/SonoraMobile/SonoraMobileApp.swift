import SwiftUI

@main
struct SonoraMobileApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

/// Fase 0: una pantalla de diagnóstico que llama al motor de Sonora (Rust) desde Swift.
struct ContentView: View {
    @State private var providerList: [String] = []
    @State private var outputs: [String] = []
    @State private var title = "Bohemian Rhapsody"
    @State private var artist = "Queen"
    @State private var status = ""
    @State private var lines: [String] = []

    var body: some View {
        NavigationStack {
            List {
                Section("Motor") {
                    LabeledContent("Versión de Sonora", value: engineVersion())
                    ForEach(providerList, id: \.self) { Text($0) }
                }

                Section("Salidas de audio") {
                    if outputs.isEmpty {
                        Text("Buscando…").foregroundStyle(.secondary)
                    }
                    ForEach(outputs, id: \.self) { Text($0) }
                }

                Section("Prueba de red: letras en LRCLIB") {
                    TextField("Canción", text: $title)
                    TextField("Artista", text: $artist)
                    Button("Buscar letra", action: search)
                    if !status.isEmpty {
                        Text(status).foregroundStyle(.secondary)
                    }
                    ForEach(Array(lines.enumerated()), id: \.offset) { item in
                        Text(item.element).font(.callout)
                    }
                }
            }
            .navigationTitle("Sonora · Fase 0")
            .task {
                providerList = await Task.detached { providers() }.value
                outputs = await Task.detached { audioOutputs() }.value
            }
        }
    }

    private func search() {
        status = "Buscando…"
        lines = []
        let song = title
        let singer = artist
        Task {
            let result = await Task.detached { () -> Result<[String], Error> in
                Result { try lyrics(title: song, artist: singer) }
            }.value
            switch result {
            case .success(let found):
                lines = found
                status = found.isEmpty ? "Sin resultados" : "Listo"
            case .failure(let error):
                status = "Error: \(error)"
            }
        }
    }
}
