// Pure Kotlin/JVM domain module: policy, occurrences, delivery state and
// challenge verification. No Android dependencies, so it is fast to test and
// is checked against the same golden fixtures as the server and iOS.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    systemProperty("wakeme.fixtures", rootProject.projectDir.resolve("../../packages/policy-fixtures/fixtures").canonicalPath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
