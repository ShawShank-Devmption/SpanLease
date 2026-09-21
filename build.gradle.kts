import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    base
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.protobuf) apply false
}

val javaVersion = libs.versions.java.get().toInt()
val formatterVersion = libs.versions.google.java.format.get()
val junit = libs.junit.jupiter
val launcher = libs.junit.platform.launcher
val assertj = libs.assertj.core
val jqwik = libs.jqwik

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(javaVersion)
        options.encoding = "UTF-8"
    }
    dependencies {
        "testImplementation"(junit)
        "testImplementation"(assertj)
        "testImplementation"(jqwik)
        "testRuntimeOnly"(launcher)
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("passed", "skipped", "failed") }
    }
    extensions.configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat(formatterVersion)
        }
    }
}

tasks.named("build") { dependsOn(subprojects.map { "${it.path}:build" }) }
tasks.named("check") { dependsOn(subprojects.map { "${it.path}:check" }) }

val verifyModuleBoundaries by tasks.registering {
    group = "verification"
    description = "Resolve classpaths and enforce detector module isolation."
    doLast {
        val allowedProjects = mapOf(
            ":common" to emptySet<String>(),
            ":instrumentation" to setOf(":common"),
            ":analyzer" to setOf(":common"),
            ":testbed" to setOf(":common", ":instrumentation")
        )
        allowedProjects.forEach { (path, allowed) ->
            val owner = project(path)
            listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach { name ->
                val configuration = owner.configurations.getByName(name)
                configuration.resolve()
                configuration.incoming.resolutionResult.allComponents.forEach { component ->
                    when (val id = component.id) {
                        is ProjectComponentIdentifier -> check(id.projectPath == path || id.projectPath in allowed) {
                            "$path must not depend on ${id.projectPath} ($name)"
                        }
                        is ModuleComponentIdentifier -> if (path == ":common" && !name.startsWith("test")) {
                            check(id.group.startsWith("com.fasterxml.jackson")) {
                                "common permits only JDK/Jackson in production, found $id"
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyModuleBoundaries) }

tasks.wrapper {
    gradleVersion = libs.versions.gradle.get()
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = libs.versions.distribution.sha256.get()
}
