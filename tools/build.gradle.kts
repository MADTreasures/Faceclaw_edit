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
}

application {
    mainClass.set("com.madtreasures.faceclaw.tools.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

/** Re-bakes the bitmap fonts in core/src/main/resources/fonts and regenerates Icons.kt. */
tasks.register<JavaExec>("bakeFonts") {
    group = "faceclaw"
    description = "Bake TTF fonts in tools/fonts into core resources"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.madtreasures.faceclaw.tools.MainKt")
    jvmArgs("-Djava.awt.headless=true")
    args(
        "bake-fonts",
        rootProject.file("tools/fonts").absolutePath,
        rootProject.file("core/src/main/resources/fonts").absolutePath,
        rootProject.file("core/src/main/kotlin/com/madtreasures/faceclaw/core/gfx/Icons.kt").absolutePath,
    )
}
