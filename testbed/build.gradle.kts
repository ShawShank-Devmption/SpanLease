import com.google.protobuf.gradle.*

plugins { alias(libs.plugins.protobuf) }

// The harness launches the analyzer as a separate process (A-HARNESS). This configuration only
// resolves that process's classpath; analyzer classes never enter testbed's compile or runtime
// classpaths, which :verifyModuleBoundaries enforces.
val analyzerRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    implementation(project(":common"))
    implementation(project(":instrumentation"))
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)
    analyzerRuntime(project(":analyzer"))
    testImplementation(platform(libs.otel.bom))
    testImplementation(libs.otel.sdk)
    testImplementation(libs.otel.sdk.logs)
    testImplementation(libs.otel.exporter.otlp)
    testImplementation(libs.otel.proto)
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
    plugins {
        create("grpc") { artifact = libs.grpc.genjava.get().toString() }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                create("grpc") { option("@generated=omit") }
            }
        }
    }
}

val analyzerClasspath = CommandLineArgumentProvider {
    listOf("-Dspanlease.analyzer.classpath=${analyzerRuntime.asPath}")
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Real gRPC → OTLP → analyzer-process litmus tests (I-LITMUS)."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    inputs.files(analyzerRuntime)
    jvmArgumentProviders.add(analyzerClasspath)
    systemProperty("spanlease.integration.runs", layout.buildDirectory.dir("integration-runs").get().asFile.absolutePath)
    shouldRunAfter(tasks.test)
}

tasks.register<JavaExec>("liveDemo") {
    group = "application"
    description = "Run the closed-ring and spare-capacity scenarios over real gRPC with live analysis."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.spanlease.testbed.LiveDemo")
    inputs.files(analyzerRuntime)
    jvmArgumentProviders.add(analyzerClasspath)
    workingDir = layout.buildDirectory.get().asFile
    doFirst { workingDir.mkdirs() }
}

tasks.register<JavaExec>("scenario") {
    workingDir(rootProject.projectDir)
    group = "application"
    description = "Finite workload runner: --args='workload bare|instrumented output-directory seed'."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.spanlease.testbed.ScenarioRunner")
    inputs.files(analyzerRuntime)
    jvmArgumentProviders.add(analyzerClasspath)
}

tasks.register<JavaExec>("experiments") {
    workingDir(rootProject.projectDir)
    group = "application"
    description = "Run a recorded finite plan: --args='pilot|matrix repetitions output-directory'."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.spanlease.testbed.ExperimentMain")
    inputs.files(analyzerRuntime)
    jvmArgumentProviders.add(analyzerClasspath)
}
