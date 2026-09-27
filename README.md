<div align="center">

# iceScribe · 冰记

**把手机变成一支离线录音笔 —— 一句话说完，文字就出来了，全程不出这台设备**

[![Release](https://img.shields.io/github/v/release/ice-wocker/iceScribe?color=38BDF8&label=Release)](https://github.com/ice-wocker/iceScribe/releases)
[![CI](https://github.com/ice-wocker/iceScribe/actions/workflows/android.yml/badge.svg)](https://github.com/ice-wocker/iceScribe/actions/workflows/android.yml)
[![Stars](https://img.shields.io/github/stars/ice-wocker/iceScribe?color=38BDF8)](https://github.com/ice-wocker/iceScribe/stargazers)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-3DDC84?logo=android&logoColor=white)](#)
[![arm64](https://img.shields.io/badge/ABI-arm64--v8a-4B5563)](#)
[![whisper.cpp](https://img.shields.io/badge/whisper.cpp-v1.9.4-000000)](https://github.com/ggml-org/whisper.cpp)
[![无网络权限](https://img.shields.io/badge/%E6%9D%83%E9%99%90-%E4%B8%8D%E7%94%B3%E8%AF%B7%20INTERNET-22D3EE)](#隐私)

[下载安装](#下载安装) · [功能](#功能) · [怎么用](#怎么用) · [模型](#模型) · [技术亮点](#技术亮点) · [隐私](#隐私) · [已知限制](#已知限制) · [构建](#构建)

</div>

---

一个干净的 Android 录音转文字 App：**whisper.cpp 和模型都内置在应用里**，装完就能用；把麦克风或已有音频转成文字，
模型在手机本地加载、推理也在本地跑。飞行模式下照样用。

最直白的一条：**这个 App 没有申请网络权限**。装完可以在系统设置里点开权限列表看——只有麦克风，
没有网络、没有存储、没有位置。录音和文字没有任何技术路径离开这台设备。

> **当前版本 v0.1.1** · 内置 whisper 模型，装完即用 · 纯 CPU 离线推理 · 不需要联网 · 不需要存储权限 · 不需要账号

---

## 功能

<table>
<tr><td width="130"><b>离线转写</b></td><td>

内置 whisper.cpp（v1.9.4），录音结束自动转写，结果直接落在可编辑的文本框里；转完会显示**耗时和相对实时的倍速**，快慢一眼可见。

</td></tr>
<tr><td><b>录音</b></td><td>

大按钮一键录制，按钮随说话音量**呼吸放大**，计时器实时走；用 `VOICE_RECOGNITION` 音源采集，对语音识别更友好；**前台服务**保证息屏、切到别的 App 也继续录。

</td></tr>
<tr><td><b>边录边转格式</b></td><td>

设备只保证 44.1k / 48k，whisper 只吃 16k。采集时**实时重采样成 16kHz 单声道并直接写成 WAV**，内存占用是常数级——录一小时也不怕。

</td></tr>
<tr><td><b>分段落盘转写</b></td><td>

转写时从 WAV 里**按 4 分钟一段流式读取**，每段把上一段的结尾文字作为提示词喂给模型，跨段断句和用词接得上；进度条给百分比，随时可取消。

</td></tr>
<tr><td><b>导入已有音频</b></td><td>

手机里的语音备忘录、会议录音、微信里的 m4a…都能选进来：走系统解码器（m4a / mp3 / wav / ogg / aac）**边解码边重采样**，再同上转写。

</td></tr>
<tr><td><b>语言与翻译</b></td><td>

中文 / 英文 / 自动检测三档；可开关**「翻译成英文」**——中文说话、英文出稿。

</td></tr>
<tr><td><b>性能可调</b></td><td>

CPU 线程数 2 / 3 / 4 / 6 / 8 任选，按机器核数给默认值。不同机型大核数量差别很大，自己试一次就知道哪档最快。

</td></tr>
<tr><td><b>结果处理</b></td><td>

文本框可直接改；一键**复制**、**分享**、**保存**；转完自动存一条，避免误触丢稿。

</td></tr>
<tr><td><b>历史记录</b></td><td>

最近的转写自动落盘（退出不丢），点一条就能取回文字继续编辑；支持单条删除与全部清除。

</td></tr>
<tr><td><b>模型管理</b></td><td>

**已内置 `base` 量化模型（q5_1，约 57 MB）**，首装打开即自动解包加载，不用先去哪儿下模型；想更准可从文件选择器导入更大的模型，也支持**数据线直接拷进应用目录**（两个目录都会被扫描）；长按可删除，列表里标出模型大小与所在目录。

</td></tr>
</table>

---

## 怎么用

1. **装 App**，第一次打开会自动解包内置模型（约 57 MB，几秒钟），完成后底部显示「模型已就绪」。
2. **点麦克风**开始说话，说完再点一下（变成方块就是停止）。
3. 等进度条走完，文字就出现在下面了。改一改、复制、分享都行。

想转已有音频就点 **导入音频**；想换成更准的模型点右上角的按钮。

---

## 模型

App **已经内置 `base` 量化模型（`ggml-base-q5_1.bin`，约 57 MB）**，装完直接能用，不需要先去哪儿下模型
——因为没有网络权限，App 里也没法下载，内置是「开箱即用」的唯一办法。

想更准可以自己换：用手机浏览器下一个 `.bin` 文件，再从 App 里导入即可。来源是 whisper.cpp 官方的 ggml 模型仓库：

| 模型 | 体积 | 适合 |
|---|---|---|
| `ggml-base-q5_1.bin` | 约 57 MB | **已内置**，开箱即用 |
| [ggml-small.bin](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin) | 约 466 MB | **中文推荐**，速度和准确率的平衡点 |
| [ggml-medium.bin](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin) | 约 1.5 GB | 更准，适合旗舰机 |
| [ggml-large-v3-turbo.bin](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin) | 约 1.6 GB | 最准的一档 |

**更快的方式**：用数据线把模型拷到应用外部目录，App 启动时会自动扫到（文件名以 `.bin` 或 `.gguf` 结尾即可）：

```bash
adb push ggml-small.bin /sdcard/Android/data/com.ice.scribe/files/models/
```

> Android 11 之后文件管理器看不到 `Android/data`，但 `adb push` 照样能写进去。

---

## 技术亮点

- **whisper.cpp 静态编进应用**：NDK 27 + CMake 拉源码编译，只保留推理部分（关掉 examples / server / tests）；代码与原生库合计不到 6 MB。
- **内置模型，装完即用**：把 `ggml-base-q5_1.bin`（约 57 MB）打进 APK 的 assets，首次启动自动解包到应用目录并加载，不需要用户先去哪儿下模型、也不碰网络。模型以 Stored 方式原样打包（已量化的权重压缩不出收益）。
- **纯 CPU 推理，不做无用的尝试**：ARM64 上关掉 GPU 与 llamafile 的 x86 专用内核，走 NEON 优化路径；`GGML_OPENMP` 关闭，避免在 Android 上引入不可用的运行时依赖。
- **常数级内存**：录音侧边录边重采样落盘、转写侧按段流式读取，两处都不把整段音频堆在内存里，所以「录多久」只受存储限制，不受内存限制。
- **跨段提示词衔接**：利用 whisper 的 `initial_prompt`，把上一段末尾九十来个字带进下一段，长录音的断句和专有名词不会被切断。
- **取消是真的能停**：接上 whisper 的 `abort_callback`，在窗口之间检查取消标志，正在跑的推理会尽快退出，而不是等它跑完。
- **零存储权限**：模型、录音、历史全部在应用私有目录；导入靠系统文件选择器给的一次性 URI 授权。
- **零第三方依赖**：只有 Android 框架 + JUnit，没有 AndroidX、没有 JSON 库（用系统自带 `org.json`）、没有图片库，安装包干净。
- **单元测试兜住音频链路**：重采样比例、WAV 写入 / 读回、模型名解析都有测试——这几步一错，时间轴就全乱。

---

## 隐私

| 问题 | 答案 |
|---|---|
| 录音会上传吗？ | 不会。App 没有 `INTERNET` 权限，代码里也没有任何网络调用。 |
| 要账号吗？ | 不要。没有登录、没有统计、没有广告 SDK。 |
| 录音存哪儿？ | 应用私有目录（`files/recordings`），只有本 App 能读；卸载即删。 |
| 要存储权限吗？ | 不要。导入音频/模型是系统文件选择器授权，只给选中的那一个文件。 |
| 能断网用吗？ | 可以，开飞行模式完全正常。 |

想验证的话：装好后打开系统设置 → 应用 → iceScribe → 权限，看到的就是全部。

---

## 已知限制

- **只打包 arm64-v8a**：32 位机型与部分模拟器装不上（32 位跑 whisper 没有实用性，砍掉能省一半体积）。
- **安装包 59 MB**：其中 57 MB 是内置模型。因为没有网络权限，模型没法改成运行时下载；首次启动还要把它解包到应用目录，安装后总占用约 116 MB（APK + 解包副本）。
- **只有 base 一档内置**：想要更高准确率（尤其中文），自己导入 `small` 及以上。
- **标点质量看模型**：中文标点由模型能力决定，`tiny` / `base` 可能标点较少，换 `small` 以上会明显变好。
- **录音占空间**：16kHz 单声道约 1.9 MB / 分钟；应用会自动只保留最近 20 段录音，避免无声无息占满存储。
- **首次编译慢**：原生库要从源码编译 whisper.cpp，第一次构建需要几分钟。

---

## 下载安装

| 渠道 | 说明 |
|---|---|
| **[Releases](https://github.com/ice-wocker/iceScribe/releases)** | 推荐。CI 构建的正式签名 APK |
| 自行构建 | 见下一节，几分钟就能出包 |

要求：**Android 7.0（API 24）及以上**，**arm64 设备**；安装包约 **59 MB**（已含内置模型），安装后总占用约 116 MB。

---

## 构建

```bash
git clone https://github.com/ice-wocker/iceScribe.git
cd iceScribe
./gradlew :app:assembleDebug        # 产物在 app/build/outputs/apk/debug/
```

需要 **JDK 17**、**Android SDK 36**、**NDK 27.2.12479018**、**CMake 3.22.1**（版本都写在 `app/build.gradle` 里）。
初次构建会通过 CMake `FetchContent` 从 GitHub 拉取 whisper.cpp v1.9.4 源码；如果本地已有源码，
可以用 `-DWHISPER_CPP_DIR=/path/to/whisper.cpp` 指过去，省下每次拉取的时间。

跑测试与静态检查：

```bash
./gradlew testDebugUnitTest lintDebug
```

---

## 作者

**ice-wocker** · [github.com/ice-wocker](https://github.com/ice-wocker)

如果这个 App 让你少传了一次录音到云上，点个 Star 就够了。

</div>