import java.util.zip.ZipFile

plugins {
    id("mcorch.kotlin-conventions")
}

// The Velocity plugin that gives the orchestrator a control channel into a
// running proxy — the counterparty three steps of the drain protocol never had.
//
// ## Why this is a module and not part of :core
//
// Nothing here runs in the orchestrator's process. The JAR this module builds is
// mounted into the *proxy's* container and loaded by Velocity, on the far side of
// a container boundary and a version boundary. It therefore depends on no other
// module in this repo, and the dependency arrow that matters points the other
// way: `:core` may depend on this module for the protocol constants under
// `mcorch.velocity.control` (they are deliberately Velocity-free), so that the
// wire contract has exactly one definition and `:core` cannot hardcode a copy of
// the version string. Do not add a dependency from here onto :schema or :core —
// it would put orchestrator types on a proxy's classloader for no gain.
//
// ## The JVM target is the proxy's, and it happens to match
//
// Every other module here targets JVM 25 because this repo does. This one
// targets JVM 25 because velocity-api 4.0.0 does — its module metadata declares
// `org.gradle.jvm.version = 25`, so the proxy that loads this JAR is running a
// JVM 25 too. The agreement is a coincidence of the current pin, not a rule:
// velocity-api 3.5.1 declares 21, so pinning back to the 3.x line means setting
// a lower `jvmTarget` here as well. Gradle refuses to resolve the dependency
// rather than letting the mismatch through, which is why there is no override in
// this file and why one would be needed if the pin moved.
//
// ## The plugin JAR is a second artifact, not this module's `jar`
//
// Velocity gives each plugin its own classloader and provides nothing but its own
// API and that API's transitive dependencies. kotlin-stdlib is not among them, so
// it has to travel in the JAR — a whole stdlib, ~1100 entries.
//
// That fat artifact is `pluginJar` and NOT the ordinary `jar`, deliberately. The
// convention plugin applies `java-library`, so `jar` is what `runtimeElements`
// publishes to consumers, and CLAUDE.md explicitly blesses `:core` depending on
// this module for the wire contract. Had `jar` been the fat one, the day that
// arrow is drawn is the day `:app`'s runtime classpath gets a second copy of
// kotlin-stdlib and classpath order decides which wins. Consumers get the thin
// jar; the proxy gets `pluginJar`.
//
// Everything Velocity *does* provide — Adventure, Gson, Guava, Guice, SnakeYAML,
// slf4j — is `compileOnly` and is deliberately absent from the plugin JAR: a
// second Adventure on the plugin's classloader is a runtime failure this build
// could not see. `verifyPluginJar` is what keeps that true, because the
// difference between a correct plugin JAR and an unloadable one is invisible in a
// green `test` task.

dependencies {
    // Provided by the proxy at runtime. `compileOnly` is not an optimisation:
    // shipping velocity-api inside a Velocity plugin is how a plugin ends up
    // running against a different API than the proxy it is loaded into.
    compileOnly(libs.velocity.api)
}

// Velocity reads `velocity-plugin.json` from the JAR root to find the main class.
// Upstream generates it with a Java annotation processor driven by `@Plugin`,
// which would mean kapt for a Kotlin module — a Kotlin compiler plugin whose
// version has to track the Kotlin version, to produce eleven lines of JSON. It
// is checked in under src/main/resources instead, and `PluginDescriptorTest`
// asserts every field of it against the constants in ControlProtocol so the two
// cannot drift.

val pluginJar =
    tasks.register<Jar>("pluginJar") {
        group = "build"
        description = "The JAR Velocity loads: this module's classes plus kotlin-stdlib."
        archiveBaseName = "mcorch-velocity-control"
        archiveClassifier = "plugin"

        from(sourceSets.main.map { it.output })
        // kotlin-stdlib and nothing else: compileOnly dependencies are absent from
        // runtimeClasspath by construction, which is what keeps the proxy-provided
        // libraries out of here.
        from(
            configurations.runtimeClasspath.map { classpath ->
                classpath.filter { it.name.endsWith(".jar") }.map { zipTree(it) }
            },
        )

        // Signatures over a rewritten JAR are invalid, and a module descriptor from
        // a dependency would claim to describe this artifact.
        exclude(
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/versions/*/module-info.class",
            "module-info.class",
        )
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

tasks.named("assemble") { dependsOn(pluginJar) }

// How another module gets the *plugin* JAR without getting it on a classpath.
//
// `:app`'s integration suite has to stage a real artefact where a node looks for
// one, and the only honest source is the task that builds it. It travels through
// its own configuration rather than through `runtimeElements` for the reason the
// note above gives: the thin `jar` is what consumers compile and run against, and
// a fat one arriving on `:app`'s runtime classpath would put a second
// kotlin-stdlib there and let classpath order decide which wins.
val controlPlugin: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(controlPlugin.name, pluginJar)
}

// A plugin JAR that Velocity cannot load fails in a way no unit test reaches:
// the classes are all correct and the *packaging* is wrong. These are the three
// packaging mistakes that produce a proxy which starts fine and simply has no
// control endpoint, so they are checked on every `build`.
val verifyPluginJar =
    tasks.register("verifyPluginJar") {
        group = "verification"
        description =
            "Checks the plugin JAR is loadable by Velocity: descriptor present, stdlib bundled, proxy libraries absent."
        val jarFile = pluginJar.flatMap { it.archiveFile }
        inputs.file(jarFile)
        // Resolved outside doLast: a Provider captured into the action is the shape
        // that breaks when the configuration cache is enabled (see gradle.properties
        // — it is off only until :cri:generateProto is green).
        val artifact = jarFile.map { it.asFile }
        doLast {
            val entries =
                ZipFile(artifact.get()).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .toSet()
                }
            val problems = mutableListOf<String>()

            // Without this Velocity does not know the JAR is a plugin at all.
            if ("velocity-plugin.json" !in entries) {
                problems += "velocity-plugin.json is missing from the JAR root"
            }
            // Without this every class in the plugin fails to link on load.
            if ("kotlin/Unit.class" !in entries) {
                problems += "kotlin-stdlib is not bundled (kotlin/Unit.class absent)"
            }
            // With any of these the plugin runs against its own copy of a library
            // the proxy also has, on a classloader that has both.
            val provided =
                entries.filter { name ->
                    // Every library the comment above claims is kept out. The list
                    // used to be four of the six, and slf4j — the one this module
                    // actually imports — was among the two it missed.
                    name.startsWith("com/velocitypowered/") ||
                        name.startsWith("net/kyori/") ||
                        name.startsWith("com/google/gson/") ||
                        name.startsWith("com/google/inject/") ||
                        name.startsWith("com/google/common/") ||
                        name.startsWith("org/yaml/") ||
                        name.startsWith("org/slf4j/")
                }
            if (provided.isNotEmpty()) {
                problems += "proxy-provided libraries are bundled: ${provided.take(5)}"
            }

            if (problems.isNotEmpty()) {
                error("plugin JAR is not loadable by Velocity:\n" + problems.joinToString("\n") { "  - $it" })
            }
        }
    }

tasks.named("check") { dependsOn(verifyPluginJar) }
