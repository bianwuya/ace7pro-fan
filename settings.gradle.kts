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
        // Official Maven Central endpoint used by the prewarmed Linux ARM64 dependency cache.
        maven("https://repo1.maven.org/maven2/")
    }
}

check(org.gradle.util.GradleVersion.current() == org.gradle.util.GradleVersion.version("9.7.1")) {
    "ACE Fan requires Gradle 9.7.1 (set ACEFAN_GRADLE or use gradle on PATH)."
}
check(JavaVersion.current() == JavaVersion.VERSION_25) {
    "ACE Fan requires JDK 25 (set JAVA_HOME)."
}

rootProject.name = "ace7pro-fan"
