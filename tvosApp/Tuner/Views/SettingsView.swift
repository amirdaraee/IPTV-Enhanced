import SwiftUI
import TunerCore

struct SettingsView: View {
    @EnvironmentObject private var app: AppModel
    @StateObject private var gate = PinGate()
    @State private var sources: [Source] = []
    @State private var settings: TunerSettings?
    @State private var editing: EditTarget?
    @State private var confirmDelete: Source?
    @State private var sourceMenu: Source?
    @State private var pinStep: PinStep?
    @State private var hiddenGroups: [ChannelGroup]?
    @State private var hiddenChannels: [Channel]?

    struct EditTarget: Identifiable { let id: Int64? ; var key: String { "\(id ?? -1)" } }
    enum PinStep: Identifiable { case enter, confirm(String); var id: String { if case let .confirm(p) = self { return "c" + p }; return "e" } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Settings").font(.largeTitle.weight(.bold)).padding(.bottom, 10)
                header("Playlists")
                ForEach(sources, id: \.id) { s in
                    Button { sourceMenu = s } label: { SourceRow(source: s, sync: app.syncStates.first { $0.sourceId == s.id }) }
                        .buttonStyle(RowButtonStyle())
                }
                row("Add playlist", "M3U or Xtream Codes") { editing = EditTarget(id: nil) }

                if let st = settings {
                    header("TV guide")
                    row("Keep past programmes", "\(st.guidePastDays) day(s)") { update { $0.with(past: next([1, 2, 3, 7], $0.guidePastDays)) } }
                    row("Load future programmes", "\(st.guideFutureDays) days") { update { $0.with(future: next([3, 7, 14], $0.guideFutureDays)) } }
                    row("Refresh all playlists and guides now", nil) { sources.forEach { app.core.refresh(sourceId: $0.id) } }
                    header("Playback")
                    row("Info bar timeout", "\(st.overlayTimeoutSec) s") { update { $0.with(timeout: next([3, 4, 6, 10], $0.overlayTimeoutSec)) } }
                    row("Prefer HLS for Xtream live", st.preferHlsLive ? "On (recommended on Apple TV)" : "Off") {
                        update { $0.with(hls: !$0.preferHlsLive) }
                    }
                }
                header("Parental control")
                row(app.core.hasPin() ? "Change PIN" : "Set a PIN", app.core.hasPin() ? "PIN is set" : "Lock channels and groups with a 4-digit PIN") {
                    protected { pinStep = .enter }
                }
                if app.core.hasPin() {
                    row("Remove PIN", nil) { protected { app.core.clearPin(); reload() } }
                }
                row("Hidden groups", nil) { protected { Task { hiddenGroups = (try? await app.core.liveGroups(includeHidden: true))?.filter(\.hidden) ?? [] } } }
                row("Hidden channels", nil) { protected { Task { hiddenChannels = (try? await app.core.hiddenChannels()) ?? [] } } }
                header("About")
                row("Tuner \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")", "Free and open-source (GPL-3.0). Tuner provides no content.") {}
            }
            .padding(.horizontal, 120)
            .padding(.vertical, 60)
        }
        .task { reload() }
        .onChange(of: app.version) { _, _ in reload() }
        .fullScreenCover(item: $editing) { SourceEditView(sourceId: $0.id) }
        .confirmationDialog(sourceMenu?.name ?? "", isPresented: Binding(get: { sourceMenu != nil }, set: { if !$0 { sourceMenu = nil } }), presenting: sourceMenu) { s in
            Button("Refresh now") { app.core.refresh(sourceId: s.id) }
            Button("Edit") { editing = EditTarget(id: s.id) }
            Button(s.enabled ? "Disable" : "Enable") {
                Task {
                    try? await app.core.updateSource(id: s.id, input: SourceInput(
                        name: s.name, type: s.type, url: s.url, username: s.username, password: s.password, epgUrls: s.epgUrls,
                        userAgent: s.userAgent, epgOffsetMinutes: s.epgOffsetMinutes, refreshHours: s.refreshHours, enabled: !s.enabled))
                }
            }
            Button("Delete", role: .destructive) { confirmDelete = s }
        }
        .alert("Delete \(confirmDelete?.name ?? "")?", isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }), presenting: confirmDelete) { s in
            Button("Delete", role: .destructive) {
                Task {
                    try? await app.core.deleteSource(id: s.id)
                    await app.refreshHasSources()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: { _ in Text("Its channels, favorites and watch history will be removed from this device.") }
        .fullScreenCover(item: $pinStep) { step in
            ZStack {
                Color.black.opacity(0.6).ignoresSafeArea()
                switch step {
                case .enter:
                    PinPad(title: "New PIN", message: "Choose a 4-digit PIN", onSubmit: { pin in
                        DispatchQueue.main.async { pinStep = .confirm(pin) }
                        return true
                    }, onCancel: { pinStep = nil })
                case let .confirm(first):
                    PinPad(title: "Confirm PIN", message: "Enter the same PIN again", onSubmit: { pin in
                        guard pin == first else { return false }
                        app.core.setPin(pin: pin)
                        pinStep = nil
                        reload()
                        return true
                    }, onCancel: { pinStep = nil })
                }
            }
        }
        .confirmationDialog("Hidden groups", isPresented: Binding(get: { hiddenGroups != nil }, set: { if !$0 { hiddenGroups = nil } })) {
            ForEach(hiddenGroups ?? [], id: \.id) { g in
                Button("Show \(g.name)") { Task { try? await app.core.setGroupHidden(group: g, hidden: false) } }
            }
            Button("Close", role: .cancel) {}
        } message: { Text((hiddenGroups ?? []).isEmpty ? "No hidden groups" : "Select a group to show it again") }
        .confirmationDialog("Hidden channels", isPresented: Binding(get: { hiddenChannels != nil }, set: { if !$0 { hiddenChannels = nil } })) {
            ForEach(hiddenChannels ?? [], id: \.id) { c in
                Button("Show \(c.name)") { Task { try? await app.core.setHidden(kind: .live, sourceId: c.sourceId, key: c.key, hidden: false) } }
            }
            Button("Close", role: .cancel) {}
        } message: { Text((hiddenChannels ?? []).isEmpty ? "No hidden channels" : "Select a channel to show it again") }
        .pinGate(gate)
    }

    private func reload() {
        settings = app.core.settings
        Task { sources = (try? await app.core.sources()) ?? [] }
    }

    private func update(_ transform: (TunerSettings) -> TunerSettings) {
        guard let s = settings else { return }
        let n = transform(s)
        app.core.updateSettings(newSettings: n)
        settings = n
    }

    /// Settings that weaken parental control require the PIN first.
    private func protected(_ action: @escaping () -> Void) {
        gate.run(app.core, locked: true, title: "Settings", action: action)
    }

    private func next(_ options: [Int32], _ current: Int32) -> Int32 {
        let i = options.firstIndex(of: current) ?? -1
        return options[(i + 1) % options.count]
    }

    private func header(_ title: String) -> some View {
        Text(title).font(.title3.weight(.semibold)).padding(.top, 24)
    }

    private func row(_ title: String, _ value: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title)
                Spacer()
                if let value { Text(value).foregroundStyle(Palette.textDim) }
            }
        }
        .buttonStyle(RowButtonStyle())
    }
}

private struct SourceRow: View {
    let source: Source
    let sync: SyncState?

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 14) {
                    Text(source.name)
                    Text(source.type == .xtream ? "Xtream" : "M3U").font(.caption).foregroundStyle(Palette.textDim)
                    if !source.enabled { Text("Disabled").font(.caption).foregroundStyle(Palette.textDim) }
                }
                Text(status).font(.caption).foregroundStyle(source.lastError != nil && sync?.running != true ? Palette.warning : Palette.textDim).lineLimit(1)
            }
            Spacer()
            Text("Options").foregroundStyle(Palette.textDim)
        }
    }

    private var status: String {
        if let s = sync, s.running { return "\(s.phase)…" + (s.count > 0 ? " \(s.count.formatted())" : "") }
        if let e = source.lastError { return e }
        if let t = source.lastRefreshed?.int64Value {
            var s = "Updated " + RelativeDateTimeFormatter().localizedString(for: t.date, relativeTo: Date())
            if let exp = source.expiresAt?.int64Value { s += " · account expires \(exp.date.formatted(date: .abbreviated, time: .omitted))" }
            return s
        }
        return "Never updated"
    }
}

private extension TunerSettings {
    func with(past: Int32? = nil, future: Int32? = nil, timeout: Int32? = nil, hls: Bool? = nil) -> TunerSettings {
        TunerSettings(
            guidePastDays: past ?? guidePastDays,
            guideFutureDays: future ?? guideFutureDays,
            use24h: use24h,
            overlayTimeoutSec: timeout ?? overlayTimeoutSec,
            autoplayLastChannel: autoplayLastChannel,
            preferHlsLive: hls ?? preferHlsLive
        )
    }
}
