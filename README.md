# WifeMobile

她在手机上。App 打开就是和她的聊天，像聊天软件一样：

- **打字、语音、照片**在同一条对话里。按住说话发一段语音，她回文字和她自己的声音；照片由电脑上她看屏幕的模型看过再告诉她；可以直接拍一张。
- **打电话**进单独的通话页。在电脑旁时，手机当你的耳机和麦克风；不在电脑旁时，她知道你在外面。
- **你离开电脑时**（Wife 判断你没在电脑上说话、没碰键盘鼠标、没在放东西，超过你设的时间），她说的话不在电脑上放，带着她的声音发到手机上。安卓常驻后台一直连着，马上弹通知；iPhone 默认也在后台一直连着（后台一直放没有声音的音频，iOS 才不停下它，会多耗电；划掉 App 或重启手机后要再打开一次），关掉这一项时电脑先存着，你打开 App 或 iOS 在后台叫醒它时收到。
- **手机声纹**：在手机上读 5 句录下来，存在电脑上，和电脑麦克风的声纹分开。录过以后，手机传来的声音要对得上才算你说的。
- 连不上电脑时，安卓可以叫醒**手机上的她**：同一个大脑（Qwen2.5-7B Q4_K_M + 说话 LoRA）在手机上跑，用电脑交给手机的角色卡和记忆。

不经过任何服务器：没有中转，没有 STUN，也不用苹果、谷歌的推送。在家走局域网；外出时拨电脑的公网地址，电脑地址变了怎么办由电脑上选的方式决定：IPv6 直连（回家自动更新）、BT 网络（拨不通时查一次 DHT）、回家重新配对。协议见 [protocol.md](protocol.md)。

## 安全

- 配对码（二维码）只在电脑内存里，扫一次、5 分钟或关窗口就作废，以后也看不到。
- 链路密钥由两边的临时 X25519 密钥和配对码一起算出；电脑用 Windows DPAPI 存，手机用安卓 Keystore 加密存，哪儿都不显示。
- 每条会话再用临时密钥协商（前向保密），ChaCha20-Poly1305 加密，加解密都在两台设备上做。
- 聊天记录、语音和照片存在手机 App 自己的存储里；电脑上她的话只在送到手机前存在 `pc/data/chat/`，手机收下就删。

## 目录

| 目录 | 内容 |
|---|---|
| `pc/` | 电脑端，Wife 的附属功能“手机”：托盘程序、配对窗口、UDP 18766、DHT 节点、把聊天和通话接到 Wife、给每台手机的待送箱 |
| `android/` | 安卓 App（Kotlin + Compose，原生部分是 libopus 和 llama.cpp） |
| `ios/` | iPhone App（Swift + SwiftUI）：`WifeLink/` 是协议（不含界面的 Swift 包），`App/` 是界面、声音和钥匙串 |

WifeBody 里配合它的：语音识别的 `/v1/mic/remote`（通话时手机当麦克风）和 `/v1/speaker/phone`（手机声纹）；附属接口的 `stt`（带手机声纹比对）、`tts`（按她这句的情绪）、`see`（看照片）、`voiceprint`、`offline`（她的离线资料），以及 `presence` 事件（他离开或回到电脑）。

## 电脑端

```powershell
cd pc
..\..\WifeBody\tools\python310\python.exe -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements.txt
.venv\Scripts\python.exe main.py --settings
```

第一次运行时自动和同级的 WifeBody 绑定，Wife 的“附属功能”页会出现“手机”。Windows 防火墙问起时允许 Python 使用网络。测试：`.venv\Scripts\python.exe -m pytest`。

## 安卓端

需要 JDK 21、Android SDK（platform 37、build-tools 36.1.0、NDK 29.0.14206865、CMake 3.31.6），`android/local.properties` 写 `sdk.dir`。原生库的源码放在 `android/third_party/`（不进 git）：

- `opus/`：libopus 1.5.2
- `llama.cpp/`：llama.cpp b10419（和 WifeBody 的 llama-server 同一版本）

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

单元测试读 `pc/tests/vectors.json`，确认手机和电脑算出一样的字节。Windows 用户名里有中文时，Gradle 的测试进程找不到自己的类：给 `%USERPROFILE%\.gradle` 建一个英文路径的目录联接（如 `mklink /J D:\Android\gradle-home %USERPROFILE%\.gradle`），再设 `GRADLE_USER_HOME` 指向它。

**手机上的她**：用数据线把电脑上的模型文件拷进手机的“下载”：`WifeBody/models/brain-candidates/qwen2.5-7b-instruct/` 下的两个 GGUF 分片，和 `WifeBrain/output/wife-v24-q4/epoch2/wife-chat.gguf`（共 4.8 GB）；再在 App 里点“选择大脑文件”选中它们，App 把它们复制进自己的存储。别用 adb 往 `Android/data/io.wife.mobile` 里放：安卓会重建那个文件夹，里面的东西一起删掉。llama.cpp 按 i8mm 编译（骁龙 8 Gen 1 及以后），更老的手机跑不了手机上的她。

## iOS 端

家里没有 Mac，iPhone 版在 GitHub 的 Mac 上编（`.github/workflows/ios.yml`）：先用 `swift test` 拿 `pc/tests/vectors.json` 核对协议，再用 XcodeGen 按 `ios/project.yml` 生成工程、编出没签名的 `Wife-unsigned.ipa`（在 Actions 页面的 Artifacts 里下载）。装到手机上要用 Apple ID 签名，比如 Windows 上的 Sideloadly；免费 Apple ID 签的 7 天后过期，要重新签。

和安卓版的区别：iOS 不让普通 App 发局域网广播（要向苹果申请权限），所以 iPhone 只拨它知道的地址：配对码里的、电脑后来告诉它的。iOS 不让普通 App 常驻后台：设置里的“在后台一直连着”（默认开）靠后台一直放没有声音的音频（和别的 App 的声音混着放，不打断音乐）让 iOS 不停下 Wife，她的话马上弹出来，代价是多耗电，划掉 App 或重启手机后要再打开一次；关掉时切到后台就挂断，她的话由电脑存着，打开 App 或 iOS 的后台刷新叫醒 App 时取。不用苹果的推送服务。没有手机上的她。Opus 用 [alta/swift-opus](https://github.com/alta/swift-opus) 里的 libopus。

## 现状

- 在真机上跑通过（小米，骁龙 8 至尊版，安卓 17；iPhone）：扫码配对；在电脑旁通话，说完到她开口约 1.5 秒（安卓）；手机上的她能回话。
- 2026-10-24 改成以聊天为主（语音消息、照片、手机声纹、离开电脑时她的话发到手机、安卓常驻），还没在真机上试过。
- 手机上的她很慢，一句话要二十多秒：7B 的 Q4_K_M 有 4.7 GB，手机剩下的内存放不下，读她的输入时一直在从闪存补页。
- 外出（不在一个网里）还没在真机上试过：电脑要有 IPv6，或者公网 IPv4 加端口映射。
- 离线时和手机上的她说的话，回家后还不会进电脑上她的记忆；离线时她的声音是手机系统的朗读，也只能打字。

## 许可证

[AGPL-3.0-only](LICENSE)，和 WifeBody、AddonSDKWife 一样。应用里带的第三方组件（llama.cpp、Opus、Bouncy Castle、ZXing 等）和它们的许可证原文在 [Android](android/app/src/main/assets/THIRD_PARTY_NOTICES.txt) 和 [iOS](ios/App/THIRD_PARTY_NOTICES.txt) 的 `THIRD_PARTY_NOTICES.txt` 里，会打进 APK 和 IPA。
