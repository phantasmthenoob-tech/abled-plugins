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

    // The tests here check the module's own resources against its own sources, so they need no
    // server and no Bukkit: paper-api stays compileOnly and is never on the test classpath.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
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

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("Medieval")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Declared explicitly: the bundle below unzips and filters this configuration into a file
    // tree, and a filtered tree no longer carries the tasks that produced its inputs, so Gradle 9
    // would otherwise reject the merge as an implicit dependency between this task and the
    // :medieval-api / :medieval-core jar tasks.
    dependsOn(configurations.runtimeClasspath)

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
