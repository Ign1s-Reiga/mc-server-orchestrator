plugins {
    id("mcorch.kotlin-conventions")
}

// CRI client: generated gRPC stubs + a thin idiomatic Kotlin wrapper.
//
// The proto pipeline is NOT wired yet — that is the next step, and it goes
// through the generate-cri-stubs skill (vendor the proto with recorded
// provenance, then configure `com.google.protobuf`). The versions it needs are
// already pinned in gradle/libs.versions.toml:
//
//   libs.protobuf.protoc          com.google.protobuf:protoc
//   libs.grpc.protocGenJava       io.grpc:protoc-gen-grpc-java
//   libs.grpc.protocGenKotlin     io.grpc:protoc-gen-grpc-kotlin  (needs :jdk8@jar)
//   containerd 2.3.3 -> k8s.io/cri-api v0.36.0
//
// Three things to settle when that lands:
//   1. Generated Kotlin has no explicit visibility modifiers, so it will fail
//      the explicitApi() check inherited from the convention plugin. Generated
//      sources need to be exempted (or moved to their own compilation).
//   2. Everything below stays `implementation`, never `api` — downstream
//      modules must see only the wrapper, never a generated type.
//   3. protoc-gen-grpc-java emits `@javax.annotation.Generated`, which is not
//      in the JDK. If the generated Java fails to compile on that symbol, add
//      jakarta.annotation-api (or org.apache.tomcat:annotations-api) — this is
//      the usual way step 3 of the skill stalls. Not pinned pre-emptively
//      because grpc-java only emits it under some codegen configurations.

dependencies {
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.netty)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.kotlin)
    implementation(libs.kotlinx.coroutines.core)

    // Native epoll, required for the Unix-domain-socket CRI endpoint on Linux.
    runtimeOnly(
        variantOf(libs.netty.transportNativeEpoll) { classifier("linux-x86_64") },
    )
}
