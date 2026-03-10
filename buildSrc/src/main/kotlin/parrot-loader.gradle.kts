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
