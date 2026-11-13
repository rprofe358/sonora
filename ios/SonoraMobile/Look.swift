import Lottie
import SwiftUI

/// Los temas de color que se eligen en Ajustes › Personalización.
enum Palette: String, CaseIterable, Identifiable {
    case system, tk, sunset, ocean, forest, lavender, rose, gold

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: "Azul del sistema"
        case .tk: "TK (blanco y negro)"
        case .sunset: "Atardecer"
        case .ocean: "Océano"
        case .forest: "Bosque"
        case .lavender: "Lavanda"
        case .rose: "Rosa"
        case .gold: "Oro"
        }
    }

    /// El color de acento. El de TK es el del texto: negro en claro, blanco en oscuro.
    var color: Color {
        switch self {
        case .system: .blue
        case .tk: .primary
        case .sunset: Color(red: 0.91, green: 0.45, blue: 0.17)
        case .ocean: Color(red: 0.12, green: 0.53, blue: 0.90)
        case .forest: Color(red: 0.18, green: 0.49, blue: 0.20)
        case .lavender: Color(red: 0.49, green: 0.34, blue: 0.76)
        case .rose: Color(red: 0.85, green: 0.11, blue: 0.38)
        case .gold: Color(red: 0.79, green: 0.64, blue: 0.15)
        }
    }
}

/// Claro, oscuro o como el iPhone.
enum Appearance: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: "Automático"
        case .light: "Claro"
        case .dark: "Oscuro"
        }
    }

    var scheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

/// La animación de entrada de TK-Music (`splash.json`, hecha en After Effects). Se ve una vez al
/// abrir la app, encima de todo; al terminar (o al tocarla) se desvanece. El logo toma el color
/// del texto, así se ve en claro y en oscuro.
struct SplashView: View {
    @Environment(\.colorScheme) private var scheme
    @Binding var showing: Bool

    var body: some View {
        let ink: UIColor = scheme == .dark ? .white : .black
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            LottieView(animation: .named("splash"))
                .configure { view in
                    view.setValueProvider(ColorValueProvider(ink.lottieColorValue), keypath: AnimationKeypath(keypath: "**.Color"))
                }
                .playing(loopMode: .playOnce)
                .animationDidFinish { _ in hide() }
                .frame(width: 280, height: 280)
        }
        .contentShape(Rectangle())
        .onTapGesture { hide() }
        .task {
            // por si la animación no carga: nunca tapa la app más de unos segundos
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            hide()
        }
    }

    private func hide() {
        withAnimation(.easeOut(duration: 0.35)) { showing = false }
    }
}
