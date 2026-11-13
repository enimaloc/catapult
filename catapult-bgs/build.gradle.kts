import com.google.protobuf.DescriptorProtos
import org.gradle.process.ExecOperations
import org.springframework.boot.gradle.plugin.SpringBootPlugin
import javax.inject.Inject

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Reads and patches bgs.desc in the generation task
        classpath("com.google.protobuf:protobuf-java:4.29.3")
    }
}

plugins {
    `java-library`
    // Not applied (a library, no boot jar): only on the classpath for the BOM coordinates below
    id("org.springframework.boot") apply false
    id("io.spring.dependency-management")
}

description = "catapult-bgs"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencyManagement {
    imports {
        // The Boot version the other modules build with (gradle.properties)
        mavenBom(SpringBootPlugin.BOM_COORDINATES)
    }
}

// The runtime and the compiler must stay on the same version
val protobufVersion = "4.29.3"

dependencies {
    api("com.google.protobuf:protobuf-java:$protobufVersion")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// protoc binary, resolved from Maven Central for the build machine's OS and architecture
val protocClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPart = when {
        os.contains("linux") -> "linux"
        os.contains("mac") || os.contains("darwin") -> "osx"
        os.contains("windows") -> "windows"
        else -> error("Unsupported OS for protoc: $os")
    }
    val archPart = when (arch) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch_64"
        else -> error("Unsupported architecture for protoc: $arch")
    }
    "$osPart-$archPart"
}
val protoc = configurations.create("protoc") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    protoc("com.google.protobuf:protoc:$protobufVersion:$protocClassifier@exe")
}

/**
 * Generates the Java classes of every .proto file packed in bgs.desc (a FileDescriptorSet), so the
 * schemas are not duplicated as .proto sources.
 */
@CacheableTask
abstract class GenerateBgsProto @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val descriptorSet: RegularFileProperty

    @get:InputFiles
    @get:Classpath
    abstract val protocExecutable: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    // Keeps bgs.desc pristine: two files can share a basename (embed_types.proto, ...) and some have no package,
    // which would make protoc write the same Java file twice or generate into the default package
    private fun patch(set: DescriptorProtos.FileDescriptorSet): DescriptorProtos.FileDescriptorSet {
        fun camel(path: String) = path.removeSuffix(".proto").split('/', '_')
            .joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
        fun javaPackage(f: DescriptorProtos.FileDescriptorProto) =
            if (f.options.hasJavaPackage()) f.options.javaPackage
            else if (f.`package`.isEmpty()) "bgs.protocol.unpackaged" else f.`package`
        fun outerClass(f: DescriptorProtos.FileDescriptorProto) =
            if (f.options.hasJavaOuterClassname()) f.options.javaOuterClassname
            else camel(f.name.substringAfterLast('/'))

        val builder = set.toBuilder()
        builder.fileBuilderList.forEach { f ->
            if (f.`package`.isEmpty() && !f.options.hasJavaPackage()) f.optionsBuilder.javaPackage = javaPackage(f.build())
        }
        val colliding = builder.fileBuilderList.groupBy { javaPackage(it.build()) to outerClass(it.build()) }
            .filterValues { it.size > 1 }.values.flatten()
        colliding.forEach { it.optionsBuilder.javaOuterClassname = camel(it.name) }
        return builder.build()
    }

    @TaskAction
    fun generate() {
        val protoc = protocExecutable.singleFile
        protoc.setExecutable(true)
        val desc = descriptorSet.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        val patched = patch(DescriptorProtos.FileDescriptorSet.parseFrom(desc.readBytes()))
        val patchedFile = File(out.parentFile, "bgs-patched.desc")
        patchedFile.writeBytes(patched.toByteArray())
        // descriptor.proto ships with protobuf-java
        val files = patched.fileList.map { it.name }.filter { it != "google/protobuf/descriptor.proto" }
        check(files.isNotEmpty()) { "No .proto file found in $desc" }

        exec.exec {
            commandLine(listOf(protoc.absolutePath, "--descriptor_set_in=${patchedFile.absolutePath}",
                "--java_out=${out.absolutePath}") + files)
        }
    }
}

val generateBgsProto = tasks.register<GenerateBgsProto>("generateBgsProto") {
    descriptorSet = layout.projectDirectory.file("src/main/resources/bgs.desc")
    protocExecutable.from(protoc)
    outputDir = layout.buildDirectory.dir("generated/sources/bgs-proto/java")
}

sourceSets.main {
    java.srcDir(generateBgsProto)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
