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
