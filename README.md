<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.webp" width="112" alt="Homura">
</p>
<h1 align="center">Homura</h1>
<p align="center"><b>让插着 SIM 卡的备用机，安安静静地把电话和短信转发出去。</b></p>
<p align="center">
  <a href="https://github.com/KiriKira/Homura/releases">下载 APK</a> ·
  <a href="#-初次连接">快速连接</a> ·
  <a href="#-两种模式按需开启">短信 vs 通话模式</a> ·
  <a href="docs/">文档</a>
</p>
<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%20(API%2026%2B)-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/SMS-No%20Root%20Needed-success?style=flat-square" alt="SMS">
  <img src="https://img.shields.io/badge/Voice-WebRTC%20%2B%20Magisk-blue?style=flat-square" alt="Voice">
  <img src="https://img.shields.io/badge/Version-1.0.1-ff69b4?style=flat-square" alt="Version">
  <img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="MIT License">
</p>

**Homura** 装在插有实体 SIM 卡的备用机上。它负责守住移动网络，把收到的短信和来电通过自建服务端（[SoulGem](https://github.com/KiriKira/SoulGem) / [GriefSeed](https://github.com/KiriKira/GriefSeed)）实时转给你的主力手机 [Madoka](https://github.com/KiriKira/Madoka)，并代为发短信或拨打电话。

## ✨ 两种模式，按需开启

- **纯短信模式（无需 Root）：** 直接安装普通 APK 即可使用。只申请电话状态与收发短信权限，不碰录音、拨号角色或 Root；每条短信严格按指定 SIM 卡槽发送，绝不擅自回落默认卡或重复乱发。
- **远程通话模式（需 Root / Magisk）：** 全面采用原生 WebRTC 音频，彻底告别 SIP 与 Asterisk。通过 Magisk 权限与受限 system-UID broker 采集/注入蜂窝通话音频，把清晰的语音直接桥接到主力机。
- **多服务器同时在线：** 可同时连接多台独立服务端节点；同一条待发短信或去电无论从哪个节点进来，本地账本都会确保只执行一次。
- **开箱即用的三栏界面：** 采用与 Madoka 一致的「状态 / 记录 / 设置」界面，引导式完成配对、逐卡核对 SIM、权限检查与备份导出（支持密码加密、JSON 与 XML）。

## 🚀 初次连接

1. **安装网关端：** 从 [Releases](https://github.com/KiriKira/Homura/releases) 下载安装（正式版共用固定签名与 versionCode，支持随时覆盖升级或回退）。
2. **配对服务器：** 打开「状态」页的引导卡片，在「设置 → 服务器与配对」中填入服务器地址和 `gateway` 角色的一次性配对码（内网或 Tailscale 可用 `http://`）。
3. **核对 SIM 卡槽：** 进入「设置 → SIM 卡」点击「同步 SIM 列表」，逐张确认本机卡槽与服务端记录对应后保存。
4. **开启后台保活：** 在「设置 → 后台运行与权限」打开后台服务并允许通知；如需长期熄屏稳定收发，建议豁免电池优化。
5. **（可选）开启通话：** 如需接打电话，先在 Magisk 中刷入 Release 里的 `Homura-Magisk.zip`（内含同一签名的 APK）并重启，再在「状态」页点击远程通话的「诊断并启动」，按提示完成默认电话应用与音频路径检查即可。

## 🔧 自行构建与发布

需要 JDK 17+ 与 Android SDK 35，最低支持 Android 8.0（API 26）。包名 `com.callagent.gateway`。

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

- **产物：** 调试包在 `app/build/outputs/apk/debug/app-debug.apk`；`./build.sh` 额外打出 Magisk 模块（`gateway-magisk.zip`）。
- **别用卸载重装解决签名冲突：** 会丢掉本机未决的发送账本。所有正式版共用同一 versionCode，可直接覆盖升级或回退。

更多文档：[Magisk 适配说明](docs/magisk-runtime.md) · [设备验收状态](docs/devices.md) · [多节点配置](docs/mesh.md) · [短信备份与归档](docs/sms-backup.md) · [界面与后台说明](docs/android-ui-and-background.md)

测试通话内嵌音频为 muque「いいんじゃない」（`app/src/main/res/raw/test_call_music.m4a`），仅用于测试，版权归原作者所有。

## 📄 许可证

[MIT](LICENSE)
