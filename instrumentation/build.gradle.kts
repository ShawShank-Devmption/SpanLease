dependencies {
    implementation(project(":common"))
    implementation(libs.grpc.api)
    implementation(libs.grpc.stub)
    implementation(platform(libs.otel.bom))
    implementation(libs.otel.sdk)
    implementation(libs.otel.sdk.logs)
    implementation(libs.otel.exporter.otlp)
}
