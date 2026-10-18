import SwiftUI
import VisionKit

@main
struct WifeApp: App {
    @StateObject private var wife = Wife.shared
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            Home().environmentObject(wife).onAppear { wife.appeared() }
        }
        .onChange(of: phase) { now in
            if now == .active { wife.appeared() }
        }
    }
}

/// Cream and rose by day, cocoa and candlelight at night: the same colours as Wife's windows on the computer.
private enum P {
    static let window = shade(0xFFF9F5, 0x221C1B), surface = shade(0xFFFFFF, 0x2C2422), text = shade(0x3E302C, 0xF4EAE5)
    static let muted = shade(0x9A857C, 0xB5A098), accent = shade(0xD9677C, 0xF29BA8), soft = shade(0xFDE7EA, 0x4A2D33)
    static let border = shade(0xF0DED5, 0x3E322F)

    static func shade(_ day: UInt32, _ night: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? color(night) : color(day) })
    }

    private static func color(_ hex: UInt32) -> UIColor {
        UIColor(red: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255,
                alpha: 1)
    }
}

struct Home: View {
    @EnvironmentObject private var wife: Wife
    @State private var scanning = false
    @State private var typed = ""

    private var status: String {
        if !wife.paired { return "还没有和电脑配对" }
        if wife.dialing { return "正在找电脑…" }
        if wife.inCall { return wife.awake == false ? "接通了，但电脑上的她在休息" : "正在说话 · " + (wife.near ? "在电脑旁" : "在外面") }
        return "和 \(wife.computer.isEmpty ? "电脑" : wife.computer) 配对了"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 14) {
                Text(String(wife.her.prefix(1))).font(.system(size: 24, weight: .bold)).foregroundStyle(P.accent)
                    .frame(width: 56, height: 56).background(P.soft).clipShape(Circle())
                VStack(alignment: .leading, spacing: 2) {
                    Text(wife.her).font(.system(size: 22, weight: .bold)).foregroundStyle(P.text)
                    Text(status).font(.system(size: 14)).foregroundStyle(P.muted)
                }
            }
            if wife.paired { talking } else { pairing }
        }
        .padding(.horizontal, 20)
        .padding(.top, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(P.window.ignoresSafeArea())
        .sheet(isPresented: $scanning) {
            if DataScannerViewController.isSupported {
                Scanner { qr in
                    scanning = false
                    wife.pair(qr)
                }
                .ignoresSafeArea()
            } else {
                Text("这台 iPhone 不能用相机扫码").padding()
            }
        }
    }

    private var pairing: some View {
        VStack(alignment: .leading, spacing: 8) {
            card {
                Text("先和电脑上的她认识一下").font(.system(size: 17, weight: .bold)).foregroundStyle(P.text)
                Text("在电脑上打开 Wife 的“手机”附属功能，点“显示配对码”，再用这里扫一下。手机和电脑要连着同一个 WiFi。配对码只能用一次，扫完就作废。")
                    .font(.system(size: 14)).foregroundStyle(P.muted)
                Button("扫码配对") { scanning = true }.buttonStyle(.borderedProminent).tint(P.accent).padding(.top, 6)
            }
            note
        }
    }

    private var talking: some View {
        VStack(spacing: 14) {
            Button(action: wife.callButton) {
                Text(wife.dialing ? "取消" : wife.inCall ? "挂断" : "打给她").font(.system(size: 20, weight: .bold))
                    .foregroundStyle(wife.inCall || wife.dialing ? P.accent : .white)
                    .frame(width: 132, height: 132).background(wife.inCall || wife.dialing ? P.soft : P.accent).clipShape(Circle())
            }
            card {
                toggle("我在电脑旁边", "她的声音从手机出来，手机当你的耳机和麦克风",
                       Binding(get: { wife.near }, set: { wife.setNear($0) }))
                toggle("外放", "关掉就用听筒；戴着耳机时总是从耳机出来", $wife.speaker)
            }
            if !wife.stats.isEmpty { Text(wife.stats).font(.system(size: 12)).foregroundStyle(P.muted) }
            note
            ScrollViewReader { reader in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(wife.lines) { line in bubble(line).id(line.id) }
                    }
                }
                .onChange(of: wife.lines.last?.text) { _ in
                    if let last = wife.lines.last { reader.scrollTo(last.id, anchor: .bottom) }
                }
            }
            HStack {
                TextField("打字给她", text: $typed).textFieldStyle(.roundedBorder)
                Button("说") {
                    wife.type(typed)
                    typed = ""
                }
                .buttonStyle(.borderedProminent).tint(P.accent).disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            Button("重新配对") { scanning = true }.font(.system(size: 13)).foregroundStyle(P.muted)
        }
    }

    @ViewBuilder private var note: some View {
        if !wife.note.isEmpty { Text(wife.note).font(.system(size: 13)).foregroundStyle(P.muted) }
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6, content: content).padding(18).frame(maxWidth: .infinity, alignment: .leading)
            .background(P.surface).clipShape(RoundedRectangle(cornerRadius: 18))
    }

    private func toggle(_ title: String, _ detail: String, _ on: Binding<Bool>) -> some View {
        Toggle(isOn: on) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15)).foregroundStyle(P.text)
                Text(detail).font(.system(size: 12)).foregroundStyle(P.muted)
            }
        }
        .tint(P.accent)
    }

    private func bubble(_ line: Line) -> some View {
        HStack {
            if line.his { Spacer(minLength: 40) }
            Text(line.text).font(.system(size: 15)).foregroundStyle(line.his ? .white : P.text)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background(line.his ? P.accent : P.surface).clipShape(RoundedRectangle(cornerRadius: 16))
            if !line.his { Spacer(minLength: 40) }
        }
    }
}
