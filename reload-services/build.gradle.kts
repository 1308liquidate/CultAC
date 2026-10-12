plugins { cult.`base-conventions` }
repositories {
    mavenLocal()
    maven("https://maven.grim.ac/public/releases")
    maven("https://maven.grim.ac/public/snapshots")
    maven("https://repo.grim.ac/snapshots")
    mavenCentral()
}
java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
configurations.configureEach { attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25) }
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.22.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    api(libs.jetbrains.annotations)
    api(libs.cult.api)
    api(libs.cult.internal)
    api(libs.hikaricp)
    compileOnly(libs.mongoDriverSync)
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
}
for (variant in listOf("apiElements", "runtimeElements")) {
    configurations.named(variant) { attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
}

tasks.test { useJUnitPlatform() }
