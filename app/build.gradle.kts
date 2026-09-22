import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.aixtin.nyral"
    compileSdk = 34

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
        minSdk = 24
        targetSdk = 34
        versionCode = 32
        versionName = "2.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
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
    implementation("com.github.mwiede:jsch:0.2.17")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.9")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.mozilla:rhino:1.7.14")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.2")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("com.atlassian.commonmark:commonmark:0.13.0")
    implementation("com.atlassian.commonmark:commonmark-ext-gfm-tables:0.13.0")
    implementation("com.atlassian.commonmark:commonmark-ext-gfm-strikethrough:0.13.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation(files(
        "$rootDir/local-test-libs/junit-4.13.2.jar",
        "$rootDir/local-test-libs/json-20231013.jar",
        "$rootDir/local-test-libs/hamcrest-core-1.3.jar"
    ))
}
