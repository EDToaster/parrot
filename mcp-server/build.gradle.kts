plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.shadow)
}

group = "dev.parrot"
version = rootProject.property("mod_version") as String

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("dev.parrot.mcp.MainKt")
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}

tasks.shadowJar {
    archiveBaseName.set("parrot-mcp-server")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    manifest { attributes["Main-Class"] = "dev.parrot.mcp.MainKt" }
    minimize {
        exclude(dependency("io.ktor:.*"))
        exclude(dependency("org.jetbrains.kotlinx:.*"))
    }
}

tasks.build { dependsOn(tasks.shadowJar) }
