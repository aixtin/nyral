plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // 2026-10-15: Kotlin 静态分析门, 与 lint baseline 策略一致(存量冻结, 新增拦截)
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
}
