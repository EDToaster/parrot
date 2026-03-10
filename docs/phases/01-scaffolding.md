# Phase 1: Project Scaffolding + Build System + CI

**Goal:** Establish the Gradle multi-module build for Parrot following the MultiLoader Template pattern, adapted for Kotlin.

**Architecture:** The common module uses NeoForge's `net.neoforged.moddev` plugin in NeoForm-only mode (setting `neoFormVersion` instead of `version`) to compile against deobfuscated vanilla Minecraft without any loader APIs. Loader modules (Fabric/NeoForge) consume common sources via `commonJava`/`commonResources` configurations. A `:protocol` module provides shared Kotlin data classes. A `:mcp-server` module produces a Shadow JAR.

**Tech Stack:** Gradle 8.x Kotlin DSL, Fabric Loom 1.11, NeoForge ModDevGradle 2.0.140, Kotlin 2.3.10, Shadow 9.3.2

---

## Task 1.1: Version Catalog + Root Config

**Files:**
- Create: `gradle/libs.versions.toml`
- Create: `gradle.properties`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`

**Step 1: Create version catalog**

```toml
# gradle/libs.versions.toml
[versions]
minecraft = "1.21.10"
neo_form = "1.21.10-20251010.172816"
fabric_loader = "0.18.4"
fabric_api = "0.138.4+1.21.10"
fabric_language_kotlin = "1.13.9+kotlin.2.3.10"
neoforge = "21.10.64"
neoforge_moddev = "2.0.140"
fabric_loom = "1.11-SNAPSHOT"
kotlin = "2.3.10"
mcp_sdk = "0.9.0"
ktor = "3.4.1"
kotlinx_serialization = "1.10.0"
kotlinx_coroutines = "1.10.2"
shadow = "9.3.2"
parchment_minecraft = "1.21.9"
parchment_version = "2025.10.05"
mixin = "0.8.5"
mixinextras = "0.3.5"
java = "21"

[libraries]
minecraft = { module = "com.mojang:minecraft", version.ref = "minecraft" }
fabric_loader = { module = "net.fabricmc:fabric-loader", version.ref = "fabric_loader" }
fabric_api = { module = "net.fabricmc.fabric-api:fabric-api", version.ref = "fabric_api" }
fabric_language_kotlin = { module = "net.fabricmc:fabric-language-kotlin", version.ref = "fabric_language_kotlin" }
neoforge = { module = "net.neoforged:neoforge", version.ref = "neoforge" }
mixin = { module = "org.spongepowered:mixin", version.ref = "mixin" }
mixinextras_common = { module = "io.github.llamalad7:mixinextras-common", version.ref = "mixinextras" }
mcp_kotlin_sdk = { module = "io.modelcontextprotocol:kotlin-sdk", version.ref = "mcp_sdk" }
ktor_client_cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
ktor_client_websockets = { module = "io.ktor:ktor-client-websockets", version.ref = "ktor" }
kotlinx_serialization_json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx_serialization" }
kotlinx_coroutines_core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx_coroutines" }

[plugins]
kotlin_jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin_serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
fabric_loom = { id = "fabric-loom", version.ref = "fabric_loom" }
neoforge_moddev = { id = "net.neoforged.moddev", version.ref = "neoforge_moddev" }
shadow = { id = "com.gradleup.shadow", version.ref = "shadow" }
```

**Step 2: Create gradle.properties**

```properties
# gradle.properties
mod_name=Parrot
mod_author=Parrot Contributors
mod_id=parrot
mod_version=0.1.0
mod_group=dev.parrot
license=MIT
description=Playwright-style MCP server for AI-assisted Minecraft mod debugging
credits=

minecraft_version=1.21.10
minecraft_version_range=[1.21.10, 1.22)
neo_form_version=1.21.10-20251010.172816

fabric_loader_version=0.18.4
fabric_version=0.138.4+1.21.10

neoforge_version=21.10.64
neoforge_loader_version_range=[4,)

parchment_minecraft=1.21.9
parchment_version=2025.10.05

java_version=21

org.gradle.jvmargs=-Xmx3G
org.gradle.daemon=false
```

**Step 3: Create settings.gradle.kts**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        exclusiveContent {
            forRepository { maven { name = "Fabric"; url = uri("https://maven.fabricmc.net") } }
            filter { includeGroup("net.fabricmc"); includeGroup("net.fabricmc.unpick"); includeGroup("fabric-loom") }
        }
        exclusiveContent {
            forRepository { maven { name = "Sponge"; url = uri("https://repo.spongepowered.org/repository/maven-public") } }
            filter { includeGroupAndSubgroups("org.spongepowered") }
        }
        exclusiveContent {
            forRepository { maven { name = "NeoForge"; url = uri("https://maven.neoforged.net/releases") } }
            filter { includeGroupAndSubgroups("net.neoforged") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "parrot"

include("protocol")
include("mod:common")
include("mod:fabric")
include("mod:neoforge")
include("mcp-server")
```

**Step 4: Create root build.gradle.kts**

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.fabric.loom) apply false
    alias(libs.plugins.neoforge.moddev) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.shadow) apply false
}
```

**Step 5: Verify**

Run: `./gradlew projects`
Expected: Lists `:protocol`, `:mod:common`, `:mod:fabric`, `:mod:neoforge`, `:mcp-server`

**Step 6: Commit**

```bash
git add gradle/ gradle.properties settings.gradle.kts build.gradle.kts
git commit -m "feat: add root Gradle config with version catalog for Parrot"
```

---

## Task 1.2: buildSrc Convention Plugins

**Files:**
- Create: `buildSrc/build.gradle.kts`
- Create: `buildSrc/src/main/kotlin/parrot-common.gradle.kts`
- Create: `buildSrc/src/main/kotlin/parrot-loader.gradle.kts`

**Step 1: Create buildSrc/build.gradle.kts**

```kotlin
plugins {
    `kotlin-dsl`
}
repositories {
    gradlePluginPortal()
    mavenCentral()
}
```

**Step 2: Create parrot-common.gradle.kts**

This convention plugin is applied to common and loader modules for shared settings (archivesName, resource expansion, capabilities).

```kotlin
// buildSrc/src/main/kotlin/parrot-common.gradle.kts
plugins {
    `java-library`
}

val mod_id: String by project
val mod_name: String by project
val mod_author: String by project
val minecraft_version: String by project
val java_version: String by project

base {
    archivesName.set("$mod_id-${project.name}-$minecraft_version")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(java_version.toInt()))
    withSourcesJar()
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven { name = "Sponge"; url = uri("https://repo.spongepowered.org/repository/maven-public") } }
        filter { includeGroupAndSubgroups("org.spongepowered") }
    }
    exclusiveContent {
        forRepositories(
            maven { name = "ParchmentMC"; url = uri("https://maven.parchmentmc.org/") },
            maven { name = "NeoForge"; url = uri("https://maven.neoforged.net/releases") }
        )
        filter { includeGroup("org.parchmentmc.data") }
    }
}

tasks.named<Jar>("jar") {
    from(rootProject.file("LICENSE")) { rename { "${it}_$mod_name" } }
    manifest {
        attributes(
            "Specification-Title" to mod_name,
            "Specification-Vendor" to mod_author,
            "Specification-Version" to project.version,
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Built-On-Minecraft" to minecraft_version
        )
    }
}

val expandProps = mapOf(
    "version" to project.version,
    "group" to project.group,
    "minecraft_version" to minecraft_version,
    "minecraft_version_range" to (project.findProperty("minecraft_version_range") ?: ""),
    "fabric_version" to (project.findProperty("fabric_version") ?: ""),
    "fabric_loader_version" to (project.findProperty("fabric_loader_version") ?: ""),
    "mod_name" to mod_name,
    "mod_author" to mod_author,
    "mod_id" to mod_id,
    "license" to (project.findProperty("license") ?: ""),
    "description" to (project.findProperty("description") ?: ""),
    "neoforge_version" to (project.findProperty("neoforge_version") ?: ""),
    "neoforge_loader_version_range" to (project.findProperty("neoforge_loader_version_range") ?: ""),
    "credits" to (project.findProperty("credits") ?: ""),
    "java_version" to java_version
)

tasks.processResources {
    filesMatching(listOf("META-INF/neoforge.mods.toml")) { expand(expandProps) }
    filesMatching(listOf("pack.mcmeta", "fabric.mod.json")) {
        expand(expandProps.mapValues { (_, v) -> if (v is String) v.replace("\n", "\\\\n") else v })
    }
    inputs.properties(expandProps)
}
```

**Step 3: Create parrot-loader.gradle.kts**

This convention plugin is applied to loader modules (fabric/neoforge) to pull in common sources.

```kotlin
// buildSrc/src/main/kotlin/parrot-loader.gradle.kts
plugins {
    id("parrot-common")
}

val mod_id: String by project

val commonJava: Configuration by configurations.creating { isCanBeResolved = true }
val commonResources: Configuration by configurations.creating { isCanBeResolved = true }

dependencies {
    compileOnly(project(":mod:common")) {
        capabilities { requireCapability("${project.group}:$mod_id") }
    }
    commonJava(project(path = ":mod:common", configuration = "commonJava"))
    commonResources(project(path = ":mod:common", configuration = "commonResources"))
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(commonJava)
    source(commonJava)
}

tasks.processResources {
    dependsOn(commonResources)
    from(commonResources)
}

tasks.named<Jar>("sourcesJar") {
    dependsOn(commonJava); from(commonJava)
    dependsOn(commonResources); from(commonResources)
}
```

**Step 4: Verify**

Run: `./gradlew buildSrc:build`
Expected: Convention plugins compile without errors

**Step 5: Commit**

```bash
git add buildSrc/
git commit -m "feat: add buildSrc convention plugins (parrot-common, parrot-loader)"
```

---

## Task 1.3: Protocol Module Build

**Files:**
- Create: `protocol/build.gradle.kts`
- Create: `protocol/src/main/kotlin/dev/parrot/protocol/.gitkeep`

**Step 1: Create protocol/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "dev.parrot"
version = rootProject.property("mod_version") as String

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
```

**Step 2: Create placeholder directory**

```bash
mkdir -p protocol/src/main/kotlin/dev/parrot/protocol
touch protocol/src/main/kotlin/dev/parrot/protocol/.gitkeep
```

**Step 3: Verify**

Run: `./gradlew :protocol:build`
Expected: Compiles successfully (empty module)

**Step 4: Commit**

```bash
git add protocol/
git commit -m "feat: add protocol module for shared serializable data classes"
```

---

## Task 1.4: Common Module Build

**Files:**
- Create: `mod/common/build.gradle.kts`
- Create: `mod/common/src/main/kotlin/dev/parrot/mod/.gitkeep`
- Create: `mod/common/src/main/resources/pack.mcmeta`

**Step 1: Create mod/common/build.gradle.kts**

The critical pattern: uses `neoFormVersion` (not `version`) for vanilla-only compilation.

```kotlin
plugins {
    id("parrot-common")
    alias(libs.plugins.neoforge.moddev)
    alias(libs.plugins.kotlin.jvm)
}

val mod_id: String by project

neoForge {
    neoFormVersion = providers.gradleProperty("neo_form_version")
    val at = file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) { accessTransformers.from(at.absolutePath) }
    parchment {
        minecraftVersion = providers.gradleProperty("parchment_minecraft")
        mappingsVersion = providers.gradleProperty("parchment_version")
    }
}

dependencies {
    compileOnly(libs.mixin)
    compileOnly(libs.mixinextras.common)
    implementation(project(":protocol"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Expose source directories for loader modules
val commonJava: Configuration by configurations.creating { isCanBeResolved = false; isCanBeConsumed = true }
val commonResources: Configuration by configurations.creating { isCanBeResolved = false; isCanBeConsumed = true }

artifacts {
    add("commonJava", sourceSets.main.get().kotlin.sourceDirectories.singleFile)
    add("commonResources", sourceSets.main.get().resources.sourceDirectories.singleFile)
}

// Loader attribute for MultiLoader resolution
val loaderAttr = Attribute.of("io.github.mcgradleconventions.loader", String::class.java)
listOf("apiElements", "runtimeElements", "sourcesElements").forEach { variant ->
    configurations.findByName(variant)?.attributes { attribute(loaderAttr, "common") }
}
sourceSets.configureEach {
    listOf(compileClasspathConfigurationName, runtimeClasspathConfigurationName).forEach { variant ->
        configurations.named(variant) { attributes { attribute(loaderAttr, "common") } }
    }
}
```

**Step 2: Create pack.mcmeta**

```json
{
  "pack": {
    "description": "${mod_name}",
    "pack_format": 34
  }
}
```

**Step 3: Verify**

Run: `./gradlew :mod:common:build`
Expected: Compiles against vanilla Minecraft via NeoForm

**Step 4: Commit**

```bash
git add mod/common/
git commit -m "feat: add mod/common module using NeoForm for vanilla MC compilation"
```

---

## Task 1.5: Fabric Module Build

**Files:**
- Create: `mod/fabric/build.gradle.kts`
- Create: `mod/fabric/src/main/resources/fabric.mod.json`
- Create: `mod/fabric/src/main/kotlin/dev/parrot/mod/fabric/ParrotFabric.kt`
- Create: `mod/fabric/src/client/kotlin/dev/parrot/mod/fabric/client/ParrotFabricClient.kt`

**Step 1: Create mod/fabric/build.gradle.kts**

```kotlin
plugins {
    id("parrot-loader")
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    minecraft(libs.minecraft)
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-${project.property("parchment_minecraft")}:${project.property("parchment_version")}@zip")
    })
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)
    modImplementation(libs.fabric.language.kotlin)
    implementation(project(":protocol"))
}

loom {
    val aw = project(":mod:common").file("src/main/resources/${project.property("mod_id")}.accesswidener")
    if (aw.exists()) { accessWidenerPath.set(aw) }
    mixin { defaultRefmapName.set("${project.property("mod_id")}.refmap.json") }
    runs {
        named("client") { client(); configName = "Fabric Client"; ideConfigGenerated(true); runDir("runs/client") }
        named("server") { server(); configName = "Fabric Server"; ideConfigGenerated(true); runDir("runs/server") }
    }
    splitEnvironmentSourceSets()
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}

// Compile common Kotlin sources
val commonJava: Configuration by configurations.getting
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    dependsOn(commonJava)
    source(commonJava)
}

// Loader attribute
val loaderAttr = Attribute.of("io.github.mcgradleconventions.loader", String::class.java)
listOf("apiElements", "runtimeElements", "sourcesElements").forEach { variant ->
    configurations.findByName(variant)?.attributes { attribute(loaderAttr, "fabric") }
}
sourceSets.configureEach {
    listOf(compileClasspathConfigurationName, runtimeClasspathConfigurationName).forEach { variant ->
        configurations.named(variant) { attributes { attribute(loaderAttr, "fabric") } }
    }
}
```

**Step 2: Create fabric.mod.json**

```json
{
    "schemaVersion": 1,
    "id": "${mod_id}",
    "version": "${version}",
    "name": "${mod_name}",
    "description": "${description}",
    "authors": ["${mod_author}"],
    "license": "${license}",
    "icon": "${mod_id}.png",
    "environment": "*",
    "entrypoints": {
        "main": [{ "adapter": "kotlin", "value": "dev.parrot.mod.fabric.ParrotFabric" }],
        "client": [{ "adapter": "kotlin", "value": "dev.parrot.mod.fabric.client.ParrotFabricClient" }]
    },
    "depends": {
        "fabricloader": ">=${fabric_loader_version}",
        "fabric-api": "*",
        "fabric-language-kotlin": "*",
        "minecraft": "~${minecraft_version}",
        "java": ">=${java_version}"
    }
}
```

**Step 3: Create placeholder entrypoints**

ParrotFabric.kt:
```kotlin
package dev.parrot.mod.fabric
import net.fabricmc.api.ModInitializer

object ParrotFabric : ModInitializer {
    override fun onInitialize() { }
}
```

ParrotFabricClient.kt:
```kotlin
package dev.parrot.mod.fabric.client
import net.fabricmc.api.ClientModInitializer

object ParrotFabricClient : ClientModInitializer {
    override fun onInitializeClient() { }
}
```

**Step 4: Verify**

Run: `./gradlew :mod:fabric:build`
Expected: Produces `parrot-fabric-1.21.10.jar`

**Step 5: Commit**

```bash
git add mod/fabric/
git commit -m "feat: add Fabric module with Loom, kotlin adapter, split source sets"
```

---

## Task 1.6: NeoForge Module Build

**Files:**
- Create: `mod/neoforge/build.gradle.kts`
- Create: `mod/neoforge/src/main/resources/META-INF/neoforge.mods.toml`
- Create: `mod/neoforge/src/main/kotlin/dev/parrot/mod/neoforge/ParrotNeoForge.kt`

**Step 1: Create mod/neoforge/build.gradle.kts**

```kotlin
plugins {
    id("parrot-loader")
    alias(libs.plugins.neoforge.moddev)
    alias(libs.plugins.kotlin.jvm)
}

val mod_id: String by project

neoForge {
    version = providers.gradleProperty("neoforge_version")
    val at = project(":mod:common").file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) { accessTransformers.from(at.absolutePath) }
    parchment {
        minecraftVersion = providers.gradleProperty("parchment_minecraft")
        mappingsVersion = providers.gradleProperty("parchment_version")
    }
    runs {
        configureEach {
            systemProperty("neoforge.enabledGameTestNamespaces", mod_id)
            ideName = "NeoForge ${name.replaceFirstChar { it.uppercase() }} (${project.path})"
        }
        create("client") { client() }
        create("server") { server() }
    }
    mods {
        register(mod_id) { sourceSet(sourceSets.main.get()) }
    }
}

dependencies {
    implementation(project(":protocol"))
    jarJar(kotlin("stdlib"))
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}

// Compile common Kotlin sources
val commonJava: Configuration by configurations.getting
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    dependsOn(commonJava)
    source(commonJava)
}

// Loader attribute
val loaderAttr = Attribute.of("io.github.mcgradleconventions.loader", String::class.java)
listOf("apiElements", "runtimeElements", "sourcesElements").forEach { variant ->
    configurations.named(variant) { attributes { attribute(loaderAttr, "neoforge") } }
}
sourceSets.configureEach {
    listOf(compileClasspathConfigurationName, runtimeClasspathConfigurationName).forEach { variant ->
        configurations.named(variant) { attributes { attribute(loaderAttr, "neoforge") } }
    }
}
```

**Step 2: Create neoforge.mods.toml**

```toml
modLoader = "javafml"
loaderVersion = "${neoforge_loader_version_range}"
license = "${license}"

[[mods]]
modId = "${mod_id}"
version = "${version}"
displayName = "${mod_name}"
logoFile = "${mod_id}.png"
credits = "${credits}"
authors = "${mod_author}"
description = '''${description}'''

[[dependencies.${mod_id}]]
modId = "neoforge"
type = "required"
versionRange = "[${neoforge_version},)"
ordering = "NONE"
side = "BOTH"

[[dependencies.${mod_id}]]
modId = "minecraft"
type = "required"
versionRange = "${minecraft_version_range}"
ordering = "NONE"
side = "BOTH"
```

**Step 3: Create placeholder entrypoint**

```kotlin
package dev.parrot.mod.neoforge
import net.neoforged.fml.common.Mod

@Mod("parrot")
class ParrotNeoForge {
    init { }
}
```

**Step 4: Verify**

Run: `./gradlew :mod:neoforge:build`
Expected: Produces `parrot-neoforge-1.21.10.jar`

**Step 5: Commit**

```bash
git add mod/neoforge/
git commit -m "feat: add NeoForge module with ModDevGradle and jarJar Kotlin bundling"
```

---

## Task 1.7: MCP Server Module Build

**Files:**
- Create: `mcp-server/build.gradle.kts`
- Create: `mcp-server/src/main/kotlin/dev/parrot/mcp/Main.kt`

**Step 1: Create mcp-server/build.gradle.kts**

```kotlin
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
```

**Step 2: Create placeholder Main.kt**

```kotlin
package dev.parrot.mcp

fun main() {
    System.err.println("[parrot-mcp] Parrot MCP Server starting...")
}
```

**Step 3: Verify**

Run: `./gradlew :mcp-server:shadowJar`
Expected: Produces `parrot-mcp-server-0.1.0.jar`

Run: `java -jar mcp-server/build/libs/parrot-mcp-server-0.1.0.jar`
Expected: Prints "[parrot-mcp] Parrot MCP Server starting..." to stderr

**Step 4: Commit**

```bash
git add mcp-server/
git commit -m "feat: add mcp-server module with Shadow JAR packaging"
```

---

## Task 1.8: GitHub Actions CI

**Files:**
- Create: `.github/workflows/build.yml`

**Step 1: Create build.yml**

```yaml
name: Build

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Java 21
        uses: actions/setup-java@v4
        with:
          java-version: 21
          distribution: temurin

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Build all modules
        run: ./gradlew build

      - name: Run all tests
        run: ./gradlew test

      - name: Upload Fabric mod
        uses: actions/upload-artifact@v4
        with:
          name: parrot-fabric
          path: mod/fabric/build/libs/*.jar
          if-no-files-found: error

      - name: Upload NeoForge mod
        uses: actions/upload-artifact@v4
        with:
          name: parrot-neoforge
          path: mod/neoforge/build/libs/*.jar
          if-no-files-found: error

      - name: Upload MCP server
        uses: actions/upload-artifact@v4
        with:
          name: parrot-mcp-server
          path: mcp-server/build/libs/parrot-mcp-server-*.jar
          if-no-files-found: error
```

**Step 2: Verify**

Run: `cat .github/workflows/build.yml | python3 -c "import sys,yaml; yaml.safe_load(sys.stdin); print('Valid YAML')"`
Expected: "Valid YAML"

**Step 3: Commit**

```bash
git add .github/
git commit -m "feat: add GitHub Actions CI workflow"
```

---

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Kotlin source sharing in MultiLoader Template | Add `source(commonJava)` to both `compileJava` and `compileKotlin` in loader modules |
| NeoForge jarJar Kotlin stdlib conflicts | Fallback: use Shadow plugin on NeoForge module instead |
| fabric-loom + kotlin plugin interaction | Ensure `fabric-language-kotlin` is `modImplementation` (not just `implementation`) |
| Protocol module kotlinx-serialization at runtime | Fabric gets it from fabric-language-kotlin; NeoForge gets it via jarJar |
| NeoForm version accuracy | Verify against `https://maven.neoforged.net/releases/net/neoforged/neoform/maven-metadata.xml` |
