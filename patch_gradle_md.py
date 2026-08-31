# -*- coding: utf-8 -*-
import io

p = "/home/ymz/droid-agent/android-agent-app/app/build.gradle.kts"
s = io.open(p, encoding="utf-8").read()

old = """    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")
}"""
new = """    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
}"""
assert s.count(old) == 1
s = s.replace(old, new)
io.open(p, "w", encoding="utf-8").write(s)
print("GRADLE_OK")
