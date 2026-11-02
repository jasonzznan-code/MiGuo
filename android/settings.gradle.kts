pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 国内网络可改用阿里云镜像（取消注释，并放到最前面）：
        // maven("https://maven.aliyun.com/repository/google")
        // maven("https://maven.aliyun.com/repository/public")
        // maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // maven("https://maven.aliyun.com/repository/google")
        // maven("https://maven.aliyun.com/repository/public")
    }
}
rootProject.name = "MiguoAndroid"
include(":app")
