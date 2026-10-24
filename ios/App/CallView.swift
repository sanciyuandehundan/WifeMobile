import SwiftUI

/// A call with her: the phone is his microphone and her speaker; her words show as she says them.
struct CallView: View {
    @EnvironmentObject var wife: Wife
    @State var since = Date()

    var said: String {
        wife.typing.isEmpty ? wife.items.last(where: { !$0.his })?.text ?? "" : wife.typing
    }

    var body: some View {
        VStack(spacing: 14) {
            Spacer().frame(height: 36)
            Avatar(name: wife.her, size: 112)
            Text(wife.her).font(.system(size: 26, weight: .bold)).foregroundStyle(P.text)
            TimelineView(.periodic(from: since, by: 1)) { context in
                Text(status(context.date)).font(.system(size: 14)).foregroundStyle(P.muted)
            }
            Text(said).font(.system(size: 16)).foregroundStyle(P.text).multilineTextAlignment(.center).lineLimit(5)
                .padding(.horizontal, 32).frame(minHeight: 100)
            Spacer()
            HStack(spacing: 36) {
                choice("desktopcomputer", "在电脑旁", wife.near) { wife.setNear(!wife.near) }
                choice("speaker.wave.2.fill", "外放", wife.speaker) { wife.speaker.toggle() }
            }
            Button(action: wife.hangUp) {
                Image(systemName: "phone.down.fill").font(.system(size: 28)).foregroundStyle(.white)
                    .frame(width: 76, height: 76).background(P.danger).clipShape(Circle())
            }
            .padding(.top, 10)
            if !wife.note.isEmpty { Text(wife.note).font(.system(size: 12)).foregroundStyle(P.muted).padding(.horizontal) }
            Text(wife.stats).font(.system(size: 10)).foregroundStyle(P.muted).padding(.bottom, 12)
        }
        .frame(maxWidth: .infinity)
        .background(P.window.ignoresSafeArea())
        .onAppear { since = Date() }
    }

    func status(_ now: Date) -> String {
        if wife.awake == false { return "接通了，但电脑上的她在休息" }
        let seconds = Int(now.timeIntervalSince(since))
        return String(format: "通话中 %d:%02d", seconds / 60, seconds % 60)
    }

    func choice(_ icon: String, _ title: String, _ on: Bool, _ flip: @escaping () -> Void) -> some View {
        Button(action: flip) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.system(size: 20)).foregroundStyle(on ? .white : P.text)
                    .frame(width: 58, height: 58).background(on ? P.accent : P.surface).clipShape(Circle())
                    .overlay(Circle().stroke(P.border))
                Text(title).font(.system(size: 12)).foregroundStyle(P.muted)
            }
        }
        .buttonStyle(.plain)
    }
}
