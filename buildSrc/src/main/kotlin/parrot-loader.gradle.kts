plugins {
    id("parrot-common")
}

val mod_id: String by project

// Allow "common" loader attribute to satisfy any loader request
val loaderAttr = Attribute.of("io.github.mcgradleconventions.loader", String::class.java)
dependencies.attributesSchema {
    attribute(loaderAttr) {
        compatibilityRules.add(LoaderCompatibilityRule::class.java)
    }
}

abstract class LoaderCompatibilityRule : AttributeCompatibilityRule<String> {
    override fun execute(details: CompatibilityCheckDetails<String>) {
        if (details.producerValue == "common") {
            details.compatible()
        }
    }
}

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
}

tasks.processResources {
    dependsOn(commonResources)
    from(commonResources)
}

tasks.named<Jar>("sourcesJar") {
    dependsOn(commonJava); from(commonJava)
    dependsOn(commonResources); from(commonResources)
}
