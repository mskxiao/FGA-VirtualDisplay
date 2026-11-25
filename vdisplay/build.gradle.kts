import org.gradle.api.attributes.java.TargetJvmVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * :vdisplay - the platform seam between FGA and a virtual display.
 *
 * A plain JVM library on purpose:
 *  - the interface layer (DisplayConfig / DisplayIds / VirtualDisplayBackend) has no
 *    android.* imports, so it can carry fast unit tests in CI with no emulator and no
 *    Robolectric;
 *  - the shell backend reaches the platform only through /system/bin tools via the
 *    ShellRunner abstraction, so the module needs no androidx and no NDK.
 *
 * Dependency direction: :app -> :vdisplay. Never the reverse, and :vdisplay must not
 * depend on :libautomata or :scripts, so FGA's automation core stays ignorant of
 * virtual displays.
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
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // No runtime dependencies. The backend drives the platform through
    // /system/bin/{screencap,input,am,settings} and dumpsys, so it needs nothing beyond
    // the JDK. An empty runtime classpath is a feature here: the seam cannot
    // accidentally pull androidx into it.
    testImplementation(platform(libs.junit.bom)) {
        because("kotlin-test comes with conflicting junit versions")
    }
    testImplementation(libs.kotlin.test.junit5)
}

tasks.test {
    useJUnitPlatform()
}

/*
 * The same split upstream :scripts uses, for the same reason (see its build file):
 *
 * The library stays at Java 11 bytecode because :app consumes it and upstream pins
 * app-facing bytecode to 11. But JUnit 6 ships Java 17 bytecode, and Gradle's variant
 * resolution refuses to put a 17-targeted artifact on an 11-targeted test compile
 * classpath. So the *test* compilation is raised to 17 while the main compilation
 * stays at 11. Tests run on the Gradle toolchain JVM (21) and never reach Android.
 */
tasks.compileTestKotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Kotlin refuses to compile when the paired Java task disagrees, even with no Java
// test sources present.
tasks.compileTestJava {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
    options.release.set(17)
}

listOf(configurations.testCompileClasspath, configurations.testRuntimeClasspath).forEach { config ->
    config.configure {
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
        }
    }
}
