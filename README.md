# 象棋乐斗

面向 Android 15 大屏设备的中国象棋应用，默认针对 14 寸平板横屏体验做了放大布局和全屏优化。

## 功能

- Android 15 目标版本：`compileSdk 35` / `targetSdk 35`
- 默认中级 AI，对应有限时、有限节点的迭代加深 Alpha-Beta 搜索
- 支持难度切换：入门 / 中级 / 高手
- 双方落子音效
- 机器人落子幽默语音播报
- Android 通知播报支持
- 悔棋、新局、提示
- 老人友好大字号、大按钮、棋盘优先布局
- 棋盘在横屏平板上按全高度铺满，减少上下浪费空间

## 技术实现

- UI：Kotlin + Jetpack Compose + Material 3
- 引擎：90 格整数棋盘表示、合法走法生成、将军检测、局面评估、置换表
- 搜索：迭代加深 Negamax / Alpha-Beta，按难度限制时间和节点，避免高难度长时间卡死
- 音频：`ToneGenerator`
- 语音：`TextToSpeech`
- 通知：`NotificationCompat`

## 目录

- `app/src/main/java/com/decli/chinesechess/game`：规则、AI、ViewModel
- `app/src/main/java/com/decli/chinesechess/ui`：Compose 界面、通知、语音、音效
- `.github/workflows/android-apk.yml`：APK 构建流程

## 本地构建

1. 安装 JDK 17+。
2. 安装 Android SDK，并确保含有：
   - `platforms;android-35`
   - `build-tools;35.0.0`
   - `platform-tools`
3. 在项目根目录写入 `local.properties`：

```properties
sdk.dir=D:\\AndroidSdk
```

4. 执行：

```bash
./gradlew test
./gradlew assembleDebug
```

调试 APK 产物路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 设计说明

- 默认锁定横屏，更适合 14 寸平板把棋盘高度拉满。
- AI 默认执黑，用户执红先手。
- 高难度依旧受时间/节点限制，不会无限搜索。

