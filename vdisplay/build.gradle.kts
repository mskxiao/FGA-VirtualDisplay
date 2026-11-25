/*
 * :vdisplay - the platform seam between FGA and a virtual display.
 *
 * A plain JVM library on purpose:
 *  - the interface layer (DisplayConfig / DisplayIds / VirtualDisplayBackend)
 *    has no android.* imports, so it can carry fast unit tests in CI with no
 *    emulator and no Robolectric;
 *  - the shell backend only needs android.util.Log for logging, which is stubbed
 *    out behind a small logger interface rather than referenced directly.
 *
 * The module is consumed by :app. It must not depend on :libautomata or :scripts
 * in the other direction - the dependency arrow points app -> vdisplay, and
 * app -> libautomata, so that FGA's core stays ignorant of virtual displays.
 */
plugins {
    id("java-library")
    id("kotlin")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // No runtime dependencies. The backend drives the platform through
    // /system/bin/{screencap,input,am,settings} and dumpsys, so it needs nothing
    // beyond the JDK. Keeping this list empty is a feature: it means the module
    // cannot accidentally pull androidx into the seam.
    testImplementation(libs.kotlin.test.junit5)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
