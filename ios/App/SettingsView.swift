import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var wife: Wife
    @Environment(\.dismiss) var dismiss
    @State var scanning = false
    @State var clearing = false

    var body: some View {
        NavigationStack {
            Form {
                Section("电脑") {
                    LabeledContent("配对的电脑", value: wife.computer.isEmpty ? "电脑" : wife.computer)
                    LabeledContent("现在", value: wife.connected ? "连着" : "没连上")
                    Button("重新配对") { scanning = true }
                }
                Section {
                    NavigationLink("录手机声纹") { VoiceprintView() }
                    LabeledContent("已经录了", value: "\(wife.voiceprints) 段")
                    if wife.voiceprints > 0 {
                        Button("清掉手机声纹", role: .destructive) { clearing = true }
                    }
                } header: {
                    Text("声纹")
                } footer: {
                    Text("录过以后，手机传来的声音（通话、语音消息）要对得上你的声纹，她才当成是你说的。还没录时，配对过的手机就算你。")
                }
                Section("收她的消息") {
                    Text("Wife 不在前台时，她的话先存在电脑上：你打开 Wife，或者 iOS 在后台叫醒它的时候就会收到，什么时候叫醒由 iOS 决定。苹果只让自己的推送服务马上叫醒 App，Wife 不经过第三方，所以不用它。")
                        .font(.system(size: 13)).foregroundStyle(P.muted)
                }
            }
            .scrollContentBackground(.hidden)
            .background(P.window.ignoresSafeArea())
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("好") { dismiss() } }
            }
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
}

/// His voice through this phone, for the computer to tell him from other voices: a few sentences read aloud.
struct VoiceprintView: View {
    static let sentences = ["我回来了，今天过得怎么样？", "等我一下，我先去倒杯水。", "今天天气不错，我们出去走走吧。",
                            "晚上想吃点什么？我来做饭。", "这首歌真好听，再放一遍吧。"]

    @EnvironmentObject var wife: Wife
    @StateObject var recorder = Recorder()
    @State var index = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("用平常说话的声音读下面这句：按住按钮读，读完松开。一共 \(Self.sentences.count) 句，电脑会记下手机里你的声音。")
                .font(.system(size: 14)).foregroundStyle(P.muted)
            if index < Self.sentences.count {
                Text("第 \(index + 1) 句，共 \(Self.sentences.count) 句").font(.system(size: 13)).foregroundStyle(P.muted)
                Text(Self.sentences[index]).font(.system(size: 22, weight: .semibold)).foregroundStyle(P.text)
                    .padding(20).frame(maxWidth: .infinity, alignment: .leading)
                    .background(P.surface).clipShape(RoundedRectangle(cornerRadius: 16))
                HoldToTalk(recorder: recorder, title: "按住 读这句", shortest: 1500, sent: { recording, _ in
                    wife.enroll(recording)
                    index += 1
                }, failed: { wife.enrolling = $0 })
                .disabled(!wife.connected)
            } else {
                Text("录好了。以后手机传来的声音，电脑都会对一下是不是你。").font(.system(size: 17)).foregroundStyle(P.text)
            }
            if !wife.enrolling.isEmpty { Text(wife.enrolling).font(.system(size: 13)).foregroundStyle(P.muted) }
            Text("电脑上已经有 \(wife.voiceprints) 段手机声纹").font(.system(size: 12)).foregroundStyle(P.muted)
            Spacer()
        }
        .padding(20)
        .background(P.window.ignoresSafeArea())
        .navigationTitle("录手机声纹")
        .onAppear { wife.enrolling = wife.connected ? "" : "要先连上电脑才能录" }
    }
}
