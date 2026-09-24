plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Pure domain API: no Bukkit, no Paper, no third-party runtime dependencies.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
