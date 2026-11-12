# EasyPT Lite

最小可用的 adb / fastboot 图形界面（Android 手机版）。代码基线是 Easy-platform-tools-GUI **v1.9.0** 的 Java 手机端，
改成 Gradle 工程，并把 Compose 版修好的实现搬了过来。

## 功能

- 三种模式：OTG 主机（线刷另一台手机） / 本机 root / 遥控 PC
- OTG fastboot：设备识别（三轮匹配）、读分区表、刷入镜像、线刷 ROM、擦除分区、重启系列、进入 EDL
- 线刷 ROM：解析 `flash_all` 脚本（多个脚本会让你选，带 `except_storage` 的标「保数据」），
  逐分区进度、失败自动重连重试、机型校验、结束统计
- 大分区分块刷写：按 `getvar:max-download-size` 切块，sparse 镜像按块重新打包
- 本机 root：检测 root、备份基带 / ROM、刷入镜像
- 遥控 PC：和桌面端配合
- 日志：自动滚到最新、清空、存日志、历史
- 两种界面：竖屏（大按钮）/ 沉浸刷机（横屏，桌面版布局，隐藏状态栏），首次进入选择
- 设置：配色、字号、语言、遥控地址/配对码/默认分区、模拟线刷（假包，不接设备）

## 构建

需要 JDK 17 与 Android SDK（platform 37 / build-tools 37.0.0），`local.properties` 写自己的 `sdk.dir`：

```bash
gradle :app:assembleDebug
```

## 说明

- 包名 `com.easypt.gui.lite`，可与 Compose 版（`com.easypt.gui.compose`）共存
- 签名用 `app/build.gradle.kts` 里的 `signingConfigs.easypt`，没有该 keystore 就删掉那一段