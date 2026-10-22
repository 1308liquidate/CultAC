/**
 *          CultAC Build Configuration
 *
 * Build Flags:
 * -Prelocate=false - Adds 'no_relocate' modifier
 * -Prelease=true   - Removes commit/modifiers for release build
 * CULT_BUILD_NUMBER - Numbered build: <base>-b<N>-<channel>-<commit>
 * CULT_BUILD_CHANNEL - The channel of a numbered build; the git branch when unset
 *
 * Logic in: buildSrc/versioning/BuildConfig.kt & VersionUtil.kt
 */

import versioning.BuildConfig
import versioning.VersionUtil

plugins {
    // The Paper host boundary alone compiles against NMS.
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23" apply false
}

BuildConfig.init(project)

val baseVersion = "0.1.0"
group = "ac.cult.cultac"
version = VersionUtil.computeVersion(project, baseVersion)
description = "Libre simulation anticheat with Java 26.3 client action simulation."

ext["timestamp"] = System.currentTimeMillis().toString()
ext["git_branch"] = VersionUtil.getGitBranch(project, true)
ext["build_channel"] = VersionUtil.buildChannel(project)
ext["git_commit"] = VersionUtil.getGitCommitHash(project, true)
ext["git_org"] = System.getenv("CULT_GIT_ORG") ?: VersionUtil.getGitUser(project)
ext["git_repo"] = System.getenv("CULT_GIT_REPO") ?: "CultAC"

println("Build configuration:")
println("    relocate           = ${BuildConfig.relocate}")
println("    mavenLocalOverride = ${BuildConfig.mavenLocalOverride}")
println("    release            = ${BuildConfig.release}")
println("    version            = $version")

tasks.register("printVersion") {
    group = "versioning"
    description = "Prints the computed project version"
    doLast {
        println("VERSION=$version")
    }
}

// ---------- Decompiled Minecraft sources ----------
// ./gradlew mcsrc [-Pmcsrc.version=<snapshot>] [-Pmcsrc.min=<release>] [-Pmcsrc.max=<release>]
// Per-class patches of the decompiled client from the max release back to every release down to the min, in mc-sources/.
// A version outside that release range (a newer snapshot) only gets a patch from the max release.
tasks.register<mcsrc.GenerateMinecraftPatches>("mcsrc") {
    group = "minecraft"
    description = "Writes patches of the decompiled Minecraft client from the pinned release back to older releases into mc-sources/"
    minVersion.set(providers.gradleProperty("mcsrc.min").orElse(providers.gradleProperty("runtimeHostMinVersion")))
    // The pinned max is the Minecraft version of the Paper dev bundle (26.3.build.N-beta -> 26.3).
    maxVersion.set(
        providers.gradleProperty("mcsrc.max")
            .orElse(providers.gradleProperty("paperDevBundleVersion").map { it.substringBefore(".build") })
    )
    selectedVersion.set(providers.gradleProperty("mcsrc.version"))
    decompilerMaxHeap.set(providers.gradleProperty("mcsrc.decompilerHeap").orElse("8g"))
    cacheDir.set(gradle.gradleUserHomeDir.resolve("caches/cultac-mcsrc"))
    outputDir.set(layout.projectDirectory.dir("mc-sources"))
}

// ---------- Java Compile Optimization ----------
subprojects {
    repositories.maven("https://repo.papermc.io/repository/maven-public/") {
        content { includeGroup("io.papermc.paper") }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.isFork = true
        options.isIncremental = true
    }
}
