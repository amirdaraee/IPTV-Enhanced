import SwiftUI
import TunerCore

/// Add or edit a playlist. A new source shows sync progress, then closes.
struct SourceEditView: View {
    let sourceId: Int64?

    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var type: SourceType = .xtream
    @State private var name = ""
    @State private var url = ""
    @State private var username = ""
    @State private var password = ""
    @State private var epgUrl = ""
    @State private var userAgent = ""
    @State private var offsetHours = "0"
    @State private var error: String?
    @State private var info: String?
    @State private var busy = false
    @State private var syncingId: Int64?
    @FocusState private var saveFocused: Bool

    private var syncState: SyncState? {
        guard let id = syncingId else { return nil }
        return model.syncStates.first { $0.sourceId == id }
    }

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    Text(sourceId == nil ? "Add a playlist" : "Edit playlist").font(.largeTitle.weight(.bold))
                    Text("IPTV Enhanced plays content from your own provider. It doesn't include any channels.")
                        .foregroundStyle(Palette.textDim)
                    Picker("Type", selection: $type) {
                        Text("Xtream login (player API)").tag(SourceType.xtream)
                        Text("M3U playlist URL").tag(SourceType.m3u)
                    }
                    .pickerStyle(.segmented)
                    .frame(maxWidth: 900)

                    field("Name (optional)", text: $name, prompt: "My provider")
                    if type == .xtream {
                        field("Server", text: $url, prompt: "http://provider.example:8080", keyboard: .URL)
                        HStack(spacing: 30) {
                            field("Username", text: $username, prompt: "")
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Password").font(.caption).foregroundStyle(Palette.textDim)
                                SecureField("", text: $password)
                            }
                        }
                    } else {
                        field("Playlist URL", text: $url, prompt: "http://provider.example/playlist.m3u", keyboard: .URL)
                    }
                    field(type == .xtream ? "Extra EPG URL (optional — the provider's guide is added automatically)" : "EPG URL (optional — read from the playlist if present)",
                          text: $epgUrl, prompt: "http://example/epg.xml.gz", keyboard: .URL)
                    // Down from the last row goes to the main button; the nearest one under the wide
                    // User-Agent field would be Cancel.
                    HStack(spacing: 30) {
                        field("Custom User-Agent (optional)", text: $userAgent, prompt: "")
                        field("Guide time shift (hours)", text: $offsetHours, prompt: "0", keyboard: .numbersAndPunctuation)
                            .frame(width: 420)
                    }
                    .onMoveCommand { if $0 == .down, !busy { saveFocused = true } }

                    if let error { Text(error).foregroundStyle(Palette.live) }
                    if let info { Text(info).foregroundStyle(Palette.accent) }

                    if busy {
                        HStack(spacing: 20) {
                            ProgressView()
                            Text(progressText).foregroundStyle(Palette.textDim)
                        }
                    } else {
                        HStack(spacing: 30) {
                            Button(sourceId == nil ? "Add playlist" : "Save") { Task { await save() } }
                                .focused($saveFocused)
                            Button("Cancel") { dismiss() }
                        }
                    }
                }
                .padding(.horizontal, 120)
                .padding(.vertical, 60)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .task { await load() }
        .onChange(of: syncState?.running) { _, running in
            guard running == false, let s = syncState else { return }
            if s.phase == "Failed" {
                error = s.error
                busy = false
                syncingId = nil
            } else {
                Task {
                    await model.refreshHasSources()
                    dismiss()
                }
            }
        }
    }

    private var progressText: String {
        guard let s = syncState else { return "Connecting…" }
        return s.count > 0 ? "\(s.phase)… \(s.count.formatted())" : "\(s.phase)…"
    }

    private func field(_ label: String, text: Binding<String>, prompt: String, keyboard: UIKeyboardType = .default) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label).font(.caption).foregroundStyle(Palette.textDim)
            TextField(prompt, text: text)
                .accessibilityLabel(label)
                .keyboardType(keyboard)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
    }

    private func load() async {
        guard let id = sourceId, let s = try? await model.core.source(id: id) else { return }
        type = s.type
        name = s.name
        url = s.url
        username = s.username ?? ""
        password = s.password ?? ""
        epgUrl = s.epgUrls.joined(separator: ", ")
        userAgent = s.userAgent ?? ""
        offsetHours = String(format: "%g", Double(s.epgOffsetMinutes) / 60)
    }

    private func input() -> SourceInput {
        let host = url.components(separatedBy: "://").last?.components(separatedBy: CharacterSet(charactersIn: ":/")).first ?? "Playlist"
        let epgs = epgUrl.split(whereSeparator: { $0 == "," || $0 == "\n" }).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        let offset = Int32((Double(offsetHours.replacingOccurrences(of: ",", with: ".")) ?? 0) * 60)
        return SourceInput(
            name: name.isEmpty ? (type == .xtream ? host : "Playlist") : name,
            type: type,
            url: url.trimmingCharacters(in: .whitespaces),
            username: type == .xtream ? username.trimmingCharacters(in: .whitespaces) : nil,
            password: type == .xtream ? password : nil,
            epgUrls: epgs,
            userAgent: userAgent.isEmpty ? nil : userAgent,
            epgOffsetMinutes: offset,
            refreshHours: 24,
            enabled: true
        )
    }

    private func save() async {
        error = nil
        if url.isEmpty { error = type == .xtream ? "Enter the server address" : "Enter the playlist URL"; return }
        if type == .xtream && (username.isEmpty || password.isEmpty) { error = "Enter your username and password"; return }
        busy = true
        do {
            if type == .xtream {
                let acc = try await model.core.verifyXtream(server: url, username: username, password: password)
                var parts: [String] = []
                if let s = acc.status { parts.append("Account \(s)") }
                if let e = acc.expiresAt { parts.append("expires \(e.int64Value.date.formatted(date: .abbreviated, time: .omitted))") }
                info = parts.joined(separator: " · ")
            }
            if let id = sourceId {
                try await model.core.updateSource(id: id, input: input())
                model.core.refresh(sourceId: id)
                dismiss()
            } else {
                syncingId = try await model.core.addSource(input: input()).int64Value
            }
        } catch {
            self.error = error.localizedDescription
            busy = false
        }
    }
}
