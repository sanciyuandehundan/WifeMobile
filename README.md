# WifeMobile

她在手机上。两种情况：

- **在电脑旁**：手机当你的耳机和麦克风。你的声音进电脑上 Wife 的语音识别，她的声音从手机出来（手机做回声消除）。
- **不在电脑旁**：手机直接拨家里的电脑，和平时一样跟电脑上的她说话，她知道你在外面。连不上电脑时，可以叫醒**手机上的她**：同一个大脑（Qwen2.5-7B Q4_K_M + 说话 LoRA）在手机上跑，用电脑在通话时交给手机的角色卡和记忆。

不经过任何服务器：没有中转，没有 STUN。在家走局域网；外出时拨电脑的公网地址，电脑地址变了怎么办由电脑上选的方式决定：IPv6 直连（回家自动更新）、BT 网络（拨不通时查一次 DHT）、回家重新配对。协议见 [protocol.md](protocol.md)。

## 安全

- 配对码（二维码）只在电脑内存里，扫一次、5 分钟或关窗口就作废，以后也看不到。
- 链路密钥由两边的临时 X25519 密钥和配对码一起算出；电脑用 Windows DPAPI 存，手机用安卓 Keystore 加密存，哪儿都不显示。
- 每次通话再用临时密钥协商（前向保密），ChaCha20-Poly1305 加密，加解密都在两台设备上做。

## 目录

| 目录 | 内容 |
|---|---|
| `pc/` | 电脑端，Wife 的附属功能“手机”：托盘程序、配对窗口、UDP 18766、DHT 节点、把通话接到 Wife |
| `android/` | 安卓 App（Kotlin + Compose，原生部分是 libopus 和 llama.cpp） |

WifeBody 里配合它的只有两处：语音识别的 `/v1/mic/remote`（手机当麦克风）和附属接口的 `offline`（她的离线资料）。

## 电脑端

```powershell
cd pc
..\..\WifeBody\tools\python310\python.exe -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements.txt
.venv\Scripts\python.exe main.py --settings
```

第一次运行时自动和同级的 WifeBody 绑定，Wife 的“附属功能”页会出现“手机”。Windows 防火墙问起时允许 Python 使用网络。测试：`.venv\Scripts\python.exe -m pytest`。

## 安卓端

需要 JDK 21、Android SDK（platform 36、build-tools 36.1.0、NDK 29.0.14206865、CMake 3.31.6），`android/local.properties` 写 `sdk.dir`。原生库的源码放在 `android/third_party/`（不进 git）：

- `opus/`：libopus 1.5.2
- `llama.cpp/`：llama.cpp b10419（和 WifeBody 的 llama-server 同一版本）

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

单元测试读 `pc/tests/vectors.json`，确认手机和电脑算出一样的字节。

**手机上的她**：把电脑上的模型文件拷进手机的 `/sdcard/Android/data/io.wife.mobile/files/brain/`：`WifeBody/models/brain-candidates/qwen2.5-7b-instruct/` 下的两个 GGUF 分片，和 `WifeBrain/output/wife-v24-q4/epoch2/wife-chat.gguf`。

## 还没有的

- 离线时和手机上的她说的话，回家后还不会进电脑上她的记忆。
- 离线时她的声音是手机系统的朗读，不是她的声音；离线也只能打字。
