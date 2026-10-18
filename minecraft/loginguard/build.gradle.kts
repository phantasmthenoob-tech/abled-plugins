plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

// loginguard is built two ways: as part of the medieval build, and on its own from this folder
// (its own settings file makes this the root project). Resolve the sibling worker module for
// whichever layout is in effect, then consume its built jar by path.
val workerProject = rootProject.findProject(":auth-bridge-worker")
val workerDir = workerProject?.projectDir ?: rootProject.file("../auth-bridge-worker")
val workerJar = workerDir.resolve("build/libs/AuthBridgeWorker.jar")

tasks.processResources {
    // A path-based input is not a task dependency, so build the worker first when the medieval
    // build is driving this; a standalone build has no such project to depend on.
    if (workerProject != null) {
        dependsOn(workerProject.tasks.named("jar"))
    }
    val tokens = mapOf("version" to project.version.toString())
    inputs.properties(tokens)
    filesMatching("plugin.yml") {
        expand(tokens)
    }
    from(workerJar) {
        rename { "AuthBridgeWorker.jar" }
    }
    doFirst {
        require(workerJar.isFile) {
            "missing $workerJar - build it first: cd .. && ./gradlew :auth-bridge-worker:jar"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("LoginGuard")
}
