# 云编译完整教程

> 目标：在这台 **1.99GB 内存、没有 JDK/SDK/Android Studio** 的容器里，
> 造出一个能装到手机上的 APK。
>
> 办法：**代码放 GitHub，编译交给 GitHub 的服务器。**

---

## 目录

- [0. 先搞懂原理](#0-先搞懂原理)
- [1. 准备 GitHub 账号](#1-准备-github-账号)
- [2. 建立仓库](#2-建立仓库)
- [3. 上传代码](#3-上传代码)
- [4. 添加构建脚本](#4-添加构建脚本)
- [5. 触发并观察构建](#5-触发并观察构建)
- [6. 取回 APK](#6-取回-apk)
- [7. 手机安装与配置](#7-手机安装与配置)
- [8. 以后怎么改代码重新构建](#8-以后怎么改代码重新构建)
- [9. 排错手册](#9-排错手册)
- [10. 省事路线：让助手直接推代码](#10-省事路线让助手直接推代码)

---

## 0. 先搞懂原理

### 整条链路长这样

```
① 你的容器                ② GitHub 仓库            ③ GitHub 的服务器
C:\Users\ADMIN\Desktop\    github.com/              （临时 Ubuntu 虚拟机
   YoloOverlay              你的名字/YoloOverlay      4核 / 16GB / 用完就删）
        │                        │                          │
        │  上传一次文件           │  检测到 push             │
        └───────────────────────>├─────────────────────────>│
                                 │                          │ 装 JDK17
                                 │                          │ 导出 tflite 模型
                                 │                          │ 下载 Gradle 8.7
                                 │                          │ gradle assembleDebug
                                 │                          │
                                 │<─────────────────────────┤
                                 │  把 APK 存成 Release      │
                                 │                          │
        ④ 你的手机 <──────────────┘                          ✂ 虚拟机销毁
        点链接下载 app-debug.apk
```

### 关键概念（只要 4 个）

| 概念 | 说人话 |
|---|---|
| **Workflow（工作流）** | 一个 YAML 文件，放在 `.github/workflows/` 里。**就是你写的"编译配方"** |
| **Runner（运行器）** | GitHub 临时开的一台 Ubuntu 虚拟机。免费、干净、跑完就删 |
| **Step（步骤）** | 配方里的一条条指令。按顺序执行，任何一条失败后面就不跑了 |
| **Trigger（触发）** | 什么时候开始跑。我们配了两种：push 代码时 / 手动点按钮 |

### 为什么这样能绕过 2GB 限制

因为**编译根本不在你这台机器上发生**。

你这边只做两件极轻的事：
1. 把一堆文本文件（总共约 82 KB）传上去
2. 从网上下一个约 10 MB 的 APK

**真正吃内存的活（Gradle、Kotlin 编译器、TensorFlow、dex 打包）全部在 16GB 的 runner 上跑。**
你的容器从头到尾没运行过一个编译进程。

### 顺带解决了模型问题

配方里有一句 `pip install ultralytics` —— 连 **torch 那 1GB 也是在 runner 上下**，
你本地完全不用装。模型导出后直接落进 `assets/`，紧跟着就被打进 APK。

---

## 1. 准备 GitHub 账号

已经有账号 → 跳到第 2 步。

1. 打开 https://github.com/signup
2. 填邮箱、密码、用户名（用户名会出现在仓库地址里，取个好记的）
3. **⚠️ 一定要完成邮箱验证**（去邮箱点确认链接）
   > 没验证邮箱的账号 **Actions 是灰色的、点不动**。这是最常见的"为什么我找不到 Actions 标签"。

---

## 2. 建立仓库

1. 打开 https://github.com/new
2. 填写：

   | 字段 | 填什么 |
   |---|---|
   | Repository name | `YoloOverlay` |
   | Description | 随便，可留空 |
   | Public / Private | **选 Public** |
   | Add a README file | ❌ **不要勾** |
   | Add .gitignore | ❌ **不要勾** |
   | Choose a license | ❌ **不要勾** |

3. 点 **Create repository**

> **为什么 Public？** Public 仓库的 Actions 是**无限免费**的。Private 每月送 2000 分钟，
> 一次构建约 6 分钟，其实也够用 300 多次 —— 但 Public 省心，而且这个项目没什么好藏的。
>
> **为什么都不勾？** 我们要传自己的 README 和 .gitignore。如果这里勾了，
> GitHub 会先建一个 README，之后你上传同名文件就会冲突。**保持空仓库最干净。**

建完后你会看到一个空仓库页面，中间有一行提示：*"Quick setup — if you've done this kind of thing before"*。

---

## 3. 上传代码

### 完整文件清单（对照检查用）

要传的是 `C:\Users\ADMIN\Desktop\YoloOverlay` 里的**内容**，一共 21 个可见文件：

```
build.gradle.kts                                   209 B
settings.gradle.kts                                331 B
gradle.properties                                  236 B
README.md                                        7,975 B
BUILD-CLOUD.md                                   6,731 B
app/build.gradle.kts                             1,544 B
app/proguard-rules.pro                              75 B
app/src/main/AndroidManifest.xml                 1,440 B
app/src/main/assets/README.md                      674 B
app/src/main/java/com/example/yolooverlay/
        CocoLabels.kt                            3,376 B
        Detection.kt                               478 B
        FloatingButtonView.kt                    2,056 B
        MainActivity.kt                         12,457 B
        OverlayService.kt                       19,619 B
        OverlayView.kt                           6,847 B
        ScreenUtils.kt                           1,416 B
        YoloDetector.kt                          9,817 B
        YoloPipeline.kt                         11,151 B
app/src/main/res/drawable/ic_launcher.xml          676 B
app/src/main/res/values/strings.xml                 80 B
tools/export_yolov8.py                           2,538 B
```

> `.gitignore`（106 B）和 `.github/` 是隐藏的，**这一步故意不传**，原因见下。

### 操作步骤

1. 在空仓库页面点 **uploading an existing file** 这个链接
   （或者 **Add file → Upload files**）
2. 打开 Windows 资源管理器，进 `C:\Users\ADMIN\Desktop\YoloOverlay`
3. **框选里面所有的东西**（Ctrl+A），**注意是选里面的内容，不是选 `YoloOverlay` 这个文件夹本身**
4. 拖进浏览器的上传区
5. 等进度条走完。下面会列出一长串文件路径
6. 往下滚，Commit changes 的说明框里写 `init`，点 **Commit changes**

### ⚠️ 这一步最容易踩的坑

| 坑 | 现象 | 解决 |
|---|---|---|
| 拖了 `YoloOverlay` 文件夹本身 | 仓库里变成 `YoloOverlay/app/...` 多一层 | 重新拖，或者 Git 里手动删掉那层 |
| `.github` 没传上去 | 正常，故意的，下一步单独建 | 看第 4 步 |
| 浏览器拖拽没反应 | 某些浏览器对文件夹拖拽支持不好 | 改用 **Add file → Upload files** 按钮，再拖 |
| 传完发现少文件 | 云构建报 `Could not find xxx` | 对照上面的清单补传 |

### 传完后应该看到什么

仓库根目录应该长这样：

```
app/
tools/
BUILD-CLOUD.md
README.md
build.gradle.kts
gradle.properties
settings.gradle.kts
```

点进 `app → src → main → java → com → example → yolooverlay`，应该看到 **9 个 .kt 文件**。
**如果少于 9 个，后面一定编译失败**，现在就补传。

---

## 4. 添加构建脚本

### 为什么要单独建

`.github` 以点开头。**Windows 资源管理器默认隐藏点开头的文件夹**，所以第 3 步的拖拽
很可能根本看不到它、传不上去。用网页编辑器建反而更快更稳。

（想让它显示出来也行：资源管理器 → 查看 → 显示 → 隐藏的项目。）

### 操作步骤

1. 回到仓库主页，点 **Add file → Create new file**
2. 在文件名输入框里**直接输入完整路径**（打 `/` 会自动建目录）：

   ```
   .github/workflows/build-apk.yml
   ```

3. 打开本地这个文件（用记事本）：

   ```
   C:\Users\ADMIN\Desktop\YoloOverlay\.github\workflows\build-apk.yml
   ```

4. **全选（Ctrl+A）→ 复制（Ctrl+C）**
5. 回到浏览器，粘贴到内容区
6. 往下滚，点 **Commit changes**

> **注意**：复制时**不要**带 BOM 或多余空行。记事本保存的原文件本身没问题，
> 直接复制粘贴即可。如果粘贴后第一行变成了 `锘縩ame: Build APK` 这种乱码，
> 用 VS Code 或 Notepad++ 重新打开再复制。

### 提交后会立刻发生什么

这个 commit 也是往 `main` 分支 push，而我们配置里的触发器正是
`on: push: branches: [main, master]` —— **所以它会自动开始第一次构建**。

---

## 5. 触发并观察构建

### 进入构建页面

1. 点仓库顶部的 **Actions** 标签
   > 如果没有 Actions 标签 → 回第 1 步检查邮箱验证
   > 如果提示要启用 → 点那个绿色按钮启用

2. 左边栏会看到 **Build APK**（来自你的 yml 第一行 `name: Build APK`）

3. 右边会有一条正在跑（黄色转圈）的记录，标题是 commit message

4. **点进去**，再点左边那个 **build** 方块，就能看到逐步的实时日志

### 手动再触发一次

Actions → 左边选 **Build APK** → 右边点 **Run workflow** 下拉 → 绿色 **Run workflow** 按钮。

这就是 yml 里 `workflow_dispatch:` 的作用。

### 每一步在干什么（对照日志看）

| 步骤 | 在干什么 | 耗时 | 看什么 |
|---|---|---|---|
| Checkout | 把你的仓库下载到虚拟机 | 2 s | — |
| Set up JDK 17 | 装 Java 编译器 | 10 s | — |
| Set up Python 3.11 | 装 Python | 10 s | — |
| **Export YOLOv8n → TFLite** | 装 ultralytics（含 torch），下载 `yolov8n.pt`，导出 tflite，复制进 `assets/` | **2~4 分钟** | 最后 `ls -lh` 应该列出 `yolov8n_float32.tflite` |
| **Verify TFLite model shapes** | 打印模型真实输入输出形状 | 30 s | **⚠️ 这步最重要，见下** |
| Ensure Android SDK 34 | 确认 SDK 平台和 build-tools 在 | 20 s | `ls` 出的列表里有 `android-34` |
| Cache Gradle | 恢复依赖缓存 | 10 s | 第一次是空的，正常 |
| Install Gradle 8.7 | 下载 Gradle 到 `$HOME/.gradle-dist` | 40 s | 最后打印 Gradle 版本号 |
| **Build debug APK** | 真正编译 | **3~5 分钟** | 最后 `BUILD SUCCESSFUL` |
| Show APK | 列出产物 | 2 s | `app-debug.apk`（含 4 个 CPU 架构的 TFLite 库 + 6MB 模型，约 25~35 MB） |
| Upload APK as artifact | 存成 Artifact | 20 s | — |
| Publish APK to Release | 发 Release | 10 s | 打印 release URL |

**总计约 6~10 分钟。** 第一次跑没有缓存，会偏慢。

### ⚠️ 必须检查的一步：Verify TFLite model shapes

这步的日志会长这样（**正确**的样子）：

```
模型文件: app/src/main/assets/yolov8n_float32.tflite
  INPUT  serving_default_images:0 [  1 640 640   3] float32
  OUTPUT StatefulPartitionedCall:0 [   1  84 8400] float32
>> 期望: INPUT [1,640,640,3] float32
>> 期望: OUTPUT 含一维 = 84 (4+80) 的 [1,84,8400] 或 [1,8400,84]
```

**怎么判断对不对**：

- 输入必须是 `[1, 640, 640, 3]` + `float32`
- 输出里必须**有一个 84 的维度**（84 = 4 个坐标 + 80 个 COCO 类别）
- 输出**只有 1 个张量**

如果输出的 84 变成了别的数，或者出现了 2 个以上输出张量（说明 ultralytics 把 NMS 也导进去了），
`YoloDetector` 会在手机上抛 `无法识别的输出形状` —— **这时就别往下走了，把这段日志贴给我改配方。**

### 成功 / 失败的样子

- ✅ 全部步骤绿色对勾，最后一条显示 `Build APK` 绿色
- ❌ 某一步红色叉，整个构建停在那一格，**后面的步骤不执行**

---

## 6. 取回 APK

有两个地方：

### 方式 A：Releases（推荐，手机友好）

1. 仓库主页**右侧栏**点 **Releases**（或者地址栏加 `/releases`）
2. 看到 `APK build 1`、`APK build 2` 这样的一条条记录
3. 点进去，下面有个 **Assets** 区，里面是 `app-debug.apk`
4. **手机上直接用浏览器打开这个 Release 页面，点 `app-debug.apk` 就能下载**

> 为什么推荐：**Release 的附件不需要登录 GitHub 就能下载。**

### 方式 B：Artifacts

1. 点进那次成功的构建
2. 页面最下面 **Artifacts** 区，点 `yolo-overlay-debug-apk` 下载 zip
3. 解压得到 `app-debug.apk`

> 缺点：**必须登录 GitHub** 才能下载。而且下的是 zip，手机上还要解压。

---

## 7. 手机安装与配置

### 安装

1. 把 `app-debug.apk` 下载到手机
2. 点击安装，会提示"禁止安装未知应用" → 去设置里允许**你用的浏览器/文件管理器**安装未知应用
3. 装完先别急着开

### 必做设置（不做的话用不了/会被杀）

1. **悬浮窗权限**
   设置 → 应用 → YOLO 悬浮识别 → **显示在其他应用上层** → 允许

2. **国产 ROM 额外设置**（这是重点，很多人卡在这）

   | 品牌 | 要做什么 |
   |---|---|
   | 小米 / 红米 | 权限里打开 **「后台弹出界面」**；开发者选项里打开 **「USB 调试(安全设置)」** |
   | 华为 / 荣耀 | 电池 → 应用启动管理 → 手动管理 → 三个开关全开 |
   | OPPO / vivo / 一加 | 电池 → 应用耗电管理 → **允许后台运行** + **允许自启动** |

   不做的话：切到别的 App 之后几十秒，悬浮窗会突然消失。

3. **打开 App 开始识别**
   - 点「① 授权悬浮窗并开始识别」
   - 弹出系统录屏授权 → 点**「立即开始」**
     > Android 14+ **每次启动都要确认一次**，这是系统强制，不是你代码写错了
   - 屏幕上出现绿色 `AI` 小球，框开始画出来
   - 切到任意 App，框继续跟着屏幕内容走

### 悬浮小球的手势

| 操作 | 效果 |
|---|---|
| 单击 | 暂停 / 恢复绘制 |
| 拖动 | 移动位置（会记住） |
| 长按 | 停止识别退出 |

通知栏里也有「暂停/继续」和「停止」两个按钮。

---

## 8. 以后怎么改代码重新构建

**这是 CI 最大的好处**：改完推上去，它自己重新编译。

### 改参数（比如换模型、调阈值）

直接在 GitHub 网页上改：

1. 点进要改的文件 → 右上角铅笔图标 **Edit**
2. 改完 → **Commit changes**
3. 这个 commit 触发 push → **自动重新构建**
4. 几分钟后新的 Release 就出来了

### 改构建配方（比如换 yolov8s）

编辑 `.github/workflows/build-apk.yml`，把这一行：

```yaml
python tools/export_yolov8.py --model yolov8n.pt --imgsz 640
```

改成：

```yaml
python tools/export_yolov8.py --model yolov8s.pt --imgsz 640
```

同时把 `OverlayService.kt` 里的 `DEFAULT_MODEL` 和文件名对上（导出 `yolov8s.pt` 会得到
`yolov8s_float32.tflite`）。

### 想手动重跑

Actions → Build APK → Run workflow。

### 省时间的小技巧

Gradle 依赖有缓存（`Cache Gradle` 那步），第二次之后 `Build debug APK` 会明显变快。
但模型导出那 2~4 分钟省不掉。

---

## 9. 排错手册

### 找不到 Actions 标签 / 是灰色的

- 邮箱没验证 → 去邮箱点确认链接
- 仓库是 fork 的（Action 默认关闭）→ 进 Settings → Actions → 允许

### 构建在 Export YOLOv8n -> TFLite 失败

最常见。看具体报错：

| 报错关键词 | 原因 | 处理 |
|---|---|---|
| `Could not find a version that satisfies the requirement ultralytics` | PyPI 临时抽风 | 点 **Re-run failed jobs** |
| `onnx2tf` / `tensorflow` 相关 | ultralytics 版本变动导致依赖变了 | 把 `pip install ultralytics` 改成 `pip install "ultralytics==8.3.0"` 固定版本 |
| `No such file or directory: 'tools/export_yolov8.py'` | 第 3 步漏传了 `tools/` 文件夹 | 补传 |
| 下载 `yolov8n.pt` 超时 | 网络抖动 | Re-run |

### 构建在 Build debug APK 失败

| 报错关键词 | 原因 | 处理 |
|---|---|---|
| `Failed to find target with hash string 'android-34'` | SDK 34 没装上 | 看 `Ensure Android SDK 34` 那步日志里 `ls` 出的平台列表 |
| `Could not find method ... ` / `Unresolved reference` | 第 3 步漏传了 .kt 文件 | 检查 `app/src/main/java/com/example/yolooverlay/` 是否有 **9 个** .kt |
| `Unsupported class file major version` | JDK 版本不对 | 确认 `Set up JDK 17` 那步是绿色 |
| `Could not resolve org.tensorflow:tensorflow-lite:2.16.1` | Maven 网络抖动 | Re-run |
| `gradle: command not found` | Gradle 没进 PATH | 看 `Install Gradle 8.7` 日志最后有没有打印版本号 |

### APK 装上去但一个框都不画

App 会弹 toast「**找不到模型文件 assets/yolov8n_float32.tflite**」→ 说明模型没打进包里。
回看 `Export YOLOv8n -> TFLite` 那步的 `ls -lh` 输出。

### 悬浮窗一闪就没了

国产 ROM 杀后台。回去看第 7 步的必做设置。

### 有框但是位置全偏了

屏幕旋转后坐标没跟上。`OverlayService.onConfigurationChanged` 里有 400ms 的延迟重建，
某些机型可能需要调长。告诉我机型我改。

---

## 10. 省事路线：让助手直接推代码

**上面的第 3、4 步（手动拖 21 个文件 + 粘贴 yml）其实可以完全跳过。**

这台容器**能访问外网**（实测 `api.github.com`、`pypi.org` 都通），
所以可以让助手用 Python 直接调 GitHub API 把整个工程推上去。

### 你需要准备的

1. 一个 GitHub 账号
2. 一个 **Personal Access Token (PAT)**

### 生成 PAT 的步骤

1. 登录 GitHub → 右上角头像 → **Settings**
2. 左侧拉到最下 → **Developer settings**
3. **Personal access tokens → Tokens (classic)**
4. 点 **Generate new token (classic)**
5. 填写：
   - Note：`yolo-overlay-push`
   - Expiration：`7 days`（够用了，用完就能删）
   - **Scopes 勾选**：
     - ✅ **`repo`**（创建仓库、推文件）
     - ✅ **`workflow`**（推 `.github/workflows/*.yml` —— **不勾这个会报 `refusing to allow a Personal Access Token to create or update workflow`**）
6. 点 **Generate token**
7. **立刻复制**那串 `ghp_xxxxx...`（离开页面就再也看不到了）

### 然后把 token 给我

告诉我：

```
token: ghp_xxxxxxxxxxxx
仓库名: YoloOverlay          （或者你想要的名字）
```

我会：
1. 建仓库
2. 用 Git Data API 一次性推 21 个文件 + workflow
3. 告诉你 Actions 页面链接

**推完第一件事就是去 GitHub 把 token 撤销**（Settings → Developer settings → 那个 token → Delete）。
7 天有效期本身就够安全了，但推完立刻删更好。

> ⚠️ 提醒：token 等同于你的账号写权限。如果你不想给，就走第 3、4 步手动传，效果完全一样。

---

## 附：整个流程的时间账

| 阶段 | 时间 |
|---|---|
| 注册 GitHub（一次性） | 3 分钟 |
| 建仓库 | 1 分钟 |
| 上传 21 个文件 | 2 分钟 |
| 建 workflow 文件 | 1 分钟 |
| 等构建 | 6~10 分钟 |
| 下载 + 装机 + 设权限 | 5 分钟 |
| **合计** | **约 20 分钟** |

之后每次改代码，只需要第 5~7 步，约 10 分钟。
