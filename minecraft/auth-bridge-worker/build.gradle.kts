import org.gradle.jvm.tasks.Jar

plugins {
    `java-library`
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // Headless Minecraft client: packet-level, offline-mode capable, text inputs and
    // dialog (screen) responses supported. Runs as a supervised child process.
    implementation("org.geysermc.mcprotocollib:protocol:26.3-SNAPSHOT") {
        // The 26.3 protocol POM pins text-serializer-nbt 0.2.1, which was never published;
        // force the actual newest 0.2.0 instead.
        exclude(group = "org.geysermc.mcprotocollib", module = "text-serializer-nbt")
    }
    implementation("org.geysermc.mcprotocollib:text-serializer-nbt:0.2.0")
    // Render system chat / kick reasons to plain text for classification.
    implementation("net.kyori:adventure-text-serializer-plain:5.2.0")
    // Netty pulls in slf4j-api; without a provider it prints two "No SLF4J providers"
    // warnings into the server console on every worker start. The worker logs via stdout.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("net.abled.bridge.WorkerMain")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
}

// Self-contained worker jar: MCProtocolLib and its network stack bundled so the plugin can
// spawn it with a plain `java -jar` and no classpath assembly.
tasks.named<Jar>("jar") {
    archiveBaseName.set("AuthBridgeWorker")
    // No version suffix: loginguard's processResources consumes this exact file, and a
    // versioned name left the previous build's AuthBridgeWorker.jar to be bundled silently.
    archiveVersion.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "net.abled.bridge.WorkerMain"
    }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    }
}
