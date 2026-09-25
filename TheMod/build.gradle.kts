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
