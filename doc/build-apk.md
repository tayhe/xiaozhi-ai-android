# GitHub Actions 自动化编译与 APK 获取指南

本项目采用 **GitHub Actions** 进行全自动化云端编译，无需在本地电脑配置耗费巨大磁盘空间的 Android NDK、CMake 和 Android SDK 构建环境。

---

## 1. 为什么采用 GitHub Actions 编译？

1. **零环境配置**：GitHub 官方 `ubuntu-latest` Runner 默认已预装 JDK 17、Android SDK（API 34/35）、NDK（r26/r27）与 CMake 3.22.1。
2. **支持 NDK 原生编译**：本项目包含 C++ 原生模块（Opus 1.3.1 编解码器与 Prefab 集成），GitHub Runner 能够无缝编译 `arm64-v8a` 等目标架构动态库。
3. **真机极速交付**：每次向 `main` 分支提交代码后，云端在 2~3 分钟内自动生成 Debug APK，手机可直接下载安装。

---

## 2. 工作流配置文件

工作流配置文件位于：[`.github/workflows/build-apk.yml`](../.github/workflows/build-apk.yml)

触发机制：
* **自动触发**：当向 `main` 分支推送代码（`git push`）或提交 Pull Request 时自动运行。
* **手动触发**：支持在 GitHub 仓库页面的 **Actions** 标签下点击 **Run workflow** 随时触发。

---

## 3. 编译流程与 APK 获取步骤

### 步骤一：推送代码
在本地修改或添加功能后，提交并推送到 GitHub 仓库：
```bash
git add .
git commit -m "feat: your changes"
git push origin main
```

### 步骤二：查看编译状态
1. 打开浏览器进入你的 GitHub 仓库：[`https://github.com/tayhe/xiaozhi-ai-android`](https://github.com/tayhe/xiaozhi-ai-android)。
2. 点击仓库顶部的 **Actions** 标签页。
3. 找到名为 **Build Android APK** 的最新流水线记录，点击可查看各步骤（Gradle 构建、NDK 编译）的实时控制台日志。

### 步骤三：下载与安装 APK
1. 流水线显示绿色对勾（构建成功）后，点击进入该次运行详情。
2. 页面底部为 **Artifacts（构建产物）** 区域，点击下载 **`xiaozhi-ai-debug-apk`**。
3. **安装到小米 6X**：
   * 可直接使用**小米 6X 自带浏览器**登录 GitHub 网页下载并解压安装。
   * 或下载到电脑后，通过 ADB 命令行直接推送到手机：
     ```bash
     adb install -r app-debug.apk
     ```

---

## 4. 连接自建小智服务端（Instabox）

在小米 6X 上安装启动后，首次使用在设置中配置服务端连接信息：
* **WebSocket 对话端点**：
  ```text
  wss://8989-agqw4kesptakygogg4l7hqpaei.instabox.tech/xiaozhi/v1/
  ```
* **OTA / 激活端点**（如使用自建 OTA）：
  ```text
  https://8989-agqw4kesptakygogg4l7hqpaei.instabox.tech/xiaozhi/ota/
  ```
