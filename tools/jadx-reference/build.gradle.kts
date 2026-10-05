import java.util.Properties
import java.io.ByteArrayOutputStream
import javax.inject.Inject

// JVM-only oracle adapter. Compile the original reference sources in place; never copy them into
// the KMP engine or modify the read-only checkout. Include both original bytecode input plugins.
plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

val baseline = Properties().apply {
    file("baseline.properties").inputStream().use { load(it) }
}
val baselineRevision = baseline.getProperty("revision")
val referenceDir = rootProject.file("reference/jadx")
val inputPluginModules = listOf("jadx-plugins/jadx-dex-input", "jadx-plugins/jadx-java-input")
val referenceModules = listOf(
    "jadx-core",
    "jadx-commons/jadx-zip",
    "jadx-plugins/jadx-input-api",
) + inputPluginModules
sourceSets.main {
    java.setSrcDirs(referenceModules.map { referenceDir.resolve("$it/src/main/java") })
    resources.setSrcDirs(referenceModules.map { referenceDir.resolve("$it/src/main/resources") })
    // Both plugins have the same service filename; merge providers instead of dropping either one.
    resources.exclude("META-INF/services/jadx.api.plugins.JadxPlugin")
}

// Versions from this exact commit's library convention and each included module build script.
dependencies {
    api("org.slf4j:slf4j-api:2.0.18")
    compileOnly("org.jetbrains:annotations:26.1.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.android.tools.smali:smali-baksmali:3.0.9") {
        exclude(group = "com.beust", module = "jcommander")
    }
    implementation("com.google.guava:guava:33.6.0-jre")
    implementation("io.github.skylot:raung-disasm:0.1.1")
}

abstract class VerifyJadxBaseline : DefaultTask() {
    @get:Input abstract val revision: Property<String>
    @get:Input abstract val repository: Property<String>
    @get:Internal abstract val checkout: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction
    fun verify() {
        val reference = checkout.get().asFile
        check(reference.resolve(".git").exists()) {
            "Missing reference/jadx. Clone ${repository.get()} there and checkout ${revision.get()}."
        }
        fun git(vararg args: String): String {
            val output = ByteArrayOutputStream()
            execOperations.exec {
                commandLine(listOf("git", "-C", reference.absolutePath) + args)
                standardOutput = output
            }
            return output.toString("UTF-8").trim()
        }
        check(git("rev-parse", "HEAD") == revision.get()) {
            "The oracle requires original jadx commit ${revision.get()}; reference/jadx is at a different revision."
        }
        check(git("status", "--porcelain", "--untracked-files=normal").isEmpty()) {
            "reference/jadx has local changes; the oracle requires the unmodified original baseline."
        }
    }
}

val verifyBaseline = tasks.register<VerifyJadxBaseline>("verifyBaseline") {
    group = "verification"
    description = "Require a clean jadx checkout at the original corpus baseline commit."
    revision.set(baselineRevision)
    repository.set(baseline.getProperty("repository"))
    checkout.set(referenceDir)
    // No outputs: always verify, including when compilation and jars are up to date.
}

abstract class MergeJadxPluginServices : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val descriptors: ConfigurableFileCollection
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun merge() {
        val providers = descriptors.files.flatMap { it.readLines(Charsets.UTF_8) }
            .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        check(providers.isNotEmpty()) { "The pinned reference must expose input plugin providers" }
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(providers.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }
}

val mergePluginServices = tasks.register<MergeJadxPluginServices>("mergePluginServices") {
    dependsOn(verifyBaseline)
    descriptors.from(inputPluginModules.map {
        referenceDir.resolve("$it/src/main/resources/META-INF/services/jadx.api.plugins.JadxPlugin")
    })
    output.set(layout.buildDirectory.file("generated/plugin-services/jadx.api.plugins.JadxPlugin"))
}

tasks.compileJava {
    dependsOn(verifyBaseline)
    options.release.set(11)
    options.encoding = "UTF-8"
}
tasks.processResources {
    dependsOn(verifyBaseline)
    from("baseline.properties") { into("jadxmp-reference") }
    from(mergePluginServices.flatMap { it.output }) { into("META-INF/services") }
}
tasks.jar {
    dependsOn(verifyBaseline)
    manifest.attributes("jadx-version" to baselineRevision)
}
