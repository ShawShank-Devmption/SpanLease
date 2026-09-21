import com.google.protobuf.gradle.*

plugins { alias(libs.plugins.protobuf) }

dependencies {
    implementation(project(":common"))
    implementation(project(":instrumentation"))
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)
    testImplementation(platform(libs.otel.bom))
    testImplementation(libs.otel.sdk)
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
