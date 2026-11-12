import SwiftUI

/// Lo que se ve en el centro del reproductor en vez de la portada.
private enum PlayerPanel {
    case lyrics, queue, session
}

/// El reproductor a pantalla completa, sobre la portada desenfocada. En el centro se turnan la
/// portada, la letra, la cola y la sesión; se cierra con la flecha o deslizando hacia abajo.
struct PlayerView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openRoute) private var open
    @State private var panel: PlayerPanel?
    @State private var scrub: Double?
    @State private var volume: Double = 1
    @State private var adding = false
    @State private var playlist: PendingAdd?
    @State private var drag: CGFloat = 0
    /// La portada es clara: texto e iconos casi negros en vez de blancos.
    @State private var light = false
    /// Lo que se tocó en el corazón, mientras el servicio lo confirma.

    private var fg: Color { light ? Color(white: 0.08) : .white }

    var body: some View {
        let state = store.state
        GeometryReader { geometry in
            if geometry.size.width > geometry.size.height {
                landscape(state, height: geometry.size.height)
            } else {
                portrait(state)
            }
        }
        .background { Backdrop(url: state.track?.cover, light: $light) }
        .offset(y: drag)
        .foregroundStyle(fg)
        .environment(\.colorScheme, light ? .light : .dark)
        .tint(fg)
        .animation(.easeInOut(duration: 0.5), value: light)
        .onChange(of: state.track == nil) { gone in
            if gone { dismiss() }
        }
        .sheet(isPresented: $adding) {
            AddSongsView(slug: state.provider ?? store.selected).environmentObject(store)
        }
        .sheet(item: $playlist) { adding in
            PlaylistPicker(adding: adding).environmentObject(store)
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

    // MARK: Partes

    private func portrait(_ state: PlaybackState) -> some View {
        VStack(spacing: 14) {
            header(state)
                .gesture(dismissDrag)
            center(state)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            if let track = state.track {
                info(track, slug: state.provider ?? store.selected)
            }
            VisualizerView(color: fg).frame(height: 32)
            seek(state)
            controls(state)
            remoteNote(state)
            toggles
        }
        .padding(.horizontal, 22)
        .padding(.bottom, 8)
    }

    /// De lado: portada y controles a la izquierda, letra (o cola, o sesión) a la derecha.
    private func landscape(_ state: PlaybackState, height: CGFloat) -> some View {
        HStack(spacing: 24) {
            ScrollView {
                VStack(spacing: 10) {
                    Cover(
                        url: state.coverLarge ?? state.track?.cover,
                        size: max(120, height * 0.45),
                        fallback: state.track?.cover
                    )
                    .shadow(color: .black.opacity(0.4), radius: 16, y: 8)
                    if let track = state.track {
                        info(track, slug: state.provider ?? store.selected)
                    }
                    VisualizerView(color: fg).frame(height: 24)
                    seek(state)
                    controls(state)
                    remoteNote(state)
                }
            }
            .scrollIndicators(.hidden)
            .frame(maxWidth: .infinity)
            VStack(spacing: 8) {
                header(state)
                    .gesture(dismissDrag)
                Group {
                    switch panel ?? .lyrics {
                    case .queue: QueuePanel { adding = true }
                    case .session: SessionList(slug: state.provider).scrollContentBackground(.hidden)
                    default: LyricsPanel()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                toggles
            }
            .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 16)
    }

    private func header(_ state: PlaybackState) -> some View {
        HStack {
            Button { dismiss() } label: {
                Image(systemName: "chevron.down")
                    .font(.title3.weight(.semibold))
                    .frame(width: 44, height: 44)
            }
            Spacer()
            Text(providerName(state.provider).map { "Reproduciendo desde \($0)" } ?? "")
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            menu(state)
        }
        .foregroundStyle(fg)
        .contentShape(Rectangle())
    }

    private func menu(_ state: PlaybackState) -> some View {
        let slug = state.provider ?? store.selected
        return Menu {
            Button { adding = true } label: {
                Label("Agregar canciones a la cola", systemImage: "plus")
            }
            Button(role: .destructive) { store.engine.clearQueue() } label: {
                Label("Limpiar la cola", systemImage: "xmark.bin")
            }
            if let track = state.track {
                Button { playlist = PendingAdd(slug: slug, tracks: [track]) } label: {
                    Label("Agregar a una playlist…", systemImage: "text.badge.plus")
                }
                if !state.remote, let album = track.albumId {
                    Button {
                        dismiss()
                        open(.album(slug: slug, id: album))
                    } label: {
                        Label("Ir al álbum", systemImage: "square.stack")
                    }
                }
                if !state.remote, let artist = track.artistId {
                    Button {
                        dismiss()
                        open(.artist(slug: slug, id: artist))
                    } label: {
                        Label("Ir al artista", systemImage: "music.mic")
                    }
                }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
                .font(.title3)
                .frame(width: 44, height: 44)
        }
    }

    @ViewBuilder
    private func center(_ state: PlaybackState) -> some View {
        switch panel {
        case nil:
            GeometryReader { geometry in
                Cover(
                    url: state.coverLarge ?? state.track?.cover,
                    size: min(geometry.size.width, geometry.size.height),
                    fallback: state.track?.cover
                )
                    .shadow(color: .black.opacity(0.4), radius: 24, y: 12)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .gesture(dismissDrag)
        case .lyrics:
            LyricsPanel()
        case .queue:
            QueuePanel { adding = true }
        case .session:
            SessionList(slug: state.provider)
                .scrollContentBackground(.hidden)
        }
    }

    private func info(_ track: TrackItem, slug: String) -> some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(.title3.weight(.bold))
                    .lineLimit(2)
                Text(track.artists)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            HeartButton(track: track, slug: slug)
        }
        .foregroundStyle(fg)
    }

    private func seek(_ state: PlaybackState) -> some View {
        VStack(spacing: 4) {
            Slider(
                value: Binding(
                    get: { scrub ?? Double(state.positionMs) },
                    set: { scrub = $0 }
                ),
                in: 0...max(1, Double(state.durationMs))
            ) { editing in
                if !editing, let target = scrub {
                    store.engine.seek(positionMs: UInt64(target))
                    scrub = nil
                }
            }
            HStack {
                Text(clock(UInt64(scrub ?? Double(state.positionMs))))
                Spacer()
                Text(clock(state.durationMs))
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
        }
    }

    private func controls(_ state: PlaybackState) -> some View {
        HStack(spacing: 44) {
            Button { store.engine.previous() } label: {
                Image(systemName: "backward.fill").font(.system(size: 30))
            }
            PlayButton(status: state.status, size: 44) { store.engine.toggle() }
            Button { store.engine.next() } label: {
                Image(systemName: "forward.fill").font(.system(size: 30))
            }
            .disabled(state.index + 1 >= state.queueLength)
        }
        .buttonStyle(.plain)
        .foregroundStyle(fg)
    }

    /// En «solo controlar» se avisa de que la música suena en otro lado. El volumen va con los
    /// botones del iPhone.
    @ViewBuilder
    private func remoteNote(_ state: PlaybackState) -> some View {
        if state.remote {
            Label("Controlas la sesión: esto suena en los otros dispositivos.", systemImage: "slider.horizontal.3")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    /// Letra, cola y sesión. Volver a tocar el activo muestra la portada otra vez.
    private var toggles: some View {
        HStack(spacing: 6) {
            toggle(.lyrics, icon: "quote.bubble", label: "Letra")
            toggle(.queue, icon: "list.bullet", label: "Cola")
            toggle(.session, icon: "person.2.wave.2", label: "Sesión")
        }
        .padding(4)
        .background(fg.opacity(0.08), in: Capsule())
    }

    private func toggle(_ which: PlayerPanel, icon: String, label: String) -> some View {
        let on = panel == which
        return Button {
            withAnimation(.easeInOut(duration: 0.2)) { panel = on ? nil : which }
        } label: {
            Image(systemName: icon)
                .font(.body.weight(.semibold))
                .frame(width: 52, height: 36)
                .background(on ? fg.opacity(0.2) : .clear, in: Capsule())
                .foregroundStyle(on ? fg : fg.opacity(0.6))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    /// Deslizar hacia abajo cierra el reproductor.
    private var dismissDrag: some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { value in drag = max(0, value.translation.height) }
            .onEnded { value in
                if value.translation.height > 140 || value.predictedEndTranslation.height > 320 {
                    dismiss()
                } else {
                    withAnimation(.spring()) { drag = 0 }
                }
            }
    }

    private func providerName(_ slug: String?) -> String? {
        store.providers.first(where: { $0.slug == slug })?.name
    }
}

/// El corazón: lleno si la canción está entre las guardadas. Con su propio estado por canción,
/// para que el cambio se vea al momento mientras el servicio lo confirma.
struct HeartButton: View {
    @EnvironmentObject private var store: Store
    let track: TrackItem
    let slug: String
    @State private var mine: Bool?

    var body: some View {
        // el motor avisa con un estado nuevo cuando termina de cargar las guardadas
        let saved = mine ?? store.engine.trackSaved(slug: slug, id: track.id)
        Button {
            let next = !saved
            mine = next
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            store.save(.track, id: track.id, from: slug, saved: next, quiet: true)
        } label: {
            Image(systemName: saved ? "heart.fill" : "heart")
                .font(.title2)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(saved ? "Quitar de tu biblioteca" : "Guardar en tu biblioteca")
        .id(track.id)
    }
}

/// Barras que siguen la música (32 bandas, de graves a agudos), leídas del motor unas 30 veces
/// por segundo mientras se ven. Al salir, el motor deja de analizar.
struct VisualizerView: View {
    @EnvironmentObject private var store: Store
    let color: Color
    @State private var bands: [Float] = []

    var body: some View {
        Canvas { context, size in
            let count = bands.count
            guard count > 0 else { return }
            let gap: CGFloat = 3
            let width = max(1, (size.width - gap * CGFloat(count - 1)) / CGFloat(count))
            for (index, level) in bands.enumerated() {
                let value = CGFloat(min(max(level, 0), 1))
                let height = max(2, value * size.height)
                let rect = CGRect(
                    x: CGFloat(index) * (width + gap),
                    y: size.height - height,
                    width: width,
                    height: height
                )
                context.fill(
                    Path(roundedRect: rect, cornerRadius: width / 2),
                    with: .color(color.opacity(0.35 + 0.5 * value))
                )
            }
        }
        .task {
            let engine = store.engine
            while !Task.isCancelled {
                bands = await Task.detached { engine.spectrum() }.value
                try? await Task.sleep(nanoseconds: 33_000_000)
            }
            engine.spectrumStop()
        }
        .accessibilityHidden(true)
    }
}

/// La portada, muy desenfocada y con un velo, de fondo. Avisa si es clara, para poner encima
/// texto oscuro.
struct Backdrop: View {
    let url: String?
    @Binding var light: Bool
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color(white: 0.08)
            if let image {
                Color.clear.overlay {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .blur(radius: 80)
                        .scaleEffect(1.5)
                }
                .clipped()
                .transition(.opacity)
            }
            LinearGradient(
                colors: light
                    ? [.white.opacity(0.2), .white.opacity(0.4)]
                    : [.black.opacity(0.3), .black.opacity(0.6)],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.4), value: image)
        .task(id: url) {
            image = url.flatMap { ImagePipeline.shared.cached($0) }
            if image == nil, let url {
                image = await ImagePipeline.shared.image(url)
            }
            light = (image?.averageLuminance ?? 0) > 0.6
        }
    }
}

extension UIImage {
    /// El brillo medio de la imagen (0 negro, 1 blanco).
    var averageLuminance: CGFloat? {
        guard let source = cgImage else { return nil }
        var pixel = [UInt8](repeating: 0, count: 4)
        let drawn: Bool = pixel.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress,
                width: 1,
                height: 1,
                bitsPerComponent: 8,
                bytesPerRow: 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            context.interpolationQuality = .medium
            context.draw(source, in: CGRect(x: 0, y: 0, width: 1, height: 1))
            return true
        }
        guard drawn else { return nil }
        let red = CGFloat(pixel[0]) / 255
        let green = CGFloat(pixel[1]) / 255
        let blue = CGFloat(pixel[2]) / 255
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }
}

// MARK: - Cola

struct QueuePanel: View {
    @EnvironmentObject private var store: Store
    let onAdd: () -> Void

    var body: some View {
        let queue = store.engine.queue()
        let current = Int(store.state.index)
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Cola").font(.title3.bold())
                Spacer()
                Button(action: onAdd) { Label("Agregar", systemImage: "plus") }
                Button { store.engine.clearQueue() } label: { Label("Limpiar", systemImage: "xmark.bin") }
            }
            .buttonStyle(.bordered)
            .font(.subheadline)
            if queue.count <= current + 1 {
                Text("No hay nada después de esta canción. Toca «Agregar» para sumar más.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ScrollViewReader { proxy in
                List {
                    ForEach(Array(queue.enumerated()), id: \.offset) { index, track in
                        Button {
                            store.engine.jump(index: UInt32(index))
                        } label: {
                            TrackRow(track: track, current: index == current)
                        }
                        .buttonStyle(.plain)
                        .opacity(index < current ? 0.5 : 1)
                        .deleteDisabled(index == current)
                        .listRowBackground(index == current ? Color.primary.opacity(0.12) : Color.clear)
                    }
                    .onDelete { offsets in
                        for index in offsets.sorted(by: >) {
                            store.engine.removeFromQueue(index: UInt32(index))
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .onAppear { proxy.scrollTo(max(0, current - 1), anchor: .top) }
            }
        }
    }
}

/// Buscar canciones y sumarlas a la cola sin salir del reproductor.
struct AddSongsView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.dismiss) private var dismiss
    let slug: String
    @State private var query = ""
    @State private var results: [TrackItem] = []
    @State private var searching = false
    @State private var added: Set<String> = []

    var body: some View {
        NavigationStack {
            List(results, id: \.id) { track in
                HStack(spacing: 10) {
                    Cover(url: track.cover, size: 44)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(track.title).lineLimit(1)
                        Text(track.artists).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    if added.contains(track.id) {
                        Image(systemName: "checkmark").foregroundStyle(.secondary)
                    } else {
                        Button {
                            store.enqueue([track], next: true, from: slug)
                            added.insert(track.id)
                        } label: {
                            Image(systemName: "text.line.first.and.arrowtriangle.forward").font(.title3)
                        }
                        .accessibilityLabel("Reproducir a continuación")
                        Button {
                            store.enqueue([track], next: false, from: slug)
                            added.insert(track.id)
                        } label: {
                            Image(systemName: "plus.circle").font(.title3)
                        }
                        .accessibilityLabel("Agregar al final")
                    }
                }
                .buttonStyle(.borderless)
            }
            .listStyle(.plain)
            .overlay {
                if searching { ProgressView() }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Buscar canciones")
            .onSubmit(of: .search, run)
            .navigationTitle("Agregar a la cola")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Listo") { dismiss() }
                }
            }
        }
    }

    private func run() {
        let text = query.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        searching = true
        Task {
            results = await store.search(text, in: slug)
            searching = false
        }
    }
}
