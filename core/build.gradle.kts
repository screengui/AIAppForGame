plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    // Applied WITHOUT a version on purpose: the Android Gradle Plugin is already on the build
    // classpath because :app applies it, and Gradle refuses a second, versioned request for a
    // plugin that is already loaded. Without this, `:app:lint` treats :core as an opaque prebuilt
    // jar and skips its sources entirely.
    id("com.android.lint")
}

kotlin {
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Exposed on the API surface because agent/perception/input contracts are `suspend`.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // Deterministic, readable CI output.
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
