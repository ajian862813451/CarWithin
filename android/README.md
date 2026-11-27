# CarWithin Android

**简体中文** · [English](README.en.md) · [CarWithin 项目首页](../README.md)

CarWithin 在同一台 Android 手机上接收系统 CarLife 画面，支持手机预览、触控回传和 Wi-Fi Direct 仪表投屏。

## 下载与安装

从 [GitHub Releases](https://github.com/xmz28/CarWithin/releases) 下载带有 Android APK 附件的版本，在手机上打开 APK 并按系统提示安装。最低安装目标为 Android 8.0（API 26）。同机 CarLife 还需要手机自带兼容的车联组件，首版以 OPPO Car+ 为测试环境。其他手机、系统版本和 Car+ 版本需要独立验证。

## 开始使用

1. 打开手机 Wi-Fi，让仪表进入其投屏或导航互联模式。
2. 在 CarWithin 首页打开「当前输出」，选择仪表类型，扫描并连接自己的仪表。首次扫描按系统提示授予附近设备或位置权限。
3. 回首页点「启动本机 CarLife」。进入 Car+ 后可回 CarWithin 查看界面或切换全屏。应用会向已连接的仪表发送画面，后续启动也会尝试重连上次成功连接的仪表。
4. 无画面时，从「当前输出」先测试动态测试图。使用结束后，在首页停止 CarLife。

「设置 → 画面帧率」可选 20、25 或 30 FPS，用于本机 CarLife 和九号 5 寸。实际帧率取决于手机和仪表。其他仪表沿用各自的投屏预设。

## 兼容性

| 设备或功能 | 验证范围 |
|---|---|
| OPPO PHB110，Android 16 | 已验证本机 CarLife 出画与触控 |
| 九号 KZ110 5 寸 | 已验证 OPPO 同机 CarLife 到仪表出画 |
| 九号 7 寸、九号 4.3 寸 | 实验模式，待对应仪表验证 |
| 台铃 4.3 寸、极核 6.2 寸 | 已接入协议预设，待 CarWithin 真机验证 |

## 从源码构建

用 Android Studio 打开本目录。需要 JDK 21 和 Android SDK 37。首次构建由 Gradle Wrapper 下载对应版本和依赖。

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Linux 或 macOS 使用 ./gradlew，参数相同。Debug APK 位于 app/build/outputs/apk/debug/。正式发行包使用发布者自己的发行签名；应用版本和构建号定义在 app/build.gradle.kts。

Android 的「关于 → 检查更新」从仓库 Latest 读取 update.json。独立发布且不标记 Latest 的 Android 包请从 Releases 手动下载。

代码按仓库的 [AGPL-3.0](../LICENSE) 发布。复用模块和第三方文本见 [Android 第三方声明](THIRD_PARTY_NOTICES.md)。
