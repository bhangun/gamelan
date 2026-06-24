import java.io.File
import java.io.StringReader
import java.util.Properties

plugins {
    base
}

if (project != rootProject) {
    throw GradleException("The buildlogic.gamelan-profiles plugin must be applied only to the root project")
}

data class GamelanProfile(
    val name: String,
    val description: String,
    val projectRoots: List<String>,
    val runtimeContract: String?,
    val source: String,
)

val profilesFile = layout.projectDirectory.file("gradle/gamelan-profiles.properties")
val localProfilesFile = layout.projectDirectory.file("gradle/gamelan-profiles.local.properties")
val profileKeyPattern = Regex("""profile\.(.+)\.(description|projects|runtime-contract)""")
val profileNamePattern = Regex("""[a-z][a-z0-9._-]*""")
val runtimeCapabilityContracts = setOf(
    "none",
    "local",
    "offline-agent",
    "standalone",
    "distributed",
    "production",
)
val runtimeServingProjectRoots = setOf(
    ":gamelan-engine",
    ":gamelan-runtime-core",
    ":gamelan-runtime-standalone",
    ":gamelan-runtime-distributed",
)

fun readProfileFile(file: File): Properties {
    val properties = Properties()
    properties.load(StringReader(file.readText()))
    return properties
}

fun requestedProfileFiles(): List<File> =
    (findProperty("gamelan.profile.files") ?: findProperty("gamelan.profile.file") ?: "")
        .toString()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { file(it) }

fun profileCatalogFiles(): List<File> =
    buildList {
        add(profilesFile.asFile)
        if (localProfilesFile.asFile.isFile) {
            add(localProfilesFile.asFile)
        }
        addAll(requestedProfileFiles())
    }

fun displayPath(file: File): String =
    file.relativeToOrSelf(rootDir).path

fun loadGamelanProfiles(files: List<File>): Map<String, GamelanProfile> {
    val properties = Properties()
    val sourceByProfile = mutableMapOf<String, String>()

    files.forEach { profileFile ->
        if (!profileFile.isFile) {
            throw GradleException("Gamelan profile catalog does not exist: ${displayPath(profileFile)}")
        }

        val fileProperties = readProfileFile(profileFile)
        val source = displayPath(profileFile)
        fileProperties.stringPropertyNames().forEach { key ->
            profileKeyPattern.matchEntire(key)?.let { match ->
                sourceByProfile[match.groupValues[1]] = source
            }
            properties.setProperty(key, fileProperties.getProperty(key))
        }
    }

    val profileNames = properties.stringPropertyNames()
        .mapNotNull { key ->
            profileKeyPattern.matchEntire(key)
                ?.groupValues
                ?.get(1)
        }
        .distinct()
        .sorted()

    return profileNames.associateWith { name ->
        if (name == "all") {
            throw GradleException("Gamelan profile name `all` is reserved for the generated full-build profile")
        }
        if (!profileNamePattern.matches(name)) {
            throw GradleException(
                "Invalid Gamelan profile name `$name`. Use lowercase letters, digits, dots, underscores, or hyphens."
            )
        }
        val description = properties.getProperty("profile.$name.description")
            ?: throw GradleException("Missing profile.$name.description in profile catalog")
        val projectRoots = properties.getProperty("profile.$name.projects")
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?: throw GradleException("Missing profile.$name.projects in profile catalog")
        val runtimeContract = properties.getProperty("profile.$name.runtime-contract")
            ?.trim()
            ?.ifEmpty { null }
        if (projectRoots.isEmpty()) {
            throw GradleException("Profile `$name` must declare at least one project root")
        }
        projectRoots.filterNot { it.startsWith(":") }.takeIf { it.isNotEmpty() }?.let { invalid ->
            throw GradleException(
                "Profile `$name` has invalid project path(s): ${invalid.joinToString(", ")}. " +
                "Project paths must start with `:`."
            )
        }
        if (runtimeContract != null && runtimeContract !in runtimeCapabilityContracts) {
            throw GradleException(
                "Profile `$name` declares unsupported runtime contract `$runtimeContract`. " +
                    "Known contracts: ${runtimeCapabilityContracts.sorted().joinToString(", ")}"
            )
        }
        GamelanProfile(name, description, projectRoots, runtimeContract, sourceByProfile.getValue(name))
    }
}

val profileCatalogFiles = profileCatalogFiles()
val gamelanProfiles = loadGamelanProfiles(profileCatalogFiles)
val allProfileDescription = "Every Gradle subproject with a build file"
val profileCatalogDisplayPaths = profileCatalogFiles.map(::displayPath)
val standaloneMigrationDir = layout.projectDirectory
    .dir("runtime/gamelan-runtime-standalone/src/main/resources/db/migration")
    .asFile
val distributedMigrationDir = layout.projectDirectory
    .dir("runtime/gamelan-runtime-distributed/src/main/resources/db/migration")
    .asFile
val standaloneRuntimePropertiesFile = layout.projectDirectory
    .file("runtime/gamelan-runtime-standalone/src/main/resources/application.properties")
    .asFile
val distributedRuntimePropertiesFile = layout.projectDirectory
    .file("runtime/gamelan-runtime-distributed/src/main/resources/application.properties")
    .asFile
val engineApplicationPropertiesFile = layout.projectDirectory
    .file("core/gamelan-engine/src/main/resources/application.properties")
    .asFile
val engineProductionPropertiesFile = layout.projectDirectory
    .file("core/gamelan-engine/src/main/resources/application-production.properties")
    .asFile
val coreClientSdkDir = layout.projectDirectory
    .dir("core/gamelan-sdk-client-core")
    .asFile
val localClientSdkDir = layout.projectDirectory
    .dir("sdk/gamelan-sdk-client-local")
    .asFile
val remoteClientSdkDir = layout.projectDirectory
    .dir("sdk/gamelan-sdk-client-remote")
    .asFile
val coreExecutorSdkDir = layout.projectDirectory
    .dir("core/gamelan-sdk-executor-core")
    .asFile
val localExecutorSdkDir = layout.projectDirectory
    .dir("sdk/gamelan-sdk-executor-local")
    .asFile
val remoteExecutorSdkDir = layout.projectDirectory
    .dir("sdk/gamelan-sdk-executor-remote")
    .asFile

fun String.taskSuffix(): String =
    split('-', '_', '.')
        .filter(String::isNotBlank)
        .joinToString("") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

fun requestedProfileNames(): List<String> =
    (findProperty("gamelan.profile") ?: findProperty("profile") ?: "core")
        .toString()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

fun requestedTargetTaskName(): String =
    (findProperty("gamelan.task") ?: findProperty("task") ?: "build")
        .toString()
        .trim()
        .ifEmpty { "build" }

fun allBuildProjects(): List<String> =
    subprojects
        .filter { it.buildFile.isFile }
        .map { it.path }
        .sorted()

fun knownProfiles(): Set<String> = gamelanProfiles.keys + "all"

fun profileDescription(profileName: String): String =
    if (profileName == "all") allProfileDescription else gamelanProfiles.getValue(profileName).description

fun profileProjectRoots(profileName: String): List<String> =
    if (profileName == "all") allBuildProjects() else gamelanProfiles.getValue(profileName).projectRoots

fun profileRuntimeContract(profileName: String): String? =
    if (profileName == "all") null else gamelanProfiles.getValue(profileName).runtimeContract

fun validateProfileCatalog() {
    val missingProjects = gamelanProfiles.values.flatMap { profile ->
        profile.projectRoots
            .filter { findProject(it) == null }
            .map { "${profile.name} -> $it" }
    }
    if (missingProjects.isNotEmpty()) {
        throw GradleException(
            "Gamelan profile catalog references unknown project(s): ${missingProjects.joinToString(", ")}"
        )
    }

    val taskSuffixCollisions = gamelanProfiles.keys
        .groupBy(String::taskSuffix)
        .filterValues { it.size > 1 }
    if (taskSuffixCollisions.isNotEmpty()) {
        throw GradleException(
            "Gamelan profile names produce duplicate generated task names: " +
                taskSuffixCollisions.values.joinToString("; ") { it.joinToString(", ") }
        )
    }
}

validateProfileCatalog()

val gamelanProfileLines = knownProfiles().sorted().flatMap { profileName ->
    val roots = profileProjectRoots(profileName)
    val source = gamelanProfiles[profileName]?.source?.let { " [$it]" }.orEmpty()
    val runtimeContract = profileRuntimeContract(profileName)?.let { " runtime-contract=$it" }.orEmpty()
    listOf("$profileName - ${profileDescription(profileName)}$runtimeContract$source") +
        roots.map { "  $it" }
}

fun resolveProfileProjectRoots(profileNames: List<String>): List<String> {
    val knownProfiles = knownProfiles()
    val unknownProfiles = profileNames.filterNot(knownProfiles::contains)
    if (unknownProfiles.isNotEmpty()) {
        throw GradleException(
            "Unknown Gamelan profile(s): ${unknownProfiles.joinToString(", ")}. " +
                "Known profiles: ${knownProfiles.sorted().joinToString(", ")}"
        )
    }

    return profileNames
        .flatMap(::profileProjectRoots)
        .distinct()
        .also { projectRoots ->
            val missingProjects = projectRoots.filter { findProject(it) == null }
            if (missingProjects.isNotEmpty()) {
                throw GradleException(
                    "Gamelan profile(s) ${profileNames.joinToString(", ")} reference unknown project(s): " +
                        missingProjects.joinToString(", ")
                )
            }
        }
}

fun Task.configureProfileTask(profileNames: List<String>, targetTaskName: String) {
    group = "gamelan profiles"

    val projectRoots = resolveProfileProjectRoots(profileNames)
    dependsOn(projectRoots.map { "$it:$targetTaskName" })

    doFirst {
        logger.lifecycle("Gamelan profiles: {}", profileNames.joinToString(", "))
        logger.lifecycle("Gamelan task: {}", targetTaskName)
        logger.lifecycle("Gamelan project roots: {}", projectRoots.joinToString(", "))
    }
}

tasks.register("gamelanProfileProjects") {
    group = "gamelan profiles"
    description = "Print selected Gamelan profile project roots. Override with -Pgamelan.profile=core,server."

    val profileNames = requestedProfileNames()
    val projectRoots = resolveProfileProjectRoots(profileNames)

    doLast {
        println("profiles=${profileNames.joinToString(",")}")
        projectRoots.forEach(::println)
    }
}

tasks.register("gamelanProfiles") {
    group = "gamelan profiles"
    description = "Print available Gamelan Gradle profiles and their project roots."

    val catalogPaths = profileCatalogDisplayPaths.toList()
    val profileLines = gamelanProfileLines.toList()

    doLast {
        println("Profile catalogs:")
        catalogPaths.forEach { println("  $it") }
        println()
        profileLines.forEach(::println)
    }
}

tasks.register("gamelanValidateProfiles") {
    group = "gamelan profiles"
    description = "Validate Gamelan Gradle profile catalogs, runtime contracts, migrations, and SDK ownership."
    dependsOn(
        "gamelanValidateRuntimeCapabilityContracts",
        "gamelanValidateRuntimeCapabilityConfig",
        "gamelanValidateMigrations",
        "gamelanValidateClientSdkTransportOwnership",
        "gamelanValidateExecutorSdkTransportOwnership",
    )

    val profileCount = gamelanProfiles.size + 1
    val catalogPaths = profileCatalogDisplayPaths.toList()

    doLast {
        println("Validated $profileCount Gamelan profile(s)")
        println("Profile catalogs:")
        catalogPaths.forEach { println("  $it") }
    }
}

tasks.register("gamelanValidateRuntimeCapabilityContracts") {
    group = "gamelan profiles"
    description = "Validate Gradle profile declarations against runtime capability contract intent."

    val profiles = gamelanProfiles.values.toList()
    val runtimeRoots = runtimeServingProjectRoots.toSet()

    doLast {
        fun GamelanProfile.displayName(): String = "`$name`"

        fun GamelanProfile.hasAnyRoot(candidates: Set<String>): Boolean =
            projectRoots.any(candidates::contains)

        fun GamelanProfile.requireRoots(requiredRoots: Set<String>, contractName: String) {
            val missing = requiredRoots.filterNot(projectRoots::contains)
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Profile ${displayName()} declares runtime-contract=$contractName but is missing " +
                        "required project root(s): ${missing.joinToString(", ")}"
                )
            }
        }

        fun GamelanProfile.requireAnyRoot(requiredRoots: Set<String>, contractName: String) {
            if (!hasAnyRoot(requiredRoots)) {
                throw GradleException(
                    "Profile ${displayName()} declares runtime-contract=$contractName but must include at least one " +
                        "project root from: ${requiredRoots.sorted().joinToString(", ")}"
                )
            }
        }

        fun GamelanProfile.forbidRoots(forbiddenRoots: Set<String>, contractName: String) {
            val present = forbiddenRoots.filter(projectRoots::contains)
            if (present.isNotEmpty()) {
                throw GradleException(
                    "Profile ${displayName()} declares runtime-contract=$contractName but includes incompatible " +
                        "project root(s): ${present.joinToString(", ")}"
                )
            }
        }

        val missingContracts = profiles
            .filter { profile -> profile.runtimeContract == null && profile.hasAnyRoot(runtimeRoots) }
            .map(GamelanProfile::name)
            .sorted()
        if (missingContracts.isNotEmpty()) {
            throw GradleException(
                "Runtime-bearing Gamelan profile(s) must declare profile.<name>.runtime-contract: " +
                    missingContracts.joinToString(", ")
            )
        }

        profiles
            .filter { profile -> profile.runtimeContract != null && profile.runtimeContract != "none" }
            .forEach { profile ->
                when (val contract = profile.runtimeContract) {
                    "offline-agent" -> {
                        profile.requireRoots(
                            setOf(
                                ":gamelan-runtime-core",
                                ":gamelan-runtime-standalone",
                                ":gamelan-sdk-client-local",
                                ":gamelan-sdk-executor-local",
                            ),
                            contract,
                        )
                        profile.forbidRoots(setOf(":gamelan-runtime-distributed"), contract)
                    }
                    "standalone" -> profile.requireRoots(
                        setOf(":gamelan-runtime-core", ":gamelan-runtime-standalone"),
                        contract,
                    )
                    "distributed" -> profile.requireRoots(
                        setOf(":gamelan-runtime-core", ":gamelan-runtime-distributed"),
                        contract,
                    )
                    "production" -> {
                        profile.requireAnyRoot(
                            setOf(":gamelan-engine", ":gamelan-runtime-distributed"),
                            contract,
                        )
                        if (":gamelan-engine" in profile.projectRoots) {
                            profile.requireRoots(setOf(":gamelan-executor-registry"), contract)
                        }
                    }
                    "local" -> Unit
                }
            }

        val declaredContracts = profiles
            .mapNotNull(GamelanProfile::runtimeContract)
            .groupingBy { it }
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(", ") { (contract, count) -> "$contract=$count" }
        println(
            "Validated Gamelan runtime capability contracts: " +
                (declaredContracts.ifBlank { "<none declared>" })
        )
    }
}

tasks.register("gamelanValidateRuntimeCapabilityConfig") {
    group = "gamelan profiles"
    description = "Validate runtime application properties against selected runtime capability contracts."

    val standalonePath = standaloneRuntimePropertiesFile.absolutePath
    val distributedPath = distributedRuntimePropertiesFile.absolutePath
    val engineApplicationPath = engineApplicationPropertiesFile.absolutePath
    val engineProductionPath = engineProductionPropertiesFile.absolutePath
    val rootDirectoryPath = rootDir.absolutePath
    inputs.file(standalonePath)
    inputs.file(distributedPath)
    inputs.file(engineApplicationPath)
    inputs.file(engineProductionPath)

    doLast {
        data class RuntimeConfigExpectation(
            val label: String,
            val path: String,
            val expectedValues: Map<String, String>,
        )

        val rootDirectory = File(rootDirectoryPath)

        fun displayPathFor(file: File): String =
            file.relativeToOrSelf(rootDirectory).path

        fun readProperties(file: File): Properties {
            if (!file.isFile) {
                throw GradleException("Missing runtime capability config file: ${displayPathFor(file)}")
            }
            return Properties().also { properties ->
                file.inputStream().use(properties::load)
            }
        }

        fun validateExpectation(expectation: RuntimeConfigExpectation) {
            val file = File(expectation.path)
            val properties = readProperties(file)
            val mismatches = expectation.expectedValues
                .filter { (key, expectedValue) -> properties.getProperty(key) != expectedValue }
                .map { (key, expectedValue) ->
                    "$key expected `$expectedValue`, found `${properties.getProperty(key) ?: "<missing>"}`"
                }
            if (mismatches.isNotEmpty()) {
                throw GradleException(
                    "Runtime capability config ${expectation.label} is not aligned with its contract: " +
                        "${displayPathFor(file)}: ${mismatches.joinToString("; ")}"
                )
            }
        }

        listOf(
            RuntimeConfigExpectation(
                "standalone offline-agent",
                standalonePath,
                mapOf(
                    "gamelan.runtime.capabilities.contract" to "offline-agent",
                    "gamelan.runtime.capabilities.cache-ttl" to "0s",
                    "gamelan.runtime.capabilities.startup-validation.mode" to "fail",
                    "gamelan.runtime.capabilities.startup-validation.accept-degraded" to "true",
                    "gamelan.runtime.capabilities.startup-validation.issue-detail-limit" to "50",
                    "gamelan.runtime.capabilities.readiness.accept-degraded" to "true",
                    "gamelan.runtime.capabilities.readiness.issue-detail-limit" to "50",
                    "gamelan.workflow.persistence.store" to "file",
                    "gamelan.event.publisher.family" to "local",
                    "gamelan.workflow.wakeup.outbox.store" to "file",
                    "gamelan.agent.context.store" to "file",
                    "gamelan.registry.persistence.type" to "memory",
                    "gamelan.registry.selection.prefer-local" to "true",
                    "gamelan.scheduler.mode" to "local",
                    "gamelan.grpc.task-stream.default-enabled" to "false",
                    "gamelan.grpc.task-stream.broker" to "memory",
                ),
            ),
            RuntimeConfigExpectation(
                "distributed",
                distributedPath,
                mapOf(
                    "gamelan.runtime.capabilities.contract" to "distributed",
                    "gamelan.runtime.capabilities.cache-ttl" to "2s",
                    "gamelan.runtime.capabilities.startup-validation.mode" to "fail",
                    "gamelan.runtime.capabilities.startup-validation.accept-degraded" to "false",
                    "gamelan.runtime.capabilities.startup-validation.issue-detail-limit" to "10",
                    "gamelan.runtime.capabilities.readiness.accept-degraded" to "false",
                    "gamelan.runtime.capabilities.readiness.issue-detail-limit" to "10",
                    "gamelan.workflow.persistence.store" to "postgres",
                    "gamelan.event.publisher.family" to "kafka",
                    "gamelan.workflow.wakeup.outbox.store" to "postgres",
                    "gamelan.agent.context.store" to "postgres",
                    "gamelan.registry.persistence.type" to "redis",
                    "gamelan.registry.selection.prefer-local" to "false",
                    "gamelan.scheduler.mode" to "redis",
                    "gamelan.grpc.task-stream.default-enabled" to "false",
                    "gamelan.grpc.task-stream.broker" to "redis",
                    "quarkus.arc.selected-alternatives" to "tech.kayys.gamelan.kafka.KafkaEventPublisher",
                ),
            ),
            RuntimeConfigExpectation(
                "engine default",
                engineApplicationPath,
                mapOf(
                    "gamelan.runtime.capabilities.contract" to "local",
                    "gamelan.runtime.capabilities.cache-ttl" to "0s",
                    "gamelan.runtime.capabilities.startup-validation.mode" to "warn",
                    "gamelan.runtime.capabilities.startup-validation.issue-detail-limit" to "20",
                    "gamelan.runtime.capabilities.readiness.issue-detail-limit" to "20",
                    "gamelan.registry.persistence.type" to "memory",
                    "gamelan.scheduler.mode" to "local",
                    "gamelan.event.publisher.family" to "local",
                    "gamelan.workflow.wakeup.outbox.store" to "auto",
                    "%prod.gamelan.runtime.capabilities.contract" to "production",
                    "%prod.gamelan.runtime.capabilities.cache-ttl" to "2s",
                    "%prod.gamelan.runtime.capabilities.startup-validation.mode" to "fail",
                    "%prod.gamelan.runtime.capabilities.startup-validation.accept-degraded" to "false",
                    "%prod.gamelan.runtime.capabilities.startup-validation.issue-detail-limit" to "10",
                    "%prod.gamelan.runtime.capabilities.readiness.accept-degraded" to "false",
                    "%prod.gamelan.runtime.capabilities.readiness.issue-detail-limit" to "10",
                    "%prod.gamelan.registry.persistence.type" to "database",
                    "%prod.gamelan.workflow.persistence.store" to "postgres",
                    "%prod.gamelan.agent.context.store" to "postgres",
                    "%prod.gamelan.scheduler.mode" to "redis",
                    "%prod.gamelan.event.publisher.family" to "kafka",
                    "%prod.gamelan.workflow.wakeup.outbox.store" to "postgres",
                    "%prod.quarkus.arc.selected-alternatives" to "tech.kayys.gamelan.kafka.KafkaEventPublisher",
                ),
            ),
            RuntimeConfigExpectation(
                "engine production",
                engineProductionPath,
                mapOf(
                    "gamelan.runtime.capabilities.contract" to "production",
                    "gamelan.runtime.capabilities.cache-ttl" to "2s",
                    "gamelan.runtime.capabilities.startup-validation.mode" to "fail",
                    "gamelan.runtime.capabilities.startup-validation.accept-degraded" to "false",
                    "gamelan.runtime.capabilities.startup-validation.issue-detail-limit" to "10",
                    "gamelan.runtime.capabilities.readiness.accept-degraded" to "false",
                    "gamelan.runtime.capabilities.readiness.issue-detail-limit" to "10",
                    "gamelan.workflow.persistence.store" to "postgres",
                    "gamelan.event.publisher.family" to "kafka",
                    "gamelan.workflow.wakeup.outbox.store" to "postgres",
                    "gamelan.agent.context.store" to "postgres",
                    "gamelan.registry.persistence.type" to "database",
                    "gamelan.scheduler.mode" to "redis",
                    "gamelan.grpc.task-stream.default-enabled" to "false",
                    "gamelan.grpc.task-stream.broker" to "redis",
                    "quarkus.arc.selected-alternatives" to "tech.kayys.gamelan.kafka.KafkaEventPublisher",
                ),
            ),
        ).forEach(::validateExpectation)

        println("Validated Gamelan runtime capability application config alignment")
    }
}

tasks.register("gamelanValidateClientSdkTransportOwnership") {
    group = "gamelan profiles"
    description = "Validate client SDK local/remote transport ownership and guard against duplicate transport classes."

    val coreClientSdkPath = coreClientSdkDir.absolutePath
    val localClientSdkPath = localClientSdkDir.absolutePath
    val remoteClientSdkPath = remoteClientSdkDir.absolutePath
    val rootDirectoryPath = rootDir.absolutePath
    inputs.dir(File(coreClientSdkPath).resolve("src/main/java"))
    inputs.dir(File(localClientSdkPath).resolve("src/main/java"))
    inputs.dir(File(localClientSdkPath).resolve("src/main/resources"))
    inputs.dir(File(remoteClientSdkPath).resolve("src/main/java"))
    inputs.dir(File(remoteClientSdkPath).resolve("src/main/resources"))

    doLast {
        val rootDirectory = File(rootDirectoryPath)

        fun displayPathFor(file: File): String =
            file.relativeToOrSelf(rootDirectory).path

        fun mainJavaRoot(projectDir: File): File =
            projectDir.resolve("src/main/java")

        fun mainJavaSources(projectDir: File): List<File> {
            val root = mainJavaRoot(projectDir)
            if (!root.isDirectory) {
                return emptyList()
            }
            return root.walkTopDown()
                .filter { file -> file.isFile && file.extension == "java" }
                .toList()
        }

        fun sourceIdentity(projectDir: File, sourceFile: File): String {
            val root = mainJavaRoot(projectDir)
            return sourceFile
                .relativeTo(root)
                .path
                .replace(File.separatorChar, '/')
                .removeSuffix(".java")
                .replace('/', '.')
        }

        fun serviceProviderEntries(projectDir: File): List<String> {
            val providerFile = projectDir.resolve(
                "src/main/resources/META-INF/services/tech.kayys.gamelan.sdk.client.GamelanClientProvider"
            )
            if (!providerFile.isFile) {
                return emptyList()
            }
            return providerFile.readLines()
                .map { line -> line.substringBefore("#").trim() }
                .filter(String::isNotEmpty)
        }

        fun requireProjectDirectory(projectDir: File, label: String) {
            if (!projectDir.isDirectory) {
                throw GradleException("Missing $label SDK project directory: ${displayPathFor(projectDir)}")
            }
        }

        val coreDir = File(coreClientSdkPath)
        val localDir = File(localClientSdkPath)
        val remoteDir = File(remoteClientSdkPath)
        requireProjectDirectory(coreDir, "core client")
        requireProjectDirectory(localDir, "local client")
        requireProjectDirectory(remoteDir, "remote client")

        val localSources = mainJavaSources(localDir)
        val remoteSources = mainJavaSources(remoteDir)
        val coreSources = mainJavaSources(coreDir)

        val remoteForbiddenLocalClasses = setOf(
            "LocalGamelanClient.java",
            "LocalGamelanClientProvider.java",
            "LocalWorkflowDefinitionClient.java",
            "LocalWorkflowRunClient.java",
        )
        val remoteLocalClasses = remoteSources
            .filter { source -> source.name in remoteForbiddenLocalClasses }
            .map { source -> displayPathFor(source) }
            .sorted()
        if (remoteLocalClasses.isNotEmpty()) {
            throw GradleException(
                "Remote client SDK must not own LOCAL transport classes: ${remoteLocalClasses.joinToString(", ")}"
            )
        }

        val localForbiddenRemotePrefixes = listOf("Rest", "Grpc", "Remote")
        val localRemoteClasses = localSources
            .filter { source -> localForbiddenRemotePrefixes.any { prefix -> source.name.startsWith(prefix) } }
            .map { source -> displayPathFor(source) }
            .sorted()
        if (localRemoteClasses.isNotEmpty()) {
            throw GradleException(
                "Local client SDK must not own REST/gRPC/remote transport classes: ${localRemoteClasses.joinToString(", ")}"
            )
        }

        val localIdentities = localSources.groupBy { source -> sourceIdentity(localDir, source) }
        val remoteIdentities = remoteSources.groupBy { source -> sourceIdentity(remoteDir, source) }
        val duplicateClientClasses = (localIdentities.keys intersect remoteIdentities.keys)
            .sorted()
        if (duplicateClientClasses.isNotEmpty()) {
            throw GradleException(
                "Local and remote client SDK modules contain duplicate Java classes: " +
                    duplicateClientClasses.joinToString(", ")
            )
        }

        val expectedLocalProviders = listOf("tech.kayys.gamelan.sdk.client.LocalGamelanClientProvider")
        val expectedRemoteProviders = listOf("tech.kayys.gamelan.sdk.client.RemoteGamelanClientProvider")
        val localProviders = serviceProviderEntries(localDir)
        val remoteProviders = serviceProviderEntries(remoteDir)
        if (localProviders != expectedLocalProviders) {
            throw GradleException(
                "Local client SDK ServiceLoader providers must be ${expectedLocalProviders.joinToString(", ")}; " +
                    "found ${localProviders.ifEmpty { listOf("<none>") }.joinToString(", ")}"
            )
        }
        if (remoteProviders != expectedRemoteProviders) {
            throw GradleException(
                "Remote client SDK ServiceLoader providers must be ${expectedRemoteProviders.joinToString(", ")}; " +
                    "found ${remoteProviders.ifEmpty { listOf("<none>") }.joinToString(", ")}"
            )
        }

        val productionStubPattern = Regex("""UnsupportedOperationException\s*\(|not implemented""", RegexOption.IGNORE_CASE)
        val productionStubs = (coreSources + localSources + remoteSources)
            .filter { source -> productionStubPattern.containsMatchIn(source.readText()) }
            .map { source -> displayPathFor(source) }
            .sorted()
        if (productionStubs.isNotEmpty()) {
            throw GradleException(
                "Client SDK production sources must not contain unsupported/not-implemented stubs: " +
                    productionStubs.joinToString(", ")
            )
        }

        println(
            "Validated client SDK transport ownership: " +
                "local=${displayPathFor(localDir)}, remote=${displayPathFor(remoteDir)}"
        )
    }
}

tasks.register("gamelanValidateExecutorSdkTransportOwnership") {
    group = "gamelan profiles"
    description = "Validate executor SDK local/remote transport ownership and local profile dependency shape."

    val coreExecutorSdkPath = coreExecutorSdkDir.absolutePath
    val localExecutorSdkPath = localExecutorSdkDir.absolutePath
    val remoteExecutorSdkPath = remoteExecutorSdkDir.absolutePath
    val localExecutorBuildFilePath = localExecutorSdkDir.resolve("build.gradle.kts").absolutePath
    val rootDirectoryPath = rootDir.absolutePath
    inputs.dir(File(coreExecutorSdkPath).resolve("src/main/java"))
    inputs.dir(File(localExecutorSdkPath).resolve("src/main/java"))
    inputs.dir(File(remoteExecutorSdkPath).resolve("src/main/java"))
    inputs.file(localExecutorBuildFilePath)

    doLast {
        val rootDirectory = File(rootDirectoryPath)

        fun displayPathFor(file: File): String =
            file.relativeToOrSelf(rootDirectory).path

        fun mainJavaRoot(projectDir: File): File =
            projectDir.resolve("src/main/java")

        fun mainJavaSources(projectDir: File): List<File> {
            val root = mainJavaRoot(projectDir)
            if (!root.isDirectory) {
                return emptyList()
            }
            return root.walkTopDown()
                .filter { file -> file.isFile && file.extension == "java" }
                .toList()
        }

        fun sourceIdentity(projectDir: File, sourceFile: File): String {
            val root = mainJavaRoot(projectDir)
            return sourceFile
                .relativeTo(root)
                .path
                .replace(File.separatorChar, '/')
                .removeSuffix(".java")
                .replace('/', '.')
        }

        fun requireProjectDirectory(projectDir: File, label: String) {
            if (!projectDir.isDirectory) {
                throw GradleException("Missing $label SDK project directory: ${displayPathFor(projectDir)}")
            }
        }

        val coreDir = File(coreExecutorSdkPath)
        val localDir = File(localExecutorSdkPath)
        val remoteDir = File(remoteExecutorSdkPath)
        requireProjectDirectory(coreDir, "core executor")
        requireProjectDirectory(localDir, "local executor")
        requireProjectDirectory(remoteDir, "remote executor")

        val coreSources = mainJavaSources(coreDir)
        val localSources = mainJavaSources(localDir)
        val remoteSources = mainJavaSources(remoteDir)

        val localForbiddenRemotePrefixes = listOf("Grpc", "Kafka", "Remote")
        val localRemoteClasses = localSources
            .filter { source -> localForbiddenRemotePrefixes.any { prefix -> source.name.startsWith(prefix) } }
            .map { source -> displayPathFor(source) }
            .sorted()
        if (localRemoteClasses.isNotEmpty()) {
            throw GradleException(
                "Local executor SDK must not own gRPC/Kafka/remote transport classes: " +
                    localRemoteClasses.joinToString(", ")
            )
        }

        val remoteForbiddenLocalClasses = setOf(
            "LocalExecutorRuntime.java",
            "LocalExecutorTransport.java",
            "LocalExecutorTransportFactory.java",
        )
        val remoteLocalClasses = remoteSources
            .filter { source -> source.name in remoteForbiddenLocalClasses }
            .map { source -> displayPathFor(source) }
            .sorted()
        if (remoteLocalClasses.isNotEmpty()) {
            throw GradleException(
                "Remote executor SDK must not own LOCAL transport classes: ${remoteLocalClasses.joinToString(", ")}"
            )
        }

        val requiredRemoteTransportContracts = mapOf(
            "GrpcExecutorTransport.java" to "@Identifier(\"grpc\")",
            "KafkaExecutorTransport.java" to "@Identifier(\"kafka\")",
        )
        requiredRemoteTransportContracts.forEach { (sourceName, requiredIdentifier) ->
            val source = remoteSources.firstOrNull { it.name == sourceName }
                ?: throw GradleException(
                    "Remote executor SDK is missing required transport implementation: $sourceName"
                )
            val sourceText = source.readText()
            if (!sourceText.contains("implements RemoteExecutorTransport")) {
                throw GradleException(
                    "Remote executor transport ${displayPathFor(source)} must implement RemoteExecutorTransport"
                )
            }
            if (!sourceText.contains(requiredIdentifier)) {
                throw GradleException(
                    "Remote executor transport ${displayPathFor(source)} must declare $requiredIdentifier"
                )
            }
        }

        val localIdentities = localSources.groupBy { source -> sourceIdentity(localDir, source) }
        val remoteIdentities = remoteSources.groupBy { source -> sourceIdentity(remoteDir, source) }
        val duplicateExecutorClasses = (localIdentities.keys intersect remoteIdentities.keys)
            .sorted()
        if (duplicateExecutorClasses.isNotEmpty()) {
            throw GradleException(
                "Local and remote executor SDK modules contain duplicate Java classes: " +
                    duplicateExecutorClasses.joinToString(", ")
            )
        }

        val productionStubPattern = Regex("""UnsupportedOperationException\s*\(|not implemented""", RegexOption.IGNORE_CASE)
        val productionStubs = (coreSources + localSources + remoteSources)
            .filter { source -> productionStubPattern.containsMatchIn(source.readText()) }
            .map { source -> displayPathFor(source) }
            .sorted()
        if (productionStubs.isNotEmpty()) {
            throw GradleException(
                "Executor SDK production sources must not contain unsupported/not-implemented stubs: " +
                    productionStubs.joinToString(", ")
            )
        }

        val localBuildFile = File(localExecutorBuildFilePath)
        val localBuildText = localBuildFile.readText()
        val remoteDependencyPattern = Regex(
            """quarkus\.quarkus\.(grpc|messaging\.kafka)|mutiny\.vertx\.kafka""",
            RegexOption.IGNORE_CASE,
        )
        if (remoteDependencyPattern.containsMatchIn(localBuildText)) {
            throw GradleException(
                "Local executor SDK build must not depend on distributed gRPC/Kafka transport libraries: " +
                    displayPathFor(localBuildFile)
            )
        }

        println(
            "Validated executor SDK transport ownership: " +
                "local=${displayPathFor(localDir)}, remote=${displayPathFor(remoteDir)}"
        )
    }
}

tasks.register("gamelanValidateMigrations") {
    group = "gamelan profiles"
    description = "Validate standalone/distributed Flyway migration catalog parity."

    val standalonePath = standaloneMigrationDir.absolutePath
    val distributedPath = distributedMigrationDir.absolutePath
    val standaloneDisplayPath = displayPath(standaloneMigrationDir)
    val distributedDisplayPath = displayPath(distributedMigrationDir)

    inputs.dir(standalonePath)
    inputs.dir(distributedPath)

    doLast {
        val migrationFilePattern = Regex("""V(\d+)__(.+)\.sql""")

        fun displayDirectory(directory: File): String =
            when (directory.absolutePath) {
                standalonePath -> standaloneDisplayPath
                distributedPath -> distributedDisplayPath
                else -> directory.path
            }

        fun displayFile(file: File): String =
            displayDirectory(file.parentFile) + "/" + file.name

        fun loadMigrationCatalog(directory: File): List<File> {
            if (!directory.isDirectory) {
                throw GradleException("Gamelan migration directory does not exist: ${displayDirectory(directory)}")
            }

            val files = directory
                .listFiles { file -> file.isFile && migrationFilePattern.matches(file.name) }
                ?.toList()
                ?.sortedBy { file -> file.name }
                ?: emptyList()

            if (files.isEmpty()) {
                throw GradleException(
                    "Gamelan migration directory has no Flyway SQL migrations: ${displayDirectory(directory)}"
                )
            }

            val versions = files.map { file ->
                migrationFilePattern.matchEntire(file.name)?.groupValues?.get(1)?.toInt()
                    ?: throw GradleException("Invalid migration filename: ${displayFile(file)}")
            }
            val duplicateVersions = versions
                .groupBy { it }
                .filterValues { it.size > 1 }
                .keys
                .sorted()
            if (duplicateVersions.isNotEmpty()) {
                throw GradleException(
                    "Duplicate Gamelan migration version(s) in ${displayDirectory(directory)}: " +
                        duplicateVersions.joinToString(", ") { "V$it" }
                )
            }

            val expectedVersions = (versions.min()..versions.max()).toList()
            if (versions.sorted() != expectedVersions) {
                throw GradleException(
                    "Gamelan migrations in ${displayDirectory(directory)} must use contiguous versions. " +
                        "Expected ${expectedVersions.joinToString(", ") { "V$it" }}, " +
                        "found ${versions.sorted().joinToString(", ") { "V$it" }}."
                )
            }

            return files
        }

        fun normalizedSql(file: File): String =
            file.readText().replace("\r\n", "\n")

        val standaloneMigrations = loadMigrationCatalog(File(standalonePath)).associateBy { it.name }
        val distributedMigrations = loadMigrationCatalog(File(distributedPath)).associateBy { it.name }

        val standaloneOnly = (standaloneMigrations.keys - distributedMigrations.keys).sorted()
        val distributedOnly = (distributedMigrations.keys - standaloneMigrations.keys).sorted()
        if (standaloneOnly.isNotEmpty() || distributedOnly.isNotEmpty()) {
            throw GradleException(
                "Standalone/distributed migration catalogs must contain the same migration filenames. " +
                    "Standalone-only: ${standaloneOnly.ifEmpty { listOf("<none>") }.joinToString(", ")}. " +
                    "Distributed-only: ${distributedOnly.ifEmpty { listOf("<none>") }.joinToString(", ")}."
            )
        }

        val contentDrift = standaloneMigrations.keys
            .sorted()
            .filter { name ->
                normalizedSql(standaloneMigrations.getValue(name)) != normalizedSql(distributedMigrations.getValue(name))
            }
        if (contentDrift.isNotEmpty()) {
            throw GradleException(
                "Standalone/distributed migration SQL drift detected: ${contentDrift.joinToString(", ")}"
            )
        }

        println(
            "Validated Gamelan runtime migrations: " +
                "$standaloneDisplayPath <-> $distributedDisplayPath"
        )
    }
}

tasks.register("gamelanProjects") {
    group = "gamelan profiles"
    description = "Print included Gamelan Gradle projects and their directories."

    val projectLines = subprojects
        .map { project -> "${project.path}=${project.projectDir.relativeToOrSelf(rootDir).path}" }
        .sorted()

    doLast {
        projectLines.forEach(::println)
    }
}

tasks.register("gamelanProfileRun") {
    val targetTaskName = requestedTargetTaskName()
    description = "Run -Pgamelan.task selected task for -Pgamelan.profile selected Gamelan project roots."
    configureProfileTask(requestedProfileNames(), targetTaskName)
}

mapOf(
    "gamelanProfileCompile" to "classes",
    "gamelanProfileAssemble" to "assemble",
    "gamelanProfileTest" to "test",
    "gamelanProfileCheck" to "check",
    "gamelanProfileBuild" to "build",
).forEach { (taskName, targetTaskName) ->
    tasks.register(taskName) {
        description = "Run `$targetTaskName` for -Pgamelan.profile selected Gamelan project roots."
        configureProfileTask(requestedProfileNames(), targetTaskName)
    }
}

knownProfiles().forEach { profileName ->
    val suffix = profileName.taskSuffix()
    mapOf(
        "Compile" to "classes",
        "Assemble" to "assemble",
        "Test" to "test",
        "Check" to "check",
        "Build" to "build",
    ).forEach { (taskSuffix, targetTaskName) ->
        tasks.register("gamelan$suffix$taskSuffix") {
            description = "Run `$targetTaskName` for the `$profileName` Gamelan profile."
            configureProfileTask(listOf(profileName), targetTaskName)
        }
    }
}
