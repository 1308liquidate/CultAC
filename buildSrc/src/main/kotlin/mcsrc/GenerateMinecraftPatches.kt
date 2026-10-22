package mcsrc

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.ExecOperations
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.PathWalkOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

/**
 * A local, greppable take on mcsrc.dev for checking logic against older clients: writes per-class patches of the
 * decompiled Minecraft client from [maxVersion] back to every release down to [minVersion]. A [selectedVersion]
 * outside that release range (a snapshot newer than [maxVersion], typically) only gets a patch from [maxVersion].
 *
 * Only the patches are written out; [maxVersion] itself is the NMS the project already compiles against. Patches to an
 * obfuscated release compare generated local variable and parameter names on both sides (see [LocalNames]), so they
 * hold logic changes rather than the renames between obfuscated and unobfuscated releases.
 *
 * Downloads, remapped jars and decompiled trees are cached in [cacheDir], so other worktrees and later runs only
 * decompile what they have not seen yet.
 */
@OptIn(ExperimentalPathApi::class)
@UntrackedTask(because = "Manages its own cache and output; the decompiled trees are too large to snapshot.")
abstract class GenerateMinecraftPatches : DefaultTask() {
    @get:Input
    abstract val minVersion: Property<String>

    @get:Input
    abstract val maxVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val selectedVersion: Property<String>

    @get:Input
    abstract val decompilerMaxHeap: Property<String>

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:Internal
    abstract val outputDir: DirectoryProperty

    @get:Nested
    abstract val javaLauncher: Property<JavaLauncher>

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val toolchains: JavaToolchainService

    init {
        javaLauncher.convention(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    }

    private val cache: Path get() = cacheDir.get().asFile.toPath()
    private val sourcesCache: Path get() = cache.resolve("sources")

    @TaskAction
    fun generate() {
        val meta = MojangMeta(cache.resolve("mojang"))
        val versions = meta.manifest().versions.associateBy { it.id }
        fun lookup(id: String) = versions[id] ?: throw GradleException("Minecraft version '$id' is not in Mojang's version manifest")

        val min = lookup(minVersion.get())
        val max = lookup(maxVersion.get())
        if (min.type != "release" || max.type != "release") {
            throw GradleException("The pinned min (${min.id}) and max (${max.id}) versions must both be releases")
        }
        if (min.released > max.released) throw GradleException("Min version ${min.id} was released after max version ${max.id}")

        val older = versions.values
            .filter { it.type == "release" && it.released >= min.released && it.released < max.released }
            .sortedByDescending { it.released }
        val selected = selectedVersion.orNull?.takeIf { it.isNotBlank() }?.let(::lookup)
        val extra = selected?.takeIf { it != max && it !in older }

        val output = outputDir.get().asFile.toPath()
        claimOutput(output)

        fun obfuscated(entry: VersionEntry) = "client_mappings" in meta.versionMeta(entry).downloads
        val patchDirs = (older + listOfNotNull(extra)).map { target ->
            val names = if (obfuscated(max) || obfuscated(target)) LocalNames.GENERATED else LocalNames.MOJANG
            val maxSources = decompile(meta, max, names)
            val targetSources = decompile(meta, target, names)
            val dir = output.resolve("${max.id}_to_${target.id}")
            val stamp = "$PATCH_FORMAT\n${stampOf(maxSources)}\n${stampOf(targetSources)}"
            if (stampOf(dir) != stamp) {
                logger.lifecycle("mcsrc: writing patches ${max.id} -> ${target.id}")
                dir.deleteRecursively()
                SourcePatches.write(sourcesCache.resolve(names.dir), max.id, target.id, dir)
                dir.resolve(STAMP).writeText(stamp)
            }
            dir.name
        }

        prune(output, patchDirs.toSet() + setOf(MARKER, README))
        writeReadme(output, max, min, older, extra)
        logger.lifecycle("mcsrc: ${patchDirs.size} patch sets from ${max.id} are in $output")
    }

    /** Returns the decompiled tree of [entry], decompiling it unless the cache already holds this exact output. */
    private fun decompile(meta: MojangMeta, entry: VersionEntry, names: LocalNames): Path {
        val target = sourcesCache.resolve("${names.dir}/${entry.id}")
        val options = VINEFLOWER_OPTIONS + names.options
        // Changes whenever the decompiled output would, so cached trees and the patches made from them are rebuilt.
        val stamp = "format=2;${VINEFLOWER_JAR.name};${options.joinToString(" ")}\n${entry.sha1}"
        if (stampOf(target) == stamp) return target

        val versionMeta = meta.versionMeta(entry)
        val jarDir = cache.resolve("mojang/versions/${entry.id}")
        val clientDownload = versionMeta.downloads["client"]
            ?: throw GradleException("Minecraft ${entry.id} has no client download")
        val client = jarDir.resolve("client.jar")
        logger.lifecycle("mcsrc: downloading ${entry.id}")
        meta.ensure(clientDownload.url, client, clientDownload.sha1)

        // Releases before 26.1 are obfuscated and ship Mojang's mappings; later ones are not obfuscated.
        val jar = versionMeta.downloads["client_mappings"]?.let { mappingsDownload ->
            val mappings = jarDir.resolve("client.txt")
            meta.ensure(mappingsDownload.url, mappings, mappingsDownload.sha1)
            val named = jarDir.resolve("client-named.jar")
            if (!named.exists()) {
                logger.lifecycle("mcsrc: remapping ${entry.id}")
                MojangRemapper.remap(client, mappings, named)
            }
            named
        } ?: client

        logger.lifecycle("mcsrc: decompiling ${entry.id} (${names.dir} local names)")
        val partial = target.resolveSibling("${entry.id}.part")
        partial.deleteRecursively()
        partial.createDirectories()
        execOperations.javaexec {
            executable = javaLauncher.get().executablePath.asFile.absolutePath
            classpath(VINEFLOWER_JAR.toFile())
            mainClass.set(ConsoleDecompiler::class.java.name)
            maxHeapSize = decompilerMaxHeap.get()
            args(options + listOf(jar.toString(), partial.toString()))
        }
        // --skip-extra-files still leaves a few resources behind; only the sources are wanted.
        partial.walk().filter { it.isRegularFile() && !it.name.endsWith(".java") }.forEach(Files::delete)
        // Children come before their parents in reverse walk order, so a parent emptied by them goes too.
        for (dir in partial.walk(PathWalkOption.INCLUDE_DIRECTORIES).toList().asReversed()) {
            if (dir.isDirectory() && dir.listDirectoryEntries().isEmpty()) Files.delete(dir)
        }
        // Vineflower keeps going when a method runs out of heap and leaves a stub in its place; never cache that.
        val starved = partial.walk().filter { it.isRegularFile() && OUT_OF_HEAP in it.readText() }.toList()
        if (starved.isNotEmpty()) {
            throw GradleException(
                "Vineflower ran out of heap decompiling ${entry.id} (${starved.size} classes); " +
                    "rerun with a larger -Pmcsrc.decompilerHeap than ${decompilerMaxHeap.get()}"
            )
        }
        partial.resolve(STAMP).writeText(stamp)

        target.deleteRecursively()
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    /** The output directory is wiped and rewritten, so refuse to take over one this task did not create. */
    private fun claimOutput(output: Path) {
        val marker = output.resolve(MARKER)
        if (output.exists() && !marker.exists() && output.listDirectoryEntries().isNotEmpty()) {
            throw GradleException("$output exists and was not created by the mcsrc task; move it away or pick another output")
        }
        output.createDirectories()
        marker.writeText("Generated by ./gradlew mcsrc; everything in this directory is overwritten.\n")
    }

    private fun prune(dir: Path, keep: Set<String>) {
        if (!dir.isDirectory()) return
        for (child in dir.listDirectoryEntries()) {
            if (child.name !in keep) child.deleteRecursively()
        }
    }

    private fun stampOf(dir: Path): String? = dir.resolve(STAMP).takeIf { it.exists() }?.readText()

    private fun writeReadme(output: Path, max: VersionEntry, min: VersionEntry, older: List<VersionEntry>, extra: VersionEntry?) {
        val text = buildString {
            appendLine("# Minecraft client patches from ${max.id}")
            appendLine()
            appendLine("Generated by `./gradlew mcsrc` (Mojang names, decompiled with Vineflower). Do not edit or commit.")
            appendLine()
            appendLine("Each `${max.id}_to_<version>/` holds one unified diff per class, going from the ${max.id} client back to that version:")
            appendLine("`-` lines are ${max.id}, `+` lines are the older version. A patch sits at the path of the ${max.id} class it changes")
            appendLine("(`net/minecraft/world/entity/LivingEntity.java.patch`), and `CHANGES.txt` lists every class that differs.")
            appendLine()
            appendLine("Releases before 26.1 were obfuscated and carry no local variable names. Patches to them name locals and parameters")
            appendLine("from their type (`level`, `blockpos`, `i`) on both sides, so they show logic changes rather than renames; their")
            appendLine("context lines will not match the names in the ${max.id} NMS source. Patches to 26.1 and later keep Mojang's names.")
            appendLine()
            appendLine("Releases back to ${min.id}: ${older.joinToString(", ") { it.id }.ifEmpty { "none" }}.")
            if (extra != null) appendLine("Extra version: ${extra.id} (patch from ${max.id} only).")
            appendLine()
            appendLine("Some classes were renamed by letter case only (`Enderman` <-> `EnderMan`). Where their patch files would collide")
            appendLine("on a case-insensitive file system, the later one gets a numbered suffix (`Enderman.java.2.patch`, noted in")
            appendLine("`CHANGES.txt`).")
        }
        output.resolve(README).writeText(text)
    }

    /** How a decompiled tree names local variables and parameters; trees are only diffed against the same kind. */
    private enum class LocalNames(val dir: String, val options: List<String>) {
        /** Mojang's own names, which unobfuscated releases (26.1 and later) ship. */
        MOJANG("mojang", emptyList()),

        /**
         * Names generated from each variable's type, the same way in every version. Obfuscated releases only carry
         * `$$0`-style local names and no final parameter flags, so a patch against one would otherwise rename every
         * local in every changed class.
         */
        GENERATED(
            "generated",
            listOf("--use-lvt-names=0", "--use-method-parameters=0", "--variable-renaming=jad", "--rename-parameters=1"),
        ),
    }

    companion object {
        private const val MARKER = ".mcsrc"
        private const val STAMP = ".mcsrc-stamp"
        private const val README = "README.md"
        private const val OUT_OF_HEAP = "// java.lang.OutOfMemoryError: Java heap space"

        private val VINEFLOWER_JAR: Path =
            Paths.get(ConsoleDecompiler::class.java.protectionDomain.codeSource.location.toURI())

        private val VINEFLOWER_OPTIONS = listOf(
            "--folder",
            "--skip-extra-files",
            "--log-level=warn",
            "--indent-string=    ",
        )

        /** Changes whenever the patch layout would, so patch sets are rewritten from the cached trees. */
        private const val PATCH_FORMAT = "patches=3"
    }
}
