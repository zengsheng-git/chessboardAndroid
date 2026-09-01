# 弈眼（YiEye）构建打包指南

本文档说明如何从零开始把本项目构建打包成可安装的 APK。适用于刚克隆代码的新环境。

> 项目简介与功能说明见 [README.md](README.md)，本文只讲**构建与打包**。

---

## 一、环境要求

| 组件 | 版本要求 | 说明 |
|---|---|---|
| JDK | **17 或 21** | Gradle 会按 `gradle/gradle-daemon-jvm.properties` 自动获取 JetBrains JDK 21 给守护进程使用（首次构建自动下载，约 610 MB） |
| Android SDK | `compileSdk 36` | 需要 `platforms;android-36`、`build-tools;36.0.0`、`platform-tools` |
| Android NDK | **r27 及以上** | 用于编译 Pikafish 原生库；**首次构建时 AGP 会自动下载所需版本**（实测自动装了 28.2.13676358，无需手动准备） |
| CMake | **3.22.1** | `app/build.gradle` 中指定，需通过 SDK Manager 安装 |
| Gradle | 9.1.0 | 由 Wrapper 自动下载，无需手动安装 |
| Android Studio | 可选 | 有则更方便；没有也能纯命令行构建 |

**目标设备限制：仅支持 `arm64-v8a` 真机**（`abiFilters` 写死了）。模拟器（x86/x86_64）和 32 位机型无法运行。

**磁盘与网络预估**：下载约 3.5 GB，磁盘占用约 12 GB，首次构建 8~15 分钟（大头在 Pikafish 的 `-O3 -flto` 原生编译）。

---

## 二、安装 Android SDK（命令行方式）

已有 Android Studio 的可跳过本节。

```powershell
# 1. 下载 cmdline-tools（约 130 MB）
#    最新版地址见 https://developer.android.com/studio#command-line-tools-only
Invoke-WebRequest `
  -Uri "https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip" `
  -OutFile "$env:TEMP\cmdline-tools.zip"

# 2. 解压到 SDK 目录（注意：必须是 cmdline-tools\latest\bin 这个布局）
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
Expand-Archive "$env:TEMP\cmdline-tools.zip" -DestinationPath "$sdk\cmdline-tools" -Force
Rename-Item "$sdk\cmdline-tools\cmdline-tools" "latest"

# 3. 设置 JAVA_HOME（务必指向 JDK 17+，不要用 JDK 8）
$env:JAVA_HOME = "E:\jdk-17.0.1"        # 改成你的实际路径

# 4. 接受许可并安装组件
$sdkmgr = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
$y = ("y`r`n" * 40)
$y | & $sdkmgr --sdk_root="$sdk" --licenses
foreach ($p in 'platform-tools','platforms;android-36','build-tools;36.0.0','cmake;3.22.1') {
    & $sdkmgr --sdk_root="$sdk" $p
}
# NDK 可以不手动装，首次构建 AGP 会自动下载匹配版本
```

**最后一步**：在项目根目录创建 `local.properties`（该文件已被 `.gitignore` 忽略，不会提交）：

```properties
sdk.dir=C:/Users/Administrator/AppData/Local/Android/Sdk
```

> 注意用正斜杠 `/`，Windows 反斜杠在 properties 文件里需要转义。

---

## 三、构建 APK

### 方式 A：Android Studio

1. File → Open，选择本项目根目录
2. 等待 Gradle Sync 完成（会自动下载依赖并编译 Pikafish 原生库，耐心等待）
3. Build → Build Bundle(s) / APK(s) → Build APK(s)

### 方式 B：命令行

```bat
cd /d "你的项目目录"

set "JAVA_HOME=E:\jdk-17.0.1"
set "ANDROID_HOME=C:\Users\Administrator\AppData\Local\Android\Sdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"

call gradlew.bat assembleDebug --console=plain
```

**产物位置**：

```
app\build\outputs\apk\debug\yi_debug_<版本号>.apk
```

> 文件名由 `app/build.gradle` 里的 `androidComponents` 块定义，格式为 `yi_<构建类型>_<versionName>.apk`。

本项目根目录附带了一个现成的 `build.bat`（若随仓库发布），一键完成环境设置 + 打包。

---

## 四、签名说明（重要）

### Debug 版 —— 无需任何操作

`gradlew assembleDebug` 会自动使用 Gradle 生成的调试密钥（首次构建自动创建于 `~\.android\debug.keystore`），产出**已签名可直接安装**的 APK。

可以用 build-tools 里的工具验证：

```bat
build-tools\36.0.0\apksigner.bat verify --print-certs app\build\outputs\apk\debug\yi_debug_xxx.apk
```

输出 `CN=Android Debug` 即表示已签名成功。

### Release 版 —— 必须自备密钥

```61:68:app/build.gradle
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
            if (keystorePropertiesFile.exists()) {
                signingConfig signingConfigs.release
            }
        }
```

没有密钥时，Release 构建产出的是**未签名 APK，安装会报 `INSTALL_PARSE_FAILED_NO_CERTIFICATES`**。

如需打 Release 版，自签名密钥免费且 10 秒搞定：

```bat
keytool -genkey -v -keystore keystore\mykey.jks ^
  -keyalg RSA -keysize 2048 -validity 10000 -alias yieye
```

然后在项目根目录建 `keystore/keystore.properties`（已被 `.gitignore` 忽略）：

```properties
storeFile=../keystore/mykey.jks
storePassword=你的密码
keyAlias=yieye
keyPassword=你的密码
```

**密钥丢失后将无法覆盖升级**，请务必备份。个人自用打 Debug 版即可，无需这一步。

---

## 五、安装到手机

```powershell
# 1. 确认手机是 arm64（输出必须是 arm64-v8a）
adb shell getprop ro.product.cpu.abi

# 2. 手机开启 USB 调试后安装（-r 覆盖升级）
adb install -r app\build\outputs\apk\debug\yi_debug_xxx.apk
```

也可把 APK 拷到手机点开安装（需授予"安装未知应用"权限）。
**不要用微信/QQ 传 APK**，会给文件改名导致安装失败。

### 首次运行权限流程

1. 打开 App → 顶部黄色横幅提示 → 点「开启」去开**悬浮窗权限**
2. 返回 → 选择计算深度（默认 14）→ 点「启动分析服务」
3. 系统弹出**屏幕录制授权** → 允许 → App 自动退到后台
4. 打开象棋 App 对弈，悬浮窗实时显示中文推荐走法

建议在主界面点「电池优化设置」将 App 加入白名单，否则国产 ROM 会在几分钟后杀掉后台服务。

---

## 六、常见问题排查

### 1. `ERROR: JAVA_HOME is set to an invalid directory`

`JAVA_HOME` 必须指向 JDK 根目录（其下要有 `bin\java.exe`）。

**cmd 的经典陷阱**：写 `set JAVA_HOME=路径 && 其他命令` 时，`&&` 前的**空格会被吃进变量值**，导致路径尾部多一个空格而失效。务必写成 `set "JAVA_HOME=路径"`（带引号包裹整个赋值）。

### 2. Gradle 依赖下载卡死 / 报代理错误

本仓库的 `gradle.properties` **不得包含硬编码代理**（曾因 `127.0.0.1:7897` 导致所有下载卡死，已移除）。如需代理请配置在用户级 `~/.gradle/gradle.properties`，不要提交到仓库。

### 3. Gradle 发行包下载一直是 0 字节

`gradlew` 内置下载器偶尔会卡住。可手动预取后让 Wrapper 跳过下载：

```powershell
# 目录中的 hash 子目录名以实际报错信息/本地已生成目录为准
$dir = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.1.0-bin\<hash>"
Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-9.1.0-bin.zip" `
                  -OutFile "$dir\gradle-9.1.0-bin.zip"
```

（本项目 `gradle-wrapper.properties` 里配置了 SHA256 校验值 `a17ddd85...`，下载完整即可自动通过校验并解压。）

### 4. PowerShell 下载大文件极慢

`Invoke-WebRequest` 默认会渲染进度条，对大文件是 10~100 倍的性能损耗。先执行：

```powershell
$ProgressPreference = 'SilentlyContinue'
```

### 5. 只想快速验证能不能编译

```bat
gradlew.bat --version     # 只验证 Gradle + JDK 工具链
```

### 6. 编译 Pikafish 太慢

`app/src/main/cpp/CMakeLists.txt` 开启了 `-O3 -std=c++20 -flto`。首次构建可临时去掉 `-flto` 提速约一半，确认跑通后再加回来。

---

## 七、本次实测参考数据

| 阶段 | 耗时 / 大小 |
|---|---|
| cmdline-tools 下载 | 136 MB |
| SDK 组件（platform-tools + platform-36 + build-tools + cmake） | ~340 MB |
| NDK r27c | 2.25 GB / 7936 文件 |
| Gradle 9.1.0 发行包 | 128.3 MB（SHA256 与 wrapper 配置一致） |
| Gradle 依赖（AGP 9 + Compose + ONNX Runtime 等） | ~780 MB |
| JetBrains JDK 21（守护进程，自动获取） | 610 MB |
| AGP 自动补装 NDK 28.2.13676358 | 自动完成 |
| **完整首次构建** | **7 分 53 秒** |
| **产出 APK** | **yi_debug_0.0.2.apk / 106.7 MB** |

产物校验（`lib/arm64-v8a/libpikafish.so`）：
- ELF magic `\x7fELF` ✅ · ELF64 ✅ · `ET_DYN` ✅ · **EM_AArch64 (183)** ✅
- 剥离符号后 1.55 MB


我把之前的分析再核对一遍，确认哪些是真问题、哪些已经失效。先做几个精确验证。
核对完毕。先修正我之前的一个误判，然后给你确认过的、按优先级排好的问题清单。

---

## 先撤回一个误判

我之前预判 **AGP 9 会因 `outputFileName`/`capitalize()` API 变更而编译失败** —— **实际构建通过，未命中**。AGP 9.0.1 仍兼容这套 API。这条可以从问题清单里划掉。

---

# 一、确认存在的 Bug（影响结果正确性）

## 🔴 Bug 1：续着（ponder）的中文记谱是基于错误棋盘算的

```79:80:app/src/main/java/com/yieye/xiangqi/AnalysisService.java
                String chinaMove = (bestMove != null && bestMove.length() >= 4) ? Utils.fenToChina(this, sourceFen, bestMove) : getString(R.string.unknown);
                String chinaMove2 = (ponderMove != null && ponderMove.length() >= 4) ? Utils.fenToChina(this, sourceFen, ponderMove) : getString(R.string.none_ponder);
```

两次调用都传**同一个 `sourceFen`**。但 ponder 是「走了 bestMove 之后对方的应手」，它应该作用在**走完 bestmove 之后的棋盘**上。

后果分两种情况：
- ponder 的起点格在原始棋盘上是空的（比如我方刚走的那步吃掉了对方的子）→ `fenToChina` 里 `if (name == null || name.isEmpty()) continue;` 直接跳过，**返回原始 UCI 串**（如 `h7e7`）
- 起点格恰好有对方棋子 → 会算出中文，但**是错的棋子在走**

**修法**：`Utils.fenToChina(ctx, board, moves, redSide)` 本身就支持连续走子（内部会更新棋盘），只要把两个 move 放进同一个数组一次调用，再按空格拆开即可：

```java
String[] both = Utils.fenToChina(this, sourceFen, new String[]{bestMove, ponderMove}, redSide);
// 拆成 chinaMove / chinaMove2
```

## 🔴 Bug 2：识别不到任何棋子时，返回一个假的开局局面

```199:201:app/src/main/java/com/yieye/xiangqi/ChessBoardParser.java
        if (validPieces.isEmpty()) {
            return "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
        }
```

这是致命兜底。当 YOLO 一枚棋子都没检出（棋盘被遮挡、识别失败、切到别的 App）时，它返回**标准开局 FEN**。这个 FEN **能通过 `isValidFen` 校验**，于是引擎会认真分析一个根本不存在的局面，悬浮窗显示一条**假推荐**。

而且由于 `lastFen` 去重，假局面会一直占着位置，直到下次局面变化。**修法**：返回 `null`，让上层走"未识别"分支。

## 🔴 Bug 3：`mySide` 每帧被覆盖，朝向识别抖动会导致分析时机错乱

```239:243:app/src/main/java/com/yieye/xiangqi/AnalysisService.java
//                        if (mySide == null) {
                        mySide = fen.split(" ")[1];
                        LogUtil.i(TAG, getString(R.string.auto_identify_side) + ...);
//                        }
```

注释写着"首帧自动识别"，但保护被注释掉了，**每帧都重算**。目前能工作纯属巧合 —— 因为 `boardToFen` 的第三个参数 `nextPlayer` 恒等于 `redSide ? "w" : "b"`（是**朝向**不是行棋方），所以这个值恰好稳定。

一旦某帧 YOLO 把 `r_jiang` 判错导致朝向翻转，`mySide` 立即翻转，「只在我方回合分析」的过滤就反向了 —— **该分析的时候等，不该分析的时候空转**。

**修法**：加迟滞（连续 N 帧一致才切换），或恢复 `if (mySide == null)` 并提供手动设置入口。

## 🔴 Bug 4：棋盘裁剪缓存只增不清，可能永久卡死

```55:72:app/src/main/java/com/yieye/xiangqi/ChessBoardParser.java
        if (cachedCropRect != null) {
            try {
                int cropY = (int) cachedCropRect.top;
                int cropHeight = (int) cachedCropRect.height();
                if (cropY >= 0 && cropY + cropHeight <= imgHeight) {
                    return Bitmap.createBitmap(bitmap, 0, cropY, imgWidth, cropHeight);
                } else {
                    cachedCropRect = null;
                }
            } ...
```

`cachedCropRect` 只在**越界**时才失效，且全项目只有 `onDestroy` 里的 `clearCropCache()` 会清它（已确认仅这一处调用）。

后果：一旦某次识别成功缓存了坐标，之后如果棋盘**在屏幕上纵向移动了**（换 App、换棋局、页面滚动），裁剪框不再覆盖棋盘 → YOLO 检出棋子数为 0 → 触发 Bug 2 的假开局 FEN，**且永远无法自愈**，只能重启服务。

**修法**：当 `parse` 返回 null（未识别出局面）时也清空 `cachedCropRect`，让下一帧重新跑一次全图检测。

---

# 二、性能问题（体验影响最大）

## 🟠 整条分析链路跑在主线程

```36:36:app/src/main/java/com/yieye/xiangqi/AnalysisService.java
    private Handler handler = new Handler();
```

`new Handler()` 是主线程 Looper。`captureAndAnalyze()` 里同步执行：

- 全屏 Bitmap 解码（1080×2400 ≈ 10 MB）
- `smartCrop` 一次 YOLO 640×640 推理（缓存冷启动时）
- `parse` 再一次 YOLO 推理
- pHash + FEN 生成 + `compareBoard`

中低端机上单帧 **300–800 ms**，全卡在服务主线程。项目里**没有后台线程池**。引擎的 `go` 是异步的所以那部分没事，但推理和图像处理都在 UI 线程。

**修法**：换成 `HandlerThread`（改动最小，只改一行 Handler 构造 + 保证时序）或单线程 `ExecutorService`。这是**收益最大的一项**。

## 🟠 提前终止搜索的功能实际是空操作

```100:120:app/src/main/java/com/yieye/xiangqi/AnalysisService.java
        engineHelper.infoEvent = (cmd, infos) -> {
            if (stopTriggeredForCurrentAnalysis) return;
            try {
//                if (infos.containsKey("mate")) {
//                    if (Integer.parseInt(infos.get("mate")) > 0) {
//                        stopTriggeredForCurrentAnalysis = true;
//                        ...
//                    }
//                } else if (infos.containsKey("score")) {
//                    ...
//                }
            } catch (NumberFormatException ignored) {
            }
        };
```

整段被注释掉了。结果：
- `infoEvent` 是空函数
- `STOP_SCORE`、`stopTriggeredForCurrentAnalysis` 全是死变量
- **README 宣称的"发现绝杀/大优提前终止"没有生效**，每个局面都跑满 5 秒或满深度

已确认 `EngineHelper.handleOutputLine` 里 `mate` 的解析是**存在的**（`infos.put("mate", args[i+2])`），只是没人消费。恢复这段逻辑是可行的，且能明显省电、加快出结果。

---

# 三、健壮性 / 崩溃风险

| # | 位置 | 问题 | 修法 |
|---|---|---|---|
| 5 | `FloatWindowManager.show()` → `windowManager.addView()` | 悬浮窗权限在服务运行期间被撤销时抛 `BadTokenException`，**服务直接崩** | `show()` 内加 `Settings.canDrawOverlays()` 判断 + try-catch |
| 6 | `EngineHelper.startAnalyze()` | 不检查 `initialized`。引擎握手最长 5 秒，期间 `sendCommandJNI` 静默丢弃（`if (!g_uci) return;`）→ 这轮分析丢失，等 18 秒超时才恢复 | 开头加 `if (!initialized) return false;` |
| 7 | `MainActivity:109` | `registerReceiver(resultReceiver, filter, Context.RECEIVER_EXPORTED)` 且发送端 `sendBroadcast` 未 `setPackage()` —— 任何 App 都能伪造分析结果 | 加 `intent.setPackage(getPackageName())` + 改用 `RECEIVER_NOT_EXPORTED` |
| 8 | `MainActivity:187` | `spinnerDepth.getSelectedItem()` 在极端时序下可为 null → 拆箱 NPE | 加非空兜底 |
| 9 | `YoloV11Detector.postProcess` | `output[4 + c][i]` 直接索引，模型通道数 ≠ 19 时 AIOOBE | 校验 `output.length >= 4 + NUM_CLASSES` |

---

# 四、死代码与体积（不影响功能，但值得清）

| 项 | 大小/行数 | 说明 |
|---|---|---|
| `ImageHelper.java` | **237 行** | **全项目零引用**（已用 grep 确认）。其中的 `findImage` 是 O(W·H·w·h) 逐像素模板匹配，在手机上根本跑不动，是 C# 版残留 |
| `ChessRecognizer.java` | 134 行 | 仅在 `ChessBoardParser:131` 的**注释行**里被提到，其余方法全无用 |
| `Utils` 未用方法 | ~150 行 | `checkBoardValid`、`checkChessmanValid`、`mirrorFenLeftRight`、`mirrorFenRedBlack`、`point2Move`、`expendArea`、`restoreArea` —— **全部零调用**（已确认）。讽刺的是 `checkBoardValid` 正好是 Bug 2 需要的兵种位置合法性校验，写了却没用上 |
| `MainActivity.drawDetections()` | 22 行 | 零调用，`debugImageView` 也是 `visibility="gone"` |
| **Compose 全家桶** | ~7 个依赖 | `buildFeatures { compose true }` + activity-compose / compose-bom / material3 / ui-tooling 等，**项目零 Compose 代码**（主题是 XML 的 `Theme.MyApp`）。拖慢构建、增加体积 |
| **`middle.onnx`** | **27.3 MB** | `USE_YOLO_V11 = true`，只在 YOLOv11 加载失败时兜底 → 27 MB 常驻 APK |
| 无用权限 | — | `INTERNET`、`ACCESS_NETWORK_STATE`、`READ/WRITE_EXTERNAL_STORAGE` 全部未使用；`network_security_config.xml` 还开了 `cleartextTrafficPermitted="true"` |
| AdMob 残留 | — | Manifest 里有 AdMob `APPLICATION_ID`（且是 Google 公开测试 ID），但依赖不在 `build.gradle` |

**APK 现在是 106.7 MB**，砍掉 Compose + `middle.onnx` 能降到 **~70 MB**。

---

# 五、上架 / 合规

- **`targetSdk 34`**：Google Play 现要求 ≥35，上架会被拒。改 `targetSdk 35` 即可（代码里已有 Android 14 的 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 处理，兼容性风险不大）
- **`THIRD_PARTY_NOTICES.md`** 引用了 `VinXiangQi-1.4.0/LICENSE`，但该目录不在仓库里
- **电池优化按钮跳错页面**：`ACTION_APPLICATION_DETAILS_SETTINGS` 打开的是应用详情页，不是电池优化页，与按钮文案「电池优化设置」不符

---

# 六、建议的修复顺序

| 优先级 | 项 | 收益 | 改动量 |
|---|---|---|---|
| **P0** | Bug 2 假开局 FEN | 消除**最误导用户**的问题 | 1 行 |
| **P0** | Bug 4 裁剪缓存卡死 | 消除"识别永久失效" | 2 行 |
| **P0** | Bug 1 ponder 记谱 | 显示正确的续着 | ~5 行 |
| **P0** | #5 悬浮窗崩溃守卫 | 消除崩溃 | 3 行 |
| **P1** | 分析链路移后台线程 | **流畅度提升最明显** | ~10 行 |
| **P1** | 恢复提前终止 | 省电、出结果更快 | ~10 行 |
| **P1** | Bug 3 mySide 迟滞 | 避免时机错乱 | ~5 行 |
| **P2** | 清死代码 + 关 Compose + 删 onnx | **APK 106MB → ~70MB** | 删文件 |
| **P2** | #7 广播收窄 | 安全 | 2 行 |
| **P3** | targetSdk 35 | 上架需要 | 1 行 |

---

P0 四项都是小改动、零风险，改完可以直接用现成的 `build.bat` 重出包。要我动手吗？

- **A**：只修 P0（4 项，约 10 分钟，改完重打包给你）
- **B**：修 P0 + P1（6 项，约 30 分钟，包含性能优化）
- **C**：连 P2 一起做（瘦身到 ~70MB + 清理死代码）