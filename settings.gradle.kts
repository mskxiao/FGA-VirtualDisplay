pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*|)")
                includeGroupByRegex("com\\.google\\.android\\..*")
                includeGroupByRegex("com\\.google\\.testing\\.platform")
            }
            mavenContent {
                releasesOnly()
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*|)")
                includeGroupByRegex("com\\.google\\.android\\..*")
                includeGroupByRegex("com\\.google\\.testing\\.platform")
            }
            mavenContent {
                releasesOnly()
            }
        }
        // fallback for the rest of the dependencies
        mavenCentral()
        // tesseract4android is published nowhere else
        maven("https://jitpack.io") {
            content {
                includeGroup("cz.adaptech.tesseract4android")
            }
        }
    }
}

include(":prefs")
include(":scripts")
include(":libautomata")

// [FGA-VirtualDisplay] stage 1: the virtual display backend seam.
// Added by this fork; upstream FGA has no equivalent module. It is deliberately
// separate from :libautomata and :scripts so FGA's automation core stays
// platform- and display-agnostic, and so `git diff` against upstream stays narrow.
include(":vdisplay")

include(":app")

rootProject.name="Fate Grand Automata"
