# 老爸下象棋

面向 Android 15 大屏设备的中国象棋应用，默认针对 14 寸平板横屏体验做了放大布局和全屏优化。

## 功能

- Android 15 目标版本：`compileSdk 35` / `targetSdk 35`
- 默认中级 AI，支持入门 / 中级 / 高手难度
- 支持难度切换：入门 / 中级 / 高手
- 双方落子音效
- 机器人落子语音播报
- Android 通知播报支持
- 悔棋、新局、提示
- 自动保存并恢复当前对局
- 老人友好大字号、大按钮、棋盘优先布局
- 棋盘在横屏平板上按全高度铺满，减少上下浪费空间

## 技术实现

- UI：Kotlin + Jetpack Compose + Material 3
- 引擎：90 格整数棋盘表示、合法走法生成、将军检测、局面评估、置换表
- 搜索：迭代加深 Negamax / Alpha-Beta，配合 PVS、Aspiration Window、Killer Move、History Heuristic、LMR
- 音频：落子音效 + 内置机器人语音片段
- 语音：优先设备中文 `TextToSpeech`，失败时回退到内置语音片段
- 通知：`NotificationCompat`

## 目录

- `app/src/main/java/com/decli/chinesechess/game`：规则、AI、存档、ViewModel
- `app/src/main/java/com/decli/chinesechess/ui`：Compose 界面、通知、语音、音效
- `docs/PRD.md`：产品需求文档
- `docs/system-design.md`：系统设计方案
- `.github/workflows/android-apk.yml`：APK 构建和发布流程

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
./gradlew testDebugUnitTest
./gradlew assembleRelease
```

Release APK 产物路径：

```text
app/build/outputs/apk/release/app-release.apk
```

## 设计说明

- 默认锁定横屏，更适合 14 寸平板把棋盘高度拉满。
- AI 默认执黑，用户执红先手。
- 高难度依旧受时间/节点限制，不会无限搜索。
