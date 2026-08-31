plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.aixtin.droidagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.aixtin.droidagent"
        minSdk = 24
        targetSdk = 34
        versionCode = 19
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.mozilla:rhino:1.7.14")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
}
