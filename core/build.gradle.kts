import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Deliberately a plain JVM module, not an Android library.
//
// The beamforming maths is the part most likely to be wrong and the part
// hardest to check by hand on a phone, so it has to be testable without a
// device or an emulator. Keeping it outside the Android modules makes that
// structural rather than a matter of discipline: an `android.*` import here is
// a compile error, and `./gradlew :core:test` runs anywhere a JDK does.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        // Bytecode level 17 to match the Android modules, but no toolchain pin:
        // any JDK from 17 up can build and test this, which is the point of
        // having it outside the Android build in the first place.
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
