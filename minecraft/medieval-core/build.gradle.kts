plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    api(project(":medieval-api"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Persistence tests run against real temporary SQLite databases. Test scope only: the server
    // supplies the driver at runtime through the plugin's 'libraries:' entry, so it is never
    // bundled into the plugin jar.
    testImplementation(libs.sqlite.jdbc)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
