dependencies {
    implementation(project(":common"))
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)
    implementation(libs.otel.proto)
}

tasks.register<JavaExec>("liveAnalyzer") {
    group = "application"
    description = "Receive live OTLP observations for an application-declared manifest."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.spanlease.analyzer.LiveAnalyzer")
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
