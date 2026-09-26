import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.swing)
}

application {
    mainClass.set("com.madtreasures.faceclaw.sim.MainKt")
}

/** Renders every screen to docs/screenshots (headless). */
tasks.register<JavaExec>("screenshots") {
    group = "faceclaw"
    description = "Render all glasses screens to PNG files"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.madtreasures.faceclaw.sim.MainKt")
    jvmArgs("-Djava.awt.headless=true")
    args("--screenshots", rootProject.file("docs/screenshots").absolutePath)
}
