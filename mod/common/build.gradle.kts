plugins {
    id("parrot-common")
    alias(libs.plugins.neoforge.moddev)
    alias(libs.plugins.kotlin.jvm)
}

val mod_id: String by project

neoForge {
    neoFormVersion = providers.gradleProperty("neo_form_version").get()
    val at = file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) { accessTransformers.from(at.absolutePath) }
    parchment {
        minecraftVersion = providers.gradleProperty("parchment_minecraft").get()
        mappingsVersion = providers.gradleProperty("parchment_version").get()
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
    add("commonJava", file("src/main/kotlin"))
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
