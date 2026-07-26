import org.gradle.api.artifacts.MinimalExternalModuleDependency

plugins {
    id("mcorch.kotlin-conventions")
    alias(libs.plugins.protobuf)
}

// CRI client: generated gRPC stubs + a thin idiomatic Kotlin wrapper.
//
// The vendored proto and its provenance live in cri/PROTO_SOURCE.md and
// src/main/proto/. Regenerating goes through the generate-cri-stubs skill:
//
//   ./gradlew clean :cri:generateProto :cri:build
//
// Generated stubs are build output under build/generated/sources/proto/main/.
// They are never committed, never hand-edited, and never formatted.
//
// Two things that usually need workarounds here and did NOT at these pinned
// versions — verified 2026-07-26, recheck after a codegen bump:
//   * explicitApi() from mcorch.kotlin-conventions still applies to this module
//     and the generated Kotlin compiles under it unchanged. protoc 4.35.1's
//     kotlin builtin and protoc-gen-grpc-kotlin 1.5.0 both emit explicit
//     `public` modifiers and explicit types. No exemption is carved out, so
//     hand-written code in src/main/kotlin is still held to explicit API.
//   * protoc-gen-grpc-java 1.82.1 emits @io.grpc.stub.annotations.GrpcGenerated
//     and no longer emits @javax.annotation.Generated, so there is no need for
//     jakarta.annotation-api / tomcat annotations-api on the compile classpath.

// The protobuf plugin wants "group:name:version[:classifier][@ext]" strings, not
// Dependency objects, so catalog entries are rendered by hand rather than
// inlining version numbers here.
fun Provider<MinimalExternalModuleDependency>.coordinates(): String =
    get().let { "${it.module.group}:${it.module.name}:${it.versionConstraint.requiredVersion}" }

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.coordinates()
    }
    plugins {
        create("grpc") {
            artifact = libs.grpc.protocGenJava.coordinates()
        }
        create("grpckt") {
            // Unlike protoc/protoc-gen-grpc-java, this one is not a native
            // executable but a plain JAR, so it needs an explicit classifier
            // and extension. `jdk8` is the only classifier published.
            artifact = "${libs.grpc.protocGenKotlin.coordinates()}:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                // `java` is on by default; `kotlin` adds the protobuf message
                // DSL builders that the wrapper is written against.
                create("kotlin")
            }
            plugins {
                create("grpc")
                create("grpckt")
            }
        }
    }
}

// Everything here is `implementation`, never `api`. Downstream modules see the
// wrapper in mcorch.cri and nothing else — no grpc, protobuf or netty type is
// allowed to appear on :core's compile classpath. Prove it from the consumer
// side after any change here:
//
//   ./gradlew :core:dependencies --configuration compileClasspath
dependencies {
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.netty)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.kotlin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    // The Java classes (EpollEventLoopGroup, EpollDomainSocketChannel,
    // DomainSocketAddress) are in the plain artifact and are referenced
    // directly: grpc-java's Utils picks EpollSocketChannel for TCP but never
    // selects EpollDomainSocketChannel on its own, so the UDS channel type and
    // event loop group have to be set explicitly. Verified against grpc-java
    // v1.82.x io/grpc/netty/Utils.java.
    implementation(libs.netty.transportNativeEpoll)

    // Native epoll, required for the Unix-domain-socket CRI endpoint on Linux.
    runtimeOnly(
        variantOf(libs.netty.transportNativeEpoll) { classifier("linux-x86_64") },
    )

    // The wrapper's tests run the real client against a fake CRI server over
    // grpc's in-process transport. No socket, no netty, no containerd, so
    // `./gradlew build` stays runnable with no container runtime present.
    testImplementation(libs.grpc.inprocess)
}
