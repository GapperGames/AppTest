// Root build file. Plugin versions are declared in settings.gradle.kts
// (pluginManagement) so that each module resolves only the plugins it uses.
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
