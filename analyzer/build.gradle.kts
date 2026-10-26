dependencies {
    implementation(project(":common"))
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)
    implementation(libs.otel.proto)
    testImplementation(platform(libs.otel.bom))
    testImplementation(libs.otel.sdk.logs)
    testImplementation(libs.otel.exporter.otlp)
}

tasks.register<JavaExec>("modelMvp") {
    group = "verification"
    description = "Run the pre-G0 in-memory detector model demonstration."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.spanlease.analyzer.ModelMvpDemo")
}

tasks.register<JavaExec>("productionDemo") {
    group = "verification"
    description = "Run schema-v2 certified-cut and graph-reduction demo scenarios."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.spanlease.analyzer.ProductionDemo")
}

 tasks.register<JavaExec>("replay") {
    workingDir(rootProject.projectDir)
    group = "verification"
    description = "Re-evaluate a recorded finite session: --args='input.json output.jsonl'."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.spanlease.analyzer.ReplayMain")
}

tasks.processTestResources {
    from(rootProject.layout.projectDirectory.dir("fixtures/replays")) { into("replays-v2") }
}
