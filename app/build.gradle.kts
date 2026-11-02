import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 关于页上的构建编号:当前提交的短哈希;没有 git(如从源码包编译)时写 dev
// 语音的运行库 sherpa-onnx:官方 AAR 由 tools/voice/fetch-sherpa-onnx.sh 按 tools/voice/sources.lock 下到 app/libs/,不进 git。
val sherpaOnnxLock: Pair<String, String> = rootProject.file("tools/voice/sources.lock").readLines()
    .map { it.trim() }.first { it.startsWith("sherpa-onnx ") }
    .split(Regex("\\s+")).let { (_, _, url, sha) -> url.substringAfterLast('/') to sha }
val sherpaOnnxAar: File = file("libs/${sherpaOnnxLock.first}")

val gitHash: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "dev"

android {
    namespace = "com.wangzan101.mika"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wangzan101.mika"
        minSdk = 30
        targetSdk = 35
        versionCode = 26
        versionName = "0.1.25"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        // 关于页版本号后的 Beta 标记;出正式版时改成 false
        buildConfigField("boolean", "BETA", "true")
        // 电脑连接(MCP)默认监听的端口;并存安装的包换一个,两个同时打开时不抢端口
        buildConfigField("int", "MCP_DEFAULT_PORT", "8765")
        // sherpa-onnx 的 Kotlin 类由 JNI 按名字读字段、调方法;以后打开压缩混淆时不能改名或删掉
        proguardFiles("proguard-voice.pro")
    }

    // 沙箱程序只有 arm64 版本(jniLibs/arm64-v8a);APK 不限 ABI,别的设备照常安装、显示「此设备不支持沙箱」。
    // 沙箱程序要从 nativeLibraryDir 直接执行,安装时必须解到磁盘
    // sherpa-onnx 的 AAR 带四种 ABI:只留 arm64-v8a(和沙箱一样);别的 ABI 上本机语音模型显示不能用。
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf("armeabi-v7a", "x86", "x86_64").flatMap { abi ->
                listOf("lib/$abi/libsherpa-onnx-jni.so", "lib/$abi/libonnxruntime.so")
            }
        }
    }
    // 自带的运行环境(assets/runtime/rootfs.tar.xz,tools/sandbox/build-runtime.py 生成)已是 xz,不再压一遍
    androidResources { noCompress += "xz" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 评测任务集(仓库根目录 eval/tasks)打进测试包的 assets,真机评测跑器从那里读题
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("eval/tasks"))

    buildTypes {
        // 看流畅度用的包:和发布版一样不可调试(调试包不做预先编译,界面慢好几倍,手感不作数),
        // 用本机调试签名,能直接覆盖调试包、保留数据;可被性能工具采样。./gradlew :app:assemblePerf
        create("perf") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isProfileable = true
            matchingFallbacks += "release"
        }
        // 和已装的 Mika 并存的调试包:应用标识加 .voice、桌面名「Mika Voice」(src/voicedev/res),数据与设置各自独立,
        // 不覆盖、不读原版的数据;debug / release / perf 不变。./gradlew :app:assembleVoicedev
        create("voicedev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".voice"
            versionNameSuffix = "-voice"
            buildConfigField("int", "MCP_DEFAULT_PORT", "18765")
            matchingFallbacks += "debug"
        }
        // 第三个并存安装的调试包「Mika Tester」:应用标识加 .tester、版本名加 -tester(src/mikatester/res),
        // 以后专门在手机上替别的代理测装好的 App。和原版、Mika Voice 三个包各自一份数据,文件提供者的 authority 都按
        // ${applicationId} 拼,互不冲突;电脑连接默认端口 28765,三个同时打开也不抢。语音代码照样编进去(同一份源码),
        // 模型用到时才下载,不占安装包。构建类型名不能以 test 开头(AGP 把它留给测试),所以叫 mikatester。
        // ./gradlew :app:assembleMikatester
        create("mikatester") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".tester"
            versionNameSuffix = "-tester"
            buildConfigField("int", "MCP_DEFAULT_PORT", "28765")
            matchingFallbacks += "debug"
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    // sherpa-onnx(本机语音识别、朗读),没下载时 checkSherpaOnnx 让构建停下
    implementation(files(sherpaOnnxAar))
    // 解 .tar.bz2 的模型包(Supertonic 3 之类)
    implementation(libs.commons.compress)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    // 两种识别模型都随 APK 安装；首次使用不依赖 Play 服务下载。
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // 沙箱路径契约(core 的 testFixtures):AndroidSandbox 在 JVM 与真机上都跑同一套
    testImplementation(testFixtures(project(":core")))
    androidTestImplementation(testFixtures(project(":core")))

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}

// LicensesTest 读仓库根目录的 THIRD_PARTY_NOTICES.md:让它参与测试的增量判断
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("THIRD_PARTY_NOTICES.md")).withPropertyName("thirdPartyNotices")
}

// 没取 sherpa-onnx 的 AAR,或文件和 sources.lock 的 SHA-256 对不上,就在编译前停下并说清楚怎么办
val checkSherpaOnnx by tasks.registering {
    val aar = sherpaOnnxAar
    val expected = sherpaOnnxLock.second
    inputs.property("sha256", expected)
    outputs.upToDateWhen { false }
    doLast {
        if (!aar.isFile) {
            throw GradleException(
                "缺少语音运行库 ${aar.relativeTo(rootDir)}。请先运行 tools/voice/fetch-sherpa-onnx.sh 下载 sherpa-onnx " +
                    "(Missing ${aar.name}: run tools/voice/fetch-sherpa-onnx.sh first)",
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        aar.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw GradleException(
                "${aar.relativeTo(rootDir)} 的 SHA-256 是 $actual,和 tools/voice/sources.lock 不符。" +
                    "再运行一次 tools/voice/fetch-sherpa-onnx.sh 重新下载 (SHA-256 mismatch: re-run the fetch script)",
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(checkSherpaOnnx) }
