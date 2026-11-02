# 语音运行库

本机语音模型(Parakeet v3 识别、Supertonic 3 朗读)跑在 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 上
(Apache-2.0;ONNX Runtime 1.28.2 以静态库链接进 `libsherpa-onnx-jni.so`,MIT)。

- `sources.lock`:用的官方 AAR 的地址与 SHA-256(`名称 版本 URL sha256`,`#` 开头是注释),和 `tools/sandbox/sources.lock` 同一个格式。
- `fetch-sherpa-onnx.sh`:按锁文件下载 AAR、核对 SHA-256,放到 `app/libs/`(被 git 忽略)。已有且对得上就不再下载;
  `--check` 只核对不下载。只接受 https,跳转也只走 https。
- `app/build.gradle.kts` 以 `implementation(files(...))` 用这个 AAR;编译前 `checkSherpaOnnx` 再核对一次,缺了或对不上就停下并指向本脚本。
  AAR 带四种 ABI,打包时只留 `arm64-v8a`(其余在 `packaging.jniLibs.excludes` 里去掉)。
- CI(`.github/workflows/build-voice-apk.yml`)在 Gradle 之前先跑本脚本。

升级:改 `sources.lock` 里的版本、地址和校验值(发布页上的 digest 或 `sha256sum` 下载的文件),再运行脚本;
同时更新 `THIRD_PARTY_NOTICES.md` 和关于页的许可证清单。

模型本身不随 APK 发布:App 里在 设置 › 语音 下载,清单见 `core/.../voice/VoiceCatalog.kt`。
