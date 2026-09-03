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






.\gradlew.bat assembleDebug --console=plain