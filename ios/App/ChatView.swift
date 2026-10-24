import AVFoundation
import PhotosUI
import SwiftUI

/// Talking with her: words, voice messages and photos in one conversation, as in a messaging app; calling her is a
/// button at the top.
struct ChatView: View {
    @EnvironmentObject var wife: Wife
    @ObservedObject var player = Wife.shared.player
    @StateObject var recorder = Recorder()
    @State var typed = ""
    @State var speaking = false                 // the hold-to-talk button in place of the keyboard
    @State var camera = false
    @State var picked: PhotosPickerItem?
    @State var settings = false
    @State var looking: Item?

    var status: String {
        if !wife.connected { return wife.connecting ? "正在连电脑…" : "连不上电脑" }
        if wife.awake == false { return "电脑上的她在休息" }
        return wife.away ? "在线 · 你不在电脑前" : "在线"
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            conversation
                .fullScreenCover(item: $looking) { item in PhotoView(image: wife.chat.picture(item.id)) }
            bar
                .fullScreenCover(isPresented: $camera) { Camera { wife.sendPhoto($0) }.ignoresSafeArea() }
        }
        .background(P.window.ignoresSafeArea())
        .overlay { if recorder.recording { RecordingBadge(recorder: recorder) } }
        .sheet(isPresented: $settings) { SettingsView() }
        .fullScreenCover(isPresented: Binding(get: { wife.inCall }, set: { _ in })) { CallView() }
        .onChange(of: picked) { item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                    await MainActor.run { wife.sendPhoto(image) }
                }
                await MainActor.run { picked = nil }
            }
        }
    }

    var header: some View {
        HStack(spacing: 12) {
            Avatar(name: wife.her)
            VStack(alignment: .leading, spacing: 1) {
                Text(wife.her).font(.system(size: 17, weight: .bold)).foregroundStyle(P.text)
                Text(status).font(.system(size: 12)).foregroundStyle(P.muted)
            }
            Spacer()
            Button(action: wife.call) {
                Image(systemName: "phone.fill").font(.system(size: 17)).foregroundStyle(P.accent)
                    .frame(width: 40, height: 40).background(P.soft).clipShape(Circle())
            }
            Button { settings = true } label: {
                Image(systemName: "gearshape").font(.system(size: 18)).foregroundStyle(P.muted).frame(width: 40, height: 40)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .overlay(alignment: .bottom) { Rectangle().fill(P.border).frame(height: 0.5) }
    }

    var conversation: some View {
        ScrollViewReader { reader in
            ScrollView {
                LazyVStack(spacing: 10) {
                    ForEach(Array(wife.items.enumerated()), id: \.element.id) { index, item in
                        if index == 0 || item.t - wife.items[index - 1].t > 5 * 60_000 { TimeLine(date: item.date) }
                        Bubble(item: item, chat: wife.chat, playing: player.playing == item.id,
                               play: { wife.play(item) }, look: { looking = item })
                    }
                    if !wife.typing.isEmpty { Bubble.her(Text(wife.typing + " …").foregroundStyle(P.muted)) }
                    if !wife.note.isEmpty {
                        Text(wife.note).font(.system(size: 12)).foregroundStyle(P.muted).multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }
                    Color.clear.frame(height: 1).id("end")
                }
                .padding(12)
            }
            .scrollDismissesKeyboard(.interactively)
            .onAppear { reader.scrollTo("end", anchor: .bottom) }
            .onChange(of: wife.items.count) { _ in withAnimation { reader.scrollTo("end", anchor: .bottom) } }
            .onChange(of: wife.typing) { _ in reader.scrollTo("end", anchor: .bottom) }
        }
    }

    var bar: some View {
        HStack(spacing: 8) {
            Button { speaking.toggle() } label: {
                Image(systemName: speaking ? "keyboard" : "mic").font(.system(size: 19)).foregroundStyle(P.text)
                    .frame(width: 36, height: 40)
            }
            if speaking {
                HoldToTalk(recorder: recorder, title: "按住 说话", sent: { wife.sendVoice($0, ms: $1) },
                           failed: { wife.note = $0 })
            } else {
                TextField("发消息", text: $typed)
                    .padding(.horizontal, 12).padding(.vertical, 10)
                    .background(P.surface).clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(P.border))
                    .submitLabel(.send)
                    .onSubmit(send)
            }
            if !speaking, !typed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Button(action: send) {
                    Image(systemName: "arrow.up").font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                        .frame(width: 36, height: 36).background(P.accent).clipShape(Circle())
                }
            } else {
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    Button { camera = true } label: {
                        Image(systemName: "camera").font(.system(size: 19)).foregroundStyle(P.text).frame(width: 34, height: 40)
                    }
                }
                PhotosPicker(selection: $picked, matching: .images) {
                    Image(systemName: "photo").font(.system(size: 19)).foregroundStyle(P.text).frame(width: 34, height: 40)
                }
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .overlay(alignment: .top) { Rectangle().fill(P.border).frame(height: 0.5) }
    }

    func send() {
        wife.type(typed)
        typed = ""
    }
}

/// One line of the conversation: his on the right in rose, hers on the left.
struct Bubble: View {
    let item: Item
    let chat: Chat
    let playing: Bool
    let play: () -> Void
    let look: () -> Void

    static func her<Content: View>(_ content: Content) -> some View {
        HStack {
            content.font(.system(size: 16)).padding(.horizontal, 14).padding(.vertical, 10)
                .background(P.surface).clipShape(RoundedRectangle(cornerRadius: 18))
            Spacer(minLength: 48)
        }
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 6) {
            if item.his {
                Spacer(minLength: 48)
                if item.pending { ProgressView().scaleEffect(0.7) }
            }
            VStack(alignment: item.his ? .trailing : .leading, spacing: 4) {
                content
                if item.his, item.kind == .voice {
                    if item.heard == false {
                        Text("没认出是你的声音，她没当成你说的").font(.system(size: 12)).foregroundStyle(P.danger)
                    } else if !item.text.isEmpty {
                        Text(item.text).font(.system(size: 12)).foregroundStyle(P.muted)
                    }
                }
            }
            if !item.his { Spacer(minLength: 48) }
        }
    }

    @ViewBuilder var content: some View {
        switch item.kind {
        case .text:
            Text(item.text).font(.system(size: 16)).foregroundStyle(item.his ? .white : P.text)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(item.his ? P.accent : P.surface).clipShape(RoundedRectangle(cornerRadius: 18))
                .textSelection(.enabled)
        case .photo:
            VStack(alignment: .trailing, spacing: 4) {
                if let image = chat.picture(item.id) {
                    Image(uiImage: image).resizable().scaledToFit().frame(maxWidth: 200, maxHeight: 260)
                        .clipShape(RoundedRectangle(cornerRadius: 14)).onTapGesture(perform: look)
                }
                if !item.text.isEmpty { Text(item.text).font(.system(size: 14)).foregroundStyle(P.muted) }
            }
        case .voice:
            if item.his {
                voice.foregroundStyle(.white).padding(.horizontal, 14).padding(.vertical, 10)
                    .frame(width: CGFloat(min(220, 76 + item.ms / 1000 * 6)), alignment: .trailing)
                    .background(P.accent).clipShape(RoundedRectangle(cornerRadius: 18))
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    voice.foregroundStyle(P.accent)
                    Text(item.text).font(.system(size: 16)).foregroundStyle(P.text).textSelection(.enabled)
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(P.surface).clipShape(RoundedRectangle(cornerRadius: 18))
            }
        }
    }

    var voice: some View {
        Button(action: play) {
            HStack(spacing: 6) {
                Image(systemName: playing ? "stop.fill" : "play.fill").font(.system(size: 13))
                Image(systemName: "waveform").font(.system(size: 15))
                Text("\(max(1, item.ms / 1000))″").font(.system(size: 14, weight: .medium))
            }
        }
        .buttonStyle(.plain)
    }
}

struct TimeLine: View {
    let date: Date

    var style: Date.FormatStyle {
        Calendar.current.isDateInToday(date) ? .dateTime.hour().minute() : .dateTime.month().day().hour().minute()
    }

    var body: some View {
        Text(date, format: style).font(.system(size: 11)).foregroundStyle(P.muted).padding(.top, 4)
    }
}

/// While he holds the button: how long, how loud, and that sliding up cancels.
struct RecordingBadge: View {
    @ObservedObject var recorder: Recorder

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "mic.fill").font(.system(size: 34)).foregroundStyle(P.accent)
            Capsule().fill(P.accent).frame(width: 30 + CGFloat(recorder.level) * 110, height: 6)
                .animation(.linear(duration: 0.1), value: recorder.level)
            TimelineView(.periodic(from: recorder.started, by: 1)) { context in
                let seconds = Int(context.date.timeIntervalSince(recorder.started))
                Text(String(format: "%d:%02d", seconds / 60, seconds % 60)).font(.system(size: 15, weight: .medium))
                    .foregroundStyle(P.text)
            }
            Text("松开发送 · 上滑取消").font(.system(size: 12)).foregroundStyle(P.muted)
        }
        .padding(22)
        .background(P.surface)
        .clipShape(RoundedRectangle(cornerRadius: 20))
        .shadow(color: .black.opacity(0.12), radius: 16)
        .allowsHitTesting(false)
    }
}

/// Hold to record, let go to send, slide up to cancel, the way messaging apps do it.
struct HoldToTalk: View {
    @ObservedObject var recorder: Recorder
    let title: String
    var shortest = 600                          // ms: shorter is a slip of the finger
    let sent: (Data, Int) -> Void
    let failed: (String) -> Void
    @State var holding = false
    @State var cancelling = false

    var body: some View {
        Text(recorder.recording ? (cancelling ? "松开手指，取消" : "松开 发送") : title)
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(recorder.recording ? .white : P.text)
            .frame(maxWidth: .infinity, minHeight: 42)
            .background(recorder.recording ? (cancelling ? P.danger : P.accent) : P.surface)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(P.border))
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { value in
                    if !holding {
                        holding = true
                        begin()
                    }
                    cancelling = value.translation.height < -60
                }
                .onEnded { _ in
                    holding = false
                    finish()
                })
    }

    func begin() {
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted:
            do {
                try recorder.start()
            } catch {
                failed("麦克风打不开：\(error.localizedDescription)")
            }
        case .undetermined:
            AVAudioSession.sharedInstance().requestRecordPermission { _ in }
        default:
            failed("没有麦克风权限：在 iPhone 的设置里给 Wife 打开麦克风")
        }
    }

    func finish() {
        guard recorder.recording else { return }
        let (recording, ms) = recorder.stop()
        if !cancelling, ms >= shortest { sent(recording, ms) }
        cancelling = false
    }
}

/// A photo he sent, full screen; a tap closes it.
struct PhotoView: View {
    let image: UIImage?
    @Environment(\.dismiss) var dismiss

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image { Image(uiImage: image).resizable().scaledToFit() }
        }
        .onTapGesture { dismiss() }
    }
}

/// The camera, for a quick photo to send her.
struct Camera: UIViewControllerRepresentable {
    let taken: (UIImage) -> Void
    @Environment(\.dismiss) var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ picker: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: Camera

        init(_ parent: Camera) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.taken(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}
