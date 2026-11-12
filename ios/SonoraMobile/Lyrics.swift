import SwiftUI

/// La letra de lo que suena. Si viene sincronizada, resalta la línea actual y la sigue; tocar
/// una línea salta a ese momento.
struct LyricsView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let state = store.state
        NavigationStack {
            Group {
                if let track = state.track, let slug = state.provider {
                    Loader(key: "\(slug):\(track.id)", load: { try $0.lyricsFor(slug: slug, track: track) }) { result in
                        LyricsBody(result: result)
                    }
                } else {
                    Text("No suena nada.").foregroundStyle(.secondary)
                }
            }
            .navigationTitle(state.track?.title ?? "Letra")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Listo") { dismiss() }
                }
            }
        }
    }
}

struct LyricsBody: View {
    let result: LyricsResult?

    var body: some View {
        if let result, !result.lines.isEmpty {
            if result.synced {
                SyncedLyrics(result: result)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(result.lines.map(\.text).joined(separator: "\n"))
                            .font(.title3.weight(.semibold))
                        Text("Letra: \(result.source)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(24)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        } else if result?.instrumental == true {
            Label("Instrumental", systemImage: "music.note")
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            Text("No encontré la letra de esta canción.")
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

struct SyncedLyrics: View {
    @EnvironmentObject private var store: Store
    let result: LyricsResult

    var body: some View {
        let active = activeLine(at: store.state.positionMs)
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    ForEach(Array(result.lines.enumerated()), id: \.offset) { index, line in
                        Text(line.text.isEmpty ? "♪" : line.text)
                            .font(.title2.bold())
                            .foregroundStyle(index == active ? Color.primary : Color.secondary.opacity(0.55))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                            .id(index)
                            .onTapGesture { store.engine.seek(positionMs: line.startMs) }
                    }
                    Text("Letra: \(result.source)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.top, 12)
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 40)
                .animation(.easeOut(duration: 0.2), value: active)
            }
            .onChange(of: active) { line in
                guard let line else { return }
                withAnimation(.easeInOut(duration: 0.35)) {
                    proxy.scrollTo(line, anchor: .center)
                }
            }
            .onAppear {
                if let active { proxy.scrollTo(active, anchor: .center) }
            }
        }
    }

    private func activeLine(at position: UInt64) -> Int? {
        result.lines.lastIndex(where: { $0.startMs <= position })
    }
}
