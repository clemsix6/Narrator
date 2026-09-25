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
}

// Loom's `include` is not transitive: nest every resolved library jar explicitly. Kotlin itself
// (stdlib, reflect, coroutines) is provided at runtime by Fabric Language Kotlin, so it is skipped.
fun isProvidedByKotlinMod(group: String, name: String) =
    group.startsWith("org.jetbrains.kotlin") || (group == "org.jetbrains" && name == "annotations")

afterEvaluate {
    bundled.resolvedConfiguration.resolvedArtifacts
        .map { it.moduleVersion.id }
        .filterNot { isProvidedByKotlinMod(it.group, it.name) }
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

// One JUnit XML report per run, listing every in-game test with its result.
loom.runs.named("gameTest") {
    val report = layout.buildDirectory.file("reports/gametest/TEST-gametest.xml").get().asFile
    systemProperties.put("fabric-api.gametest.report-file", report.absolutePath)
}

// Each in-game run starts from an empty world and config (Loom 1.18 only clears client test runs).
val cleanGameTestRun = tasks.register<Delete>("cleanGameTestRun") {
    delete(layout.buildDirectory.dir("run/gameTest"))
}
tasks.matching { it.name == "runGameTest" }.configureEach { dependsOn(cleanGameTestRun) }

// The in-game tests reuse the fake Anthropic server of the unit tests.
sourceSets.named("gametest") {
    compileClasspath += sourceSets.test.get().output
    runtimeClasspath += sourceSets.test.get().output
}

tasks.test {
    useJUnitPlatform()
    // Minecraft's log4j configuration writes logs/ into the working directory: keep it under build/.
    workingDir = layout.buildDirectory.dir("test-run").get().asFile
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
