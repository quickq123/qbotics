plugins {
    java
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// The engine classes live in the mod's common module; they have no Minecraft dependencies.
sourceSets.main {
    java.srcDir("../../common/src/main/java")
    java.include("engine/**", "qbotics/engine/**")
}

val chicoryVersion = "1.7.5"

dependencies {
    implementation("com.dylibso.chicory:runtime:$chicoryVersion")
    implementation("com.dylibso.chicory:compiler:$chicoryVersion")
}

application {
    mainClass = "engine.Main"
    applicationDefaultJvmArgs = listOf(
        // Design doc §4: computers share a capped pool of carrier threads.
        "-Djdk.virtualThreadScheduler.parallelism=2",
        "-Djdk.virtualThreadScheduler.maxPoolSize=2",
        // Report any place a blocked computer would hog a carrier thread.
        "-Djdk.tracePinnedThreads=short",
    )
}

tasks.named<JavaExec>("run") {
    workingDir = projectDir
    args = listOf("../out/micropython.wasm")
}
