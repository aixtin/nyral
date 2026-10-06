import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("jacoco")
}

android {
    namespace = "io.github.aixtin.nyral"
    compileSdk = 36

    // 正式签名（keystore.properties 不入库，密码不硬编码）
    val keystoreProps = rootProject.file("keystore.properties")
    val hasReleaseKey = keystoreProps.exists()
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                val p = Properties()
                keystoreProps.inputStream().use { p.load(it) }
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.aixtin.nyral"
        minSdk = 29
        targetSdk = 36
        versionCode = 42
        versionName = "2.4.0"
        ndk {
            abiFilters += listOf(project.findProperty("nyralAbi") as String? ?: "arm64-v8a")
        }
    }

    buildTypes {
        release {
            // 安全审查修复(2026-10-03): release 开启 R8 压缩+混淆+优化
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        // 2026-10-06: 存量 lint error 冻结进 baseline, 仅新增问题使 CI 失败
        baseline = file("lint-baseline.xml")
    }


    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 安全审查修复(2026-10-03): 安全相关库升级到最新稳定版(jsch/commons-compress/bcprov/xz 均有 CVE 历史)
    implementation("com.github.mwiede:jsch:0.2.26")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:7.6.0") // CVE-2026-41245/CVE-2026-28208 修复
    implementation("org.bouncycastle:bcprov-jdk18on:1.82")
    implementation("org.mozilla:rhino:1.7.15")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("com.atlassian.commonmark:commonmark:0.13.0")
    implementation("com.atlassian.commonmark:commonmark-ext-gfm-tables:0.13.0")
    implementation("com.atlassian.commonmark:commonmark-ext-gfm-strikethrough:0.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // coroutines 1.10+ 需 Kotlin 2.x 编译器, 项目为 1.9, 保持 1.8.1 兼容
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("org.hamcrest:hamcrest-core:1.3")
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Generate Jacoco HTML/XML report for pure-function classes (all package)"
    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }
    classDirectories.setFrom(files("$buildDir/tmp/kotlin-classes/debug"))
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(files("$buildDir/jacoco/testDebugUnitTest.exec"))
}