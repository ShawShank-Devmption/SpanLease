dependencies {
    implementation(project(":common"))
    implementation(project(":analyzer"))
    implementation(platform(libs.otel.bom))
    implementation(libs.otel.sdk)
}

tasks.register<JavaExec>("replay") {
    group = "application"
    description = "Offline baseline information comparison: --args='observations.replay.json baselines.jsonl'."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.spanlease.baselines.BaselineReplay")
}

tasks.processTestResources {
    from(rootProject.layout.projectDirectory.dir("fixtures/replays")) { into("replays-v2") }
}
