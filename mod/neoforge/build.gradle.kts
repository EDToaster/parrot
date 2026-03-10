plugins {
    id("parrot-loader")
    alias(libs.plugins.neoforge.moddev)
    alias(libs.plugins.kotlin.jvm)
}

val mod_id: String by project

neoForge {
    version = providers.gradleProperty("neoforge_version").get()
    val at = project(":mod:common").file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) { accessTransformers.from(at.absolutePath) }
    parchment {
        minecraftVersion = providers.gradleProperty("parchment_minecraft").get()
        mappingsVersion = providers.gradleProperty("parchment_version").get()
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
