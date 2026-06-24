import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
}

val gamelanGroup = providers.gradleProperty("gamelan.group")
    .orElse("tech.kayys.gamelan")
val gamelanVersion = providers.gradleProperty("gamelan.version")
    .orElse("0.1.0")
val javaRelease = providers.gradleProperty("gamelan.java.release")
    .map(String::toInt)
    .orElse(25)
val publishEnabled = providers.gradleProperty("gamelan.publish.enabled")
    .map(String::toBoolean)
    .orElse(false)
val localRepositoryEnabled = providers.gradleProperty("gamelan.repositories.local.enabled")
    .map(String::toBoolean)
    .orElse(false)
val gollekRepositoryEnabled = providers.gradleProperty("gamelan.repositories.gollek.enabled")
    .map(String::toBoolean)
    .orElse(true)
val snapshotRepositoryEnabled = providers.gradleProperty("gamelan.repositories.snapshots.enabled")
    .map(String::toBoolean)
    .orElse(true)
val jbossRepositoryEnabled = providers.gradleProperty("gamelan.repositories.jboss.enabled")
    .map(String::toBoolean)
    .orElse(true)
val preferredLz4Module = providers.gradleProperty("gamelan.dependencies.lz4.preferred-module")
    .orElse("at.yawk.lz4:lz4-java:1.10.1")

repositories {
    if (localRepositoryEnabled.get()) {
        mavenLocal()
    }

    mavenCentral()

    if (gollekRepositoryEnabled.get()) {
        maven {
            name = "gollekGitHub"
            url = uri(
                providers.gradleProperty("gamelan.repositories.gollek.url")
                    .orElse("https://maven.pkg.github.com/bhangun/gollek")
                    .get()
            )
        }
    }

    if (snapshotRepositoryEnabled.get()) {
        maven {
            name = "sonatypeSnapshots"
            url = uri(
                providers.gradleProperty("gamelan.repositories.snapshots.url")
                    .orElse("https://oss.sonatype.org/content/repositories/snapshots")
                    .get()
            )
        }
    }

    if (jbossRepositoryEnabled.get()) {
        maven {
            name = "jbossPublic"
            url = uri(
                providers.gradleProperty("gamelan.repositories.jboss.url")
                    .orElse("https://repository.jboss.org/nexus/content/groups/public")
                    .get()
            )
        }
    }
}

dependencies {
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

configurations.configureEach {
    resolutionStrategy.capabilitiesResolution.withCapability("org.lz4:lz4-java") {
        select(preferredLz4Module.get())
        because("Quarkus Kafka 3.32 brings the at.yawk.lz4 replacement while Kafka clients still request org.lz4")
    }
}

group = gamelanGroup.get()
version = gamelanVersion.get()
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaRelease.get())
    }
}

if (publishEnabled.get()) {
    pluginManager.apply("maven-publish")
    extensions.configure<PublishingExtension>("publishing") {
        publications.create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

tasks.withType<JavaCompile>() {
    options.encoding = "UTF-8"
    options.release.set(javaRelease)
    options.compilerArgs.add("-parameters")
}

tasks.withType<Javadoc>() {
    options.encoding = "UTF-8"
}

val runtimeHttpTestsEnabled = providers.systemProperty("gamelan.runtime.http.tests")
    .orElse(providers.gradleProperty("gamelan.runtime.http.tests"))
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

tasks.withType<Test>() {
    useJUnitPlatform {
        if (!runtimeHttpTestsEnabled.get()) {
            excludeTags("runtime-http")
        }
    }
    if (!runtimeHttpTestsEnabled.get()) {
        exclude("**/tech/kayys/gamelan/runtime/**/resource/*ResourceTest.class")
        exclude("**/tech/kayys/gamelan/runtime/**/resource/*ResourceIT.class")
        exclude("**/tech/kayys/gamelan/runtime/**/resource/GamelanRuntimeTestSuite.class")
    }
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
