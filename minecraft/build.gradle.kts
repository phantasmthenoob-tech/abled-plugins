import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    base
}

allprojects {
    group = "net.abled.medieval"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    // Reproducible archives: no timestamps, stable entry order.
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
