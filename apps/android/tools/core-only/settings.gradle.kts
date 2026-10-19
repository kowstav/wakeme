// Builds and tests only the pure-Kotlin :core module (shared-policy fixture parity),
// without the Android Gradle Plugin or Google Maven. Useful where only Maven Central
// and the Gradle Plugin Portal are reachable:
//   ../../gradlew -p tools/core-only :core:test      (from apps/android)
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
rootProject.name = "wakeme-core-only"
include(":core")
project(":core").projectDir = file("../../core")
