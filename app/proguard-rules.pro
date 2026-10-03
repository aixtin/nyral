# Nyral R8 混淆规则 (2026-10-03 安全审查修复: release 开启 R8 压缩+混淆+优化)

# 应用自身: 保留全部(工具名/参数均字符串分发, 防裁剪误伤; 第三方库混淆已达成逆向难度目标)
-keep class io.github.aixtin.nyral.** { *; }

# Rhino: JS 引擎依赖解释模式与动态类加载
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# JSch: SSH 算法类通过反射注册
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# BouncyCastle: JCA Provider SPI 注册
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# CommonMark / Markwon: 解析器经反射构造
-keep class org.commonmark.** { *; }
-keep class io.noties.markwon.** { *; }
-dontwarn org.commonmark.**
-dontwarn io.noties.markwon.**

# Apache Commons Compress / junrar / xz: SPI + 反射
-keep class org.apache.commons.compress.** { *; }
-keep class com.github.junrar.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.junrar.**
-dontwarn org.tukaani.xz.**

# ONNX Runtime: 含 native 与内部反射
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Media3 / AndroidX: 组件注解与接口反射
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class androidx.recyclerview.** { *; }
-dontwarn androidx.recyclerview.**

# org.json: 平台提供, 不需 keep
-dontwarn org.json.**

# slf4j: 无绑定实现(仅被库引用), 运行时不影响
-dontwarn org.slf4j.**
