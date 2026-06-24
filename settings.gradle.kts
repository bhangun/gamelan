import java.io.StringReader
import java.util.Properties

val gamelanProjectNamePattern = Regex("""[a-z][a-z0-9._-]*""")

fun requestedModuleCatalogFiles(): List<File> =
    providers.gradleProperty("gamelan.module.files")
        .orElse(providers.gradleProperty("gamelan.module.file"))
        .orElse("")
        .get()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { file(it) }

fun moduleCatalogFiles(): List<File> =
    buildList {
        add(file("gradle/gamelan-modules.properties"))
        file("gradle/gamelan-modules.local.properties").takeIf(File::isFile)?.let(::add)
        addAll(requestedModuleCatalogFiles())
    }

fun displayPath(file: File): String =
    file.relativeToOrSelf(rootDir).path

fun loadGamelanProjects(): Map<String, String> {
    val projects = sortedMapOf<String, String>()

    moduleCatalogFiles().forEach { modulesFile ->
        if (!modulesFile.isFile) {
            throw GradleException("Missing Gamelan module catalog: ${displayPath(modulesFile)}")
        }

        val properties = Properties()
        properties.load(StringReader(modulesFile.readText()))
        properties.stringPropertyNames()
            .filter { it.startsWith("project.") }
            .forEach { key ->
                val projectName = key.removePrefix("project.").trim()
                val projectDir = properties.getProperty(key).trim()
                projects[projectName] = projectDir
            }
    }

    if (projects.isEmpty()) {
        throw GradleException("Gamelan module catalog must declare at least one project")
    }

    projects.forEach { (projectName, projectDir) ->
        if (!gamelanProjectNamePattern.matches(projectName)) {
            throw GradleException(
                "Invalid Gamelan Gradle project name `$projectName`. " +
                    "Use lowercase letters, digits, dots, underscores, or hyphens."
            )
        }
        if (projectDir.isBlank()) {
            throw GradleException("Gamelan Gradle project `$projectName` must declare a project directory")
        }
        val directory = file(projectDir)
        if (!directory.isDirectory) {
            throw GradleException("Gamelan Gradle project `$projectName` points to missing directory: $projectDir")
        }
    }

    return projects
}

pluginManagement {
    // Include 'plugins build' to define convention plugins.
    includeBuild("build-logic")
    repositories {
        if (providers.gradleProperty("gamelan.plugin.repositories.local.enabled").map(String::toBoolean).orElse(false).get()) {
            mavenLocal()
        }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("io.quarkus") version providers.gradleProperty("gamelan.quarkus.version").orElse("3.32.2").get()
    }
}

rootProject.name = "gamelan-parent"

loadGamelanProjects().forEach { (projectName, projectDir) ->
    include(":$projectName")
    project(":$projectName").projectDir = file(projectDir)
}
