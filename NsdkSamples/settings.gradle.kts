pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}


dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NSDK Maven repository (hosted on GitHub)
        maven {
            url = uri("https://raw.githubusercontent.com/nianticspatial/nsdk-library-aar/main")
        }
    }
}

rootProject.name = "NSDK Samples"
include(":NsdkSamples")
