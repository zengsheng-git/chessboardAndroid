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

### 7. 无线调试连不上 / 反复掉线

无线调试的**连接端口每次会话都会变**：重新打开"开发者选项 → 无线调试"主界面，查看新的
"IP 地址:端口"后执行 `adb connect <ip:端口>` 即可（配对关系保留，无需重新配对码）。
授权弹窗点过"拒绝"的话，需在开发者选项里"撤销 USB 调试授权"后重插重试。

### 8. git push 报 Connection was reset

走本机代理推送（端口以实际代理软件为准，仅本次命令生效）：

```bash
git -c http.proxy=http://127.0.0.1:7890 push
```

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
| **当前版本（0.0.18，含识别架构重构）** | yi_debug_0.0.18.apk |

产物校验（`lib/arm64-v8a/libpikafish.so`）：
- ELF magic `\x7fELF` ✅ · ELF64 ✅ · `ET_DYN` ✅ · **EM_AArch64 (183)** ✅
- 剥离符号后 1.55 MB

---

## 八、识别与分析引擎说明（0.0.18 起，重要）

> 本节记录 0.0.18 重构后的分析架构、识别方案，以及本轮排查解决的全部问题。
> 改动细节见 git 提交 `2b50f1c`。

### 8.1 架构（对齐桌面端 chessboard 原型）

- **单线程阻塞式分析循环**：截屏 → 识别 → 状态机分类 → 阻塞式引擎搜索，
  全部串行在一条线程上。引擎搜索 `searchSync`（发出 go 后等 bestmove 才返回），
  彻底移除旧版"异步 bestmove 回调 + isAnalyzing 标志 + 18s 看门狗"的竞态机制。
- **引擎为进程级单例**：跨监听会话复用，停止监听只中止在途搜索、不杀引擎。
  （native 回调是全局注册的，按会话销毁重建 EngineHelper 会导致 bestmove 回给
  已销毁的旧实例——新会话搜索全部超时。）
- **截屏缓存最近一帧**：ImageReader 是推式的，画面静止时不产新帧；缓存使循环
  始终"看得见当前屏幕"，与桌面端拉式截屏语义等价。
- **心跳看门狗**：native 推理挂死 30s 自动 dump 线程堆栈、重建检测器并换线程继续。

### 8.2 识别方案（关键决策）

- **模型回退到 `middle.onnx`**——它与桌面端 chessboard 的 `large.onnx` 是**同一文件**
  （MD5 一致），是桌面端实战验证过的稳定模型。此前切到 `yolov11.onnx` 后，
  在腾讯象棋的将军高亮/装饰环下频繁丢子、棋子变异、幻影，
  是本轮大部分"轮次错报/错误提示"的识别层根源。
- 置信度门槛 **0.7**（与桌面端同值）；新增**每类棋子数量上限**过滤
  （车马炮×2、士象×2、兵×5、将帅×1），高亮/装饰误检的幻影棋子在此被结构性丢弃。
- **将/帅在九宫格内放宽置信度至 0.2**：将军高亮与装饰环会拉低王类置信度；
  仅接受九宫格内、格位为空、同侧无王的位置。
- 手机采集域与 PC 不同（棋子更小、高亮更多），若出现大面积漏检，
  优先调整 `YoloV5Detector.CONF_THRESHOLD`，不要动模型。

### 8.3 行棋方推断规则

1. 成对变化（一空一占）= 一步棋，走子方 = 消失格棋子的颜色（上一稳定帧识别过，可靠）；
2. 多格变化且双方子数不变 = 两步并一帧的偶数步，行棋方不变；
3. 识别噪声帧（半步、幻影、不成对、带丢子的合并帧）一律**不分析、不锚定**，
   并回滚感知哈希让下一拍重试同一画面；连续 8 拍噪声才按底部方兜底出一次招；
4. 丢王修复带一致性约束：王以外部分恰好是一步干净棋才补回；王位记忆
   （SharedPreferences 持久化）跨重锚定、跨会话生效；
5. INITIAL 锚定帧必须通过合法性校验（双王、子数上限），否则不锚定、显示"等待识别"；
6. 出招先验货：搜索返回时若对手已行棋，丢弃过期提示。

### 8.4 本轮解决的问题清单

| 症状 | 根因 | 修复 |
|---|---|---|
| 悬浮窗"正在分析"永挂 | 并发搜索 / native 回调错位 / 推理挂死 | 阻塞式搜索 + 引擎单例 + 看门狗自愈 |
| 轮次错报（红/黑颠倒） | 识别噪声帧被当真实走子推断 | 干净走子才推断；噪声帧跳过；合并帧奇偶/吃子规则 |
| 提示招法明显错误 | 缺王/幻影局面被分析 | 丢王修复带一致性约束；幻影帧拦截 |
| 旧提示长时间挂屏不更新 | 过期提示直接显示 + 帧饥饿 | 出招先验货丢弃过期提示；截屏缓存消除帧饥饿 |
| 重开会话后搜索全超时 | 引擎回调错位（实例销毁） | 引擎进程单例，跨会话复用 |
| "等待识别"永挂 | 非法帧被锚定为基线 | INITIAL 合法性校验 + 重试 |

### 8.5 已知边界

- **残局挑战页**：黑将带装饰环，模型可能识别不出 → 悬浮窗诚实显示"等待识别"
  （属模型训练数据缺失，根治需用带装饰的棋盘截图微调模型）；
- **复盘页轮次标签**：浏览历史局面时"轮到谁"在画面上不存在，App 按底部方猜测，
  进入复盘后翻一步即自动纠正；
- 无云库辅助，每步搜索最长 5s（`AnalysisService.ENGINE_STEP_TIME_SEC`）。
