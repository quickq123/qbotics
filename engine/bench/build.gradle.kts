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

// The engine classes and the editor's text logic live in the mod's common module; neither
// has any Minecraft dependencies, so both are compiled and checked here too.
sourceSets.main {
    java.srcDir("../../common/src/main/java")
    java.include("engine/**", "qbotics/engine/**", "qbotics/ide/**")
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

// The web panels' JavaScript engine (qbotics/web/JsRuntime) and its wasm, for the jsbench task.
sourceSets.main {
    java.include("qbotics/web/JsRuntime.java")
    resources.srcDir("../../common/src/main/resources")
    resources.include("qbotics/web/js.wasm")
}

dependencies {
    implementation("org.jspecify:jspecify:1.0.0")
}

// JavaScript speed: ./gradlew -q jsbench (design doc §10.3).
tasks.register<JavaExec>("jsbench") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "engine.JsBench"
    jvmArgs = (project.findProperty("jvm") as String?)?.split(" ") ?: listOf()
    workingDir = projectDir
}

// QuickJS's own tests on the web panels' engine: ./gradlew -q jstest -Ptests=<quickjs>/tests
tasks.register<JavaExec>("jstest") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "engine.JsTest"
    workingDir = projectDir
    args = listOf((project.findProperty("tests") as String?) ?: "../js/out/tests")
}
