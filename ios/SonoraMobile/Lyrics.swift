import SwiftUI

/// La letra de lo que suena, dentro del reproductor.
struct LyricsPanel: View {
    @EnvironmentObject private var store: Store

    var body: some View {
        let state = store.state
        if let track = state.track, let slug = state.provider {
            Loader(key: "\(slug):\(track.id)", load: { try $0.lyricsFor(slug: slug, track: track) }) { result in
                LyricsBody(result: result)
            }
        } else {
            Text("No suena nada.").foregroundStyle(.secondary)
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
                            .font(.system(size: 20, weight: .semibold))
                            .lineSpacing(6)
                        Text("Letra: \(result.source)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 24)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .scrollIndicators(.hidden)
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

/// Como en el escritorio: la línea que suena, nítida y grande, a un tercio de la altura; las
/// demás, más pequeñas, tenues y desenfocadas. Tocar una línea salta a ese momento.
struct SyncedLyrics: View {
    @EnvironmentObject private var store: Store
    let result: LyricsResult

    var body: some View {
        let active = activeLine(at: store.state.positionMs)
        GeometryReader { geometry in
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        Color.clear.frame(height: geometry.size.height * 0.3)
                        ForEach(Array(result.lines.enumerated()), id: \.offset) { index, line in
                            LyricLine(
                                text: line.text,
                                current: index == active,
                                distance: active.map { abs(index - $0) } ?? 99
                            )
                            .id(index)
                            .onTapGesture { store.engine.seek(positionMs: line.startMs) }
                        }
                        Text("Letra: \(result.source)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.top, 16)
                        Color.clear.frame(height: geometry.size.height * 0.6)
                    }
                    .animation(.easeOut(duration: 0.35), value: active)
                }
                .scrollIndicators(.hidden)
                .onChange(of: active) { line in
                    guard let line else { return }
                    withAnimation(.easeInOut(duration: 0.45)) {
                        proxy.scrollTo(line, anchor: UnitPoint(x: 0.5, y: 0.3))
                    }
                }
                .onAppear {
                    if let active { proxy.scrollTo(active, anchor: UnitPoint(x: 0.5, y: 0.3)) }
                }
            }
        }
    }

    private func activeLine(at position: UInt64) -> Int? {
        result.lines.lastIndex(where: { $0.startMs <= position })
    }
}

private struct LyricLine: View {
    let text: String
    let current: Bool
    let distance: Int

    var body: some View {
        Text(text.isEmpty ? "♪" : text)
            .font(.system(size: current ? 30 : 22, weight: .bold))
            .foregroundStyle(.white.opacity(current ? 1 : (distance <= 1 ? 0.5 : 0.35)))
            .blur(radius: current ? 0 : (distance <= 1 ? 1.5 : 3))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
    }
}
