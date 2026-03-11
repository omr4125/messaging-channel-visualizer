plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.12.0"
}

group = "messaging.channel.visualizer"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.17")

    intellijPlatform {
        val localPathProp = providers.gradleProperty("ideaLocalPath").orNull

        if (localPathProp != null) {
            local(localPathProp)
        } else {
            intellijIdeaCommunity("2024.3")
        }

        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("231")
            untilBuild.set("253.*")
        }
    }

    buildSearchableOptions.set(false)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}