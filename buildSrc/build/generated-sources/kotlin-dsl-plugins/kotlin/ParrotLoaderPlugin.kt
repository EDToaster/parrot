/**
 * Precompiled [parrot-loader.gradle.kts][Parrot_loader_gradle] script plugin.
 *
 * @see Parrot_loader_gradle
 */
public
class ParrotLoaderPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Parrot_loader_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
