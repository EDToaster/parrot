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
