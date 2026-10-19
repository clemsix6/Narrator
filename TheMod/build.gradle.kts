import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

base {
    archivesName = providers.gradleProperty("archives_base_name")
}

repositories {
    mavenCentral()
}

// Third-party libraries shipped inside the mod jar as nested jars.
val bundled: Configuration = configurations.create("bundled")

configurations.implementation {
    extendsFrom(bundled)
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    bundled("com.anthropic:anthropic-java:${property("anthropic_java_version")}")
    bundled("org.duckdb:duckdb_jdbc:${property("duckdb_version")}")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:${property("junit_version")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Virtual time for debounces, heartbeats and deadlines.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${property("kotlinx_coroutines_version")}")
}

// Loom's `include` is not transitive: nest every resolved library jar explicitly, except what the
// server already provides at runtime: Kotlin itself (stdlib, reflect, coroutines) comes with Fabric
// Language Kotlin, and SLF4J with Minecraft.
fun isProvidedAtRuntime(group: String, name: String) =
    group.startsWith("org.jetbrains.kotlin") || (group == "org.jetbrains" && name == "annotations") || group == "org.slf4j"

afterEvaluate {
    bundled.resolvedConfiguration.resolvedArtifacts
        .map { it.moduleVersion.id }
        .filterNot { isProvidedAtRuntime(it.group, it.name) }
        .forEach { dependencies.add("include", "${it.group}:${it.name}:${it.version}") }
}

// On Windows, Java's NIO pipes create AF_UNIX sockets in %TEMP% and fail with "Unable to establish
// loopback connection" when that path is not ASCII (a user profile such as "Clément"). The dev server
// gets an ASCII temp directory, including when launched from an IDE run configuration.
val asciiTempDir: File? =
    if (System.getProperty("os.name").startsWith("Windows") && System.getProperty("user.home").any { it.code > 127 }) {
        File(System.getenv("PUBLIC") ?: "C:\\Users\\Public", "narrator2-tmp").apply { mkdirs() }
    } else {
        null
    }

loom {
    mods {
        register("narrator2") {
            sourceSet(sourceSets.main.get())
        }
    }
    runs {
        named("server") {
            runDirectory = layout.projectDirectory.dir("../TheServer")
            // DuckDB loads its native library through System.load (restricted method since JDK 24).
            jvmArguments.add("--enable-native-access=ALL-UNNAMED")
            asciiTempDir?.let {
                environmentVars.put("TEMP", it.absolutePath)
                environmentVars.put("TMP", it.absolutePath)
            }
        }
    }
}

// The LLMGW key file, `.secrets/llmgw.key` at the root of the checkout. A git worktree has no `.secrets/`
// (it is not versioned): its key is the main checkout's, found through `git rev-parse --git-common-dir`.
// Only the file's path is handled here; the key itself is read when a task runs.
val llmgwKeyFile: File by lazy {
    val local = layout.projectDirectory.file("../.secrets/llmgw.key").asFile
    if (local.isFile) return@lazy local
    val commonDir = runCatching {
        providers.exec {
            workingDir = layout.projectDirectory.asFile
            commandLine("git", "rev-parse", "--path-format=absolute", "--git-common-dir")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrDefault("")
    commonDir.takeIf { it.isNotEmpty() }?.let { File(it).parentFile.resolve(".secrets/llmgw.key") }?.takeIf { it.isFile } ?: local
}

// The LLMGW key: NARRATOR_LLMGW_KEY when Gradle has it, else llmgwKeyFile. The server runs (runServer,
// runLiveGameTest) get it when they start, through the environment only: never on a command line, in a
// generated run configuration or in a log.
fun llmgwKey(): String? = System.getenv("NARRATOR_LLMGW_KEY")?.trim()?.ifEmpty { null }
    ?: llmgwKeyFile.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

tasks.matching { it.name == "runServer" }.configureEach {
    doFirst { llmgwKey()?.let { (this as JavaExec).environment("NARRATOR_LLMGW_KEY", it) } }
}

// In-game tests (Fabric GameTest). `src/gametest` is a separate test mod; `runGameTest` runs it on a
// headless GameTest server in build/run/gameTest, and `check` depends on it.
// Must come after the "server" run above: the gameTest run inherits its JVM arguments and TEMP.
// Fabric API's GameTest server starts without an accepted EULA, so Loom's `eula` option stays off.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "narrator2-gametest"
        enableGameTests = true
        enableClientGameTests = false
        eula = false
    }
}

// One JUnit XML report per run, listing every in-game test with its result. The in-game tests read
// their (fake) LLMGW key from the environment, like the mod does in production. The real key is
// blanked (an empty value is no key): the in-game tests never reach LLMGW, for the Director or the
// reflexes, even on a machine where NARRATOR_LLMGW_KEY is set.
loom.runs.named("gameTest") {
    val report = layout.buildDirectory.file("reports/gametest/TEST-gametest.xml").get().asFile
    systemProperties.put("fabric-api.gametest.report-file", report.absolutePath)
    environmentVars.put("NARRATOR2_GAMETEST_KEY", "gametest-key-6f1c")
    environmentVars.put("NARRATOR_LLMGW_KEY", "")
}

// Each in-game run starts from an empty world and config (Loom 1.18 only clears client test runs).
val cleanGameTestRun = tasks.register<Delete>("cleanGameTestRun") {
    delete(layout.buildDirectory.dir("run/gameTest"))
}
tasks.matching { it.name == "runGameTest" }.configureEach { dependsOn(cleanGameTestRun) }

// The live acceptances against the owner's real LLMGW server (T22 for M2, M4-24 for the mechanics): a GameTest
// server that runs only the in-game live tests (`LiveDirectorGameTests`, `LiveMechanicsGameTests`, filtered by
// id), with the real key and the SSH tunnel on localhost:8088. Never part of `build` or `check`; costs real
// tokens. `-Pnarrator2.live.dry` runs the same scenarios without a key (the Director stays asleep), to check
// them for free. `-Pnarrator2.live.filter=narrator2-gametest:live_mechanics_*` picks the tests (`live_*` by
// default), `-Pnarrator2.live.parts=L5,L6` the parts of the mechanics' evals, `-Pnarrator2.live.budget=6` the
// dollars a run may spend (see `LiveMechanicsGameTests`).
loom.runs.register("liveGameTest") {
    inherit(loom.runs.getByName("gameTest"))
    // No IDE run configuration: it would write the run's environment, hence the key, into a file.
    generateRunConfig = false
    runDirectory = layout.buildDirectory.dir("run/liveGameTest")
    // Replaces the inherited empty key. Loom applies a run's variables when its task starts (after doFirst),
    // so the key goes in as a provider: read only when the run's variables are needed, never logged.
    val dry = providers.gradleProperty("narrator2.live.dry").isPresent
    environmentVars.put("NARRATOR_LLMGW_KEY", providers.provider { if (dry) "" else llmgwKey().orEmpty() })
    systemProperties.put("fabric-api.gametest.filter", providers.gradleProperty("narrator2.live.filter").orElse("narrator2-gametest:live_*"))
    systemProperties.put("narrator2.live.parts", providers.gradleProperty("narrator2.live.parts").orElse(""))
    systemProperties.put("narrator2.live.budget", providers.gradleProperty("narrator2.live.budget").orElse(""))
    systemProperties.put("fabric-api.gametest.report-file", layout.buildDirectory.file("reports/liveGameTest/TEST-live.xml").get().asFile.absolutePath)
    systemProperties.put("narrator2.live", "true")
}
val cleanLiveGameTestRun = tasks.register<Delete>("cleanLiveGameTestRun") {
    delete(layout.buildDirectory.dir("run/liveGameTest"))
}
tasks.matching { it.name == "runLiveGameTest" }.configureEach { dependsOn(cleanLiveGameTestRun) }
tasks.register("liveGameTest") {
    description = "Runs the live acceptances (M2 evening, M4 mechanics) on a GameTest server against the real LLMGW server (SSH tunnel on localhost:8088)."
    group = "verification"
    dependsOn("runLiveGameTest")
}

// The in-game tests reuse the fake Anthropic server of the unit tests.
sourceSets.named("gametest") {
    compileClasspath += sourceSets.test.get().output
    runtimeClasspath += sourceSets.test.get().output
}

tasks.withType<Test>().configureEach {
    // Minecraft's log4j configuration writes logs/ into the working directory: keep it under build/.
    workingDir = layout.buildDirectory.dir("$name-run").get().asFile
    doFirst { workingDir.mkdirs() }
    asciiTempDir?.let {
        environment("TEMP", it.absolutePath)
        environment("TMP", it.absolutePath)
    }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.test {
    useJUnitPlatform { excludeTags("live") }
}

// Tests against the owner's real LLMGW server, through the SSH tunnel (see CLAUDE.md). Never part of
// `test`, `build` or `check`. The key comes from NARRATOR_LLMGW_KEY or .secrets/llmgw.key (llmgwKeyFile,
// the main checkout's from a worktree), read by the tests themselves: only the file's path is passed here.
tasks.register<Test>("liveTest") {
    description = "Runs the live tests against the real LLMGW server (needs the SSH tunnel on localhost:8088)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    doFirst { systemProperty("narrator2.live.keyFile", llmgwKeyFile.absolutePath) }
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}
