plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
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

// loginguard is a standalone Gradle build, sibling to auth-bridge-worker in the minecraft/
// workspace, so cross-project tasks are unavailable; consume the worker's built jar by path.
val workerJar = rootProject.file("../auth-bridge-worker/build/libs/AuthBridgeWorker.jar")

tasks.processResources {
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
