import SwiftUI

/// Sonora Session: escuchar lo mismo a la vez que otras apps de Sonora, como la del PC, o
/// controlarlas desde aquí sin que suene el iPhone.
struct SessionView: View {
    @EnvironmentObject private var store: Store
    @State private var code = ""
    @State private var remote = false

    var body: some View {
        RoutedStack {
            List {
                if let session = store.session {
                    inSession(session)
                } else {
                    outside
                }
            }
            .navigationTitle("Sesión")
            .withPlayerBar()
        }
    }

    @ViewBuilder
    private var outside: some View {
        Section {
            Text("Escucha lo mismo a la vez que otras apps de Sonora, como la del PC. Una persona inicia la sesión y comparte su código de 6 dígitos.")
                .foregroundStyle(.secondary)
        }
        Section {
            TextField("Código de 6 dígitos", text: $code)
                .keyboardType(.numberPad)
                .font(.title2.monospacedDigit())
            Toggle("Solo controlar (no suena en este iPhone)", isOn: $remote)
            Button("Unirse") { store.joinSession(code, remote: remote) }
                .disabled(code.filter(\.isNumber).count != 6 || store.sessionBusy)
        } header: {
            Text("Unirse con un código")
        } footer: {
            Text("Con «Solo controlar» el iPhone es un control remoto: lo que pongas, pauses o adelantes aquí suena en el PC.")
        }
        Section {
            Button("Iniciar una sesión en \(providerName(store.selected))") { store.startSession() }
                .disabled(store.signedIn.isEmpty || store.sessionBusy)
        } header: {
            Text("Iniciar")
        } footer: {
            Text("La sesión suena desde ese servicio; los demás necesitan haber iniciado sesión en él.")
        }
        if store.sessionBusy {
            HStack(spacing: 12) {
                ProgressView()
                Text("Conectando…")
            }
        }
    }

    @ViewBuilder
    private func inSession(_ session: SessionInfo) -> some View {
        Section {
            VStack(spacing: 8) {
                Text("Código").font(.caption).foregroundStyle(.secondary)
                Text(spaced(session.code))
                    .font(.system(size: 44, weight: .bold, design: .monospaced))
                ShareLink(item: "Únete a mi Sonora Session con el código \(session.code)") {
                    Label("Compartir código", systemImage: "square.and.arrow.up")
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            if session.reconnecting {
                Label("Reconectando…", systemImage: "wifi.exclamationmark")
                    .foregroundStyle(.orange)
            }
            LabeledContent("Servicio", value: providerName(session.provider))
            if session.remote {
                Label("Solo controlas: la música suena en los otros dispositivos.", systemImage: "slider.horizontal.3")
                    .foregroundStyle(.secondary)
            }
        }
        Section("Personas (\(session.members.count))") {
            ForEach(session.members, id: \.id) { member in
                HStack(spacing: 12) {
                    Cover(url: member.image, size: 36, round: true)
                    Text(member.you ? "\(member.name) (tú)" : member.name)
                    Spacer()
                    if member.host {
                        Text("Anfitrión").font(.caption).foregroundStyle(.secondary)
                    }
                }
                .swipeActions {
                    if session.hosting && !member.you {
                        Button("Sacar", role: .destructive) {
                            store.engine.sessionKick(member: member.id)
                        }
                    }
                }
            }
        }
        Section {
            Button(session.hosting ? "Terminar la sesión" : "Salir de la sesión", role: .destructive) {
                store.engine.sessionLeave()
            }
        }
    }

    private func providerName(_ slug: String) -> String {
        store.providers.first(where: { $0.slug == slug })?.name ?? slug
    }

    /// `123 456`, más fácil de dictar.
    private func spaced(_ code: String) -> String {
        guard code.count == 6 else { return code }
        return "\(code.prefix(3)) \(code.suffix(3))"
    }
}
