plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.plugin"
version = "1.0.0"

repositories {
    maven { setUrl("https://maven.aliyun.com/repository/public/") }
    maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin/") }
    mavenCentral()
}

intellij {
    version.set("2023.2")
    type.set("IC")
    plugins.set(listOf("maven"))
}

tasks {
    runIde {
        // 禁用配置缓存（解决你现在的报错）
        systemProperty("gradle.configuration-cache", "false")
    }

    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("261.*")
    }

    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }
}
