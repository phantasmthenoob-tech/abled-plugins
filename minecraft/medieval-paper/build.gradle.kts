plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // implementation: the core/api classes are bundled into the plugin jar below.
    implementation(project(":medieval-core"))
    compileOnly(libs.paper.api)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    val tokens = mapOf("version" to project.version.toString())
    inputs.properties(tokens)
    filesMatching("plugin.yml") {
        expand(tokens)
    }
}

tasks.jar {
    archiveBaseName.set("Medieval")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Self-contained plugin jar: our own modules (medieval-api, medieval-core) are merged in.
    // paper-api is compileOnly, so it is never on the runtime classpath and never bundled.
    // Using the runtime classpath as a provider keeps evaluation lazy and makes Gradle add the
    // producer task dependencies automatically, so no shading plugin is required.
    val bundled = configurations.runtimeClasspath.map { classpath ->
        classpath
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it).matching { exclude("META-INF/**") } }
    }
    from(bundled)
}
