pluginManagement {
    repositories {
        // 国内镜像加速: 阿里云同步 Google Maven 与 Maven Central, 国内拉依赖更快
        // 开源说明: 海外开发者若访问慢, 删除镜像行仅保留官方仓库即可
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像优先, 官方仓库兜底
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}
rootProject.name = "AgentApp"
include(":app")
