import BackgroundTasks
import SwiftUI
import VisionKit

@main
struct WifeApp: App {
    @StateObject private var wife = Wife.shared
    @Environment(\.scenePhase) private var phase

    init() {
        // iOS wakes the app now and then to fetch what she said meanwhile (no push service: protocol.md 送到)
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Wife.fetchTask, using: nil) { task in
            Wife.shared.fetch(task as! BGAppRefreshTask)
        }
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if wife.paired { ChatView() } else { PairingView() }
            }
            .environmentObject(wife)
            .onAppear { wife.foreground() }
        }
        .onChange(of: phase) { now in
            if now == .active { wife.foreground() } else if now == .background { wife.background() }
        }
    }
}

/// Cream and rose by day, cocoa and candlelight at night: the same colours as Wife's windows on the computer.
enum P {
    static let window = shade(0xFFF9F5, 0x221C1B), surface = shade(0xFFFFFF, 0x2C2422), text = shade(0x3E302C, 0xF4EAE5)
    static let muted = shade(0x9A857C, 0xB5A098), accent = shade(0xD9677C, 0xF29BA8), soft = shade(0xFDE7EA, 0x4A2D33)
    static let border = shade(0xF0DED5, 0x3E322F), danger = shade(0xC9473F, 0xF08A80)

    static func shade(_ day: UInt32, _ night: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? color(night) : color(day) })
    }

    private static func color(_ hex: UInt32) -> UIColor {
        UIColor(red: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255,
                alpha: 1)
    }
}

/// Her initial in a rose circle.
struct Avatar: View {
    let name: String
    var size: CGFloat = 44

    var body: some View {
        Text(String(name.prefix(1))).font(.system(size: size * 0.42, weight: .bold)).foregroundStyle(P.accent)
            .frame(width: size, height: size).background(P.soft).clipShape(Circle())
    }
}

/// The first screen until the phone has scanned the computer's one-time code.
struct PairingView: View {
    @EnvironmentObject var wife: Wife
    @State var scanning = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 14) {
                Avatar(name: wife.her, size: 56)
                Text(wife.her).font(.system(size: 22, weight: .bold)).foregroundStyle(P.text)
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("先和电脑上的她认识一下").font(.system(size: 17, weight: .bold)).foregroundStyle(P.text)
                Text("在电脑上打开 Wife 的“手机”附属功能，点“显示配对码”，再用这里扫一下。手机和电脑要连着同一个 WiFi。配对码只能用一次，扫完就作废。")
                    .font(.system(size: 14)).foregroundStyle(P.muted)
                Button("扫码配对") { scanning = true }.buttonStyle(.borderedProminent).tint(P.accent).padding(.top, 6)
            }
            .padding(18).frame(maxWidth: .infinity, alignment: .leading).background(P.surface)
            .clipShape(RoundedRectangle(cornerRadius: 18))
            if !wife.note.isEmpty { Text(wife.note).font(.system(size: 13)).foregroundStyle(P.muted) }
            Spacer()
        }
        .padding(20)
        .background(P.window.ignoresSafeArea())
        .sheet(isPresented: $scanning) { ScanSheet { scanning = false } }
    }
}

/// The camera looking for the pairing code, or a note when this iPhone can't scan.
struct ScanSheet: View {
    @EnvironmentObject var wife: Wife
    let done: () -> Void

    var body: some View {
        if DataScannerViewController.isSupported {
            Scanner { qr in
                done()
                wife.pair(qr)
            }
            .ignoresSafeArea()
        } else {
            Text("这台 iPhone 不能用相机扫码").padding()
        }
    }
}
