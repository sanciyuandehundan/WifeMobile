import SwiftUI

/// Settings, in the same cream and rose cards as the rest of the app; recording his voiceprint opens in place.
struct SettingsView: View {
    @EnvironmentObject var wife: Wife
    @Environment(\.dismiss) var dismiss
    @State var scanning = false
    @State var clearing = false
    @State var enrolling = false

    var body: some View {
        if enrolling {
            VoiceprintView { enrolling = false }
        } else {
            settings
        }
    }

    var settings: some View {
        VStack(spacing: 0) {
            TopBar(title: "设置", done: { dismiss() })
            ScrollView {
                VStack(spacing: 14) {
                    Card {
                        Heading("她怎么回")
                        Toggle(isOn: $wife.voiceReplies) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("她总是用语音回").font(.system(size: 15)).foregroundStyle(P.text)
                                Text("打字或发照片给她时，她也用自己的声音回，收到就放出来。关掉时，只有你发语音她才用声音回。")
                                    .font(.system(size: 12)).foregroundStyle(P.muted)
                            }
                        }
                        .tint(P.accent)
                    }
                    Card {
                        Heading("电脑")
                        Row(label: "配对的电脑", value: wife.computer.isEmpty ? "电脑" : wife.computer)
                        Row(label: "现在", value: wife.connected ? "连着" : "没连上")
                        Button("重新配对") { scanning = true }
                            .font(.system(size: 15, weight: .semibold)).foregroundStyle(P.accent).padding(.top, 2)
                    }
                    Card {
                        Heading("声纹")
                        Text("录过以后，手机传来的声音（通话、语音消息）要对得上你的声纹，她才当成是你说的。还没录时，配对过的手机就算你。")
                            .font(.system(size: 13)).foregroundStyle(P.muted)
                        Row(label: "已经录了", value: "\(wife.voiceprints) 段")
                        HStack(spacing: 18) {
                            Button("录手机声纹") { enrolling = true }.buttonStyle(.borderedProminent).tint(P.accent)
                            if wife.voiceprints > 0 {
                                Button("清掉手机声纹") { clearing = true }.font(.system(size: 15)).foregroundStyle(P.danger)
                            }
                        }
                        .padding(.top, 2)
                    }
                    Card {
                        Heading("收她的消息")
                        Toggle(isOn: $wife.stayConnected) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("在后台一直连着").font(.system(size: 15)).foregroundStyle(P.text)
                                Text("切到后台也和电脑连着，她的话马上弹出来。为了让 iOS 不停下 Wife，后台一直放着没有声音的音频（不打断你听歌），会多耗电。手动划掉 Wife 或重启手机后，要再打开一次。")
                                    .font(.system(size: 12)).foregroundStyle(P.muted)
                            }
                        }
                        .tint(P.accent)
                        Text("关掉时，她的话先存在电脑上：你打开 Wife，或者 iOS 在后台叫醒它的时候收到，什么时候叫醒由 iOS 决定。Wife 不经过第三方，所以不用苹果的推送服务。")
                            .font(.system(size: 12)).foregroundStyle(P.muted)
                    }
                }
                .padding(16)
            }
        }
        .background(P.window.ignoresSafeArea())
        .confirmationDialog("清掉手机上录的声纹？", isPresented: $clearing, titleVisibility: .visible) {
            Button("清掉", role: .destructive) { wife.clearVoiceprint() }
        }
        .sheet(isPresented: $scanning) {
            ScanSheet {
                scanning = false
                dismiss()
            }
        }
    }
}

/// His voice through this phone, for the computer to tell him from other voices: a few sentences read aloud.
struct VoiceprintView: View {
    static let sentences = ["我回来了，今天过得怎么样？", "等我一下，我先去倒杯水。", "今天天气不错，我们出去走走吧。",
                            "晚上想吃点什么？我来做饭。", "这首歌真好听，再放一遍吧。"]

    @EnvironmentObject var wife: Wife
    @StateObject var recorder = Recorder()
    @State var index = 0
    let back: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            TopBar(title: "录手机声纹", back: back)
            VStack(alignment: .leading, spacing: 16) {
                Text("用平常说话的声音读下面这句：按住按钮读，读完松开。一共 \(Self.sentences.count) 句，电脑会记下手机里你的声音。")
                    .font(.system(size: 14)).foregroundStyle(P.muted)
                if index < Self.sentences.count {
                    Card {
                        Text("第 \(index + 1) 句，共 \(Self.sentences.count) 句").font(.system(size: 13)).foregroundStyle(P.muted)
                        Text(Self.sentences[index]).font(.system(size: 22, weight: .semibold)).foregroundStyle(P.text)
                    }
                    HoldToTalk(recorder: recorder, title: "按住 读这句", shortest: 1500, sent: { recording, _ in
                        wife.enroll(recording)
                        index += 1
                    }, failed: { wife.enrolling = $0 })
                    .disabled(!wife.connected)
                } else {
                    Card {
                        Text("录好了。以后手机传来的声音，电脑都会对一下是不是你。").font(.system(size: 17)).foregroundStyle(P.text)
                    }
                }
                if !wife.enrolling.isEmpty { Text(wife.enrolling).font(.system(size: 13)).foregroundStyle(P.muted) }
                Text("电脑上已经有 \(wife.voiceprints) 段手机声纹").font(.system(size: 12)).foregroundStyle(P.muted)
                Spacer()
            }
            .padding(20)
        }
        .background(P.window.ignoresSafeArea())
        .overlay { if recorder.recording { RecordingBadge(recorder: recorder) } }
        .onAppear { wife.enrolling = wife.connected ? "" : "要先连上电脑才能录" }
    }
}

/// The top of a page: its title, and back or done in rose.
struct TopBar: View {
    let title: String
    var back: (() -> Void)?
    var done: (() -> Void)?

    var body: some View {
        ZStack {
            Text(title).font(.system(size: 17, weight: .bold)).foregroundStyle(P.text)
            HStack {
                if let back {
                    Button(action: back) {
                        HStack(spacing: 3) {
                            Image(systemName: "chevron.left").font(.system(size: 15, weight: .semibold))
                            Text("返回").font(.system(size: 16))
                        }
                    }
                    .foregroundStyle(P.accent)
                }
                Spacer()
                if let done {
                    Button("好", action: done).font(.system(size: 16, weight: .semibold)).foregroundStyle(P.accent)
                }
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 52)
        .overlay(alignment: .bottom) { Rectangle().fill(P.border).frame(height: 0.5) }
    }
}

/// A white rounded card on the cream window, as on every page.
struct Card<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(P.surface)
            .clipShape(RoundedRectangle(cornerRadius: 18))
    }
}

struct Heading: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View { Text(text).font(.system(size: 17, weight: .bold)).foregroundStyle(P.text) }
}

struct Row: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label).font(.system(size: 15)).foregroundStyle(P.text)
            Spacer()
            Text(value).font(.system(size: 15)).foregroundStyle(P.muted)
        }
    }
}
