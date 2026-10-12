plugins { `java-library`; cult.`format-conventions` }
java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

repositories { mavenCentral() }
