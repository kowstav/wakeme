plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// android/skills adaptive, step 1: Compose Preview Screenshot Testing across phone, foldable, tablet and
// desktop. The plugin is experimental, so it is applied only when asked (-Pwakeme.screenshots=true,
// the CI "previews" job) and cannot break the app build, tests or lint.
val screenshots = providers.gradleProperty("wakeme.screenshots").orNull == "true"

fun prop(name: String): String = (findProperty(name) as String?).orEmpty()
fun quoted(name: String): String = "\"" + prop(name).replace("\"", "\\\"") + "\""

android {
    namespace = "app.wakeme"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.wakeme.android"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", quoted("wakeme.apiBaseUrl"))
        buildConfigField("String", "SUPABASE_URL", quoted("wakeme.supabaseUrl"))
        buildConfigField("String", "SUPABASE_ANON_KEY", quoted("wakeme.supabaseAnonKey"))
        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted("wakeme.firebaseProjectId"))
        buildConfigField("String", "FIREBASE_APP_ID", quoted("wakeme.firebaseAppId"))
        buildConfigField("String", "FIREBASE_API_KEY", quoted("wakeme.firebaseApiKey"))
        buildConfigField("String", "FIREBASE_SENDER_ID", quoted("wakeme.firebaseSenderId"))
        buildConfigField("String", "REVENUECAT_PUBLIC_KEY", quoted("wakeme.revenueCatPublicKey"))
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", quoted("wakeme.googleServerClientId"))
        buildConfigField("String", "INVITE_HOST", "\"wakeme.app\"")
    }

    signingConfigs {
        create("release") {
            // Supplied by CI from the protected release environment; never committed.
            val store = System.getenv("WAKEME_UPLOAD_KEYSTORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("WAKEME_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WAKEME_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("WAKEME_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    if (screenshots) experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

if (screenshots) apply(plugin = "com.android.compose.screenshot")

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.locationbutton)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.glance.appwidget.preview)
    implementation(libs.androidx.glance.preview)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.navsuite)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material3.adaptive.layout)
    implementation(libs.compose.material3.adaptive.navigation3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.code.scanner)
    implementation(libs.revenuecat)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)

    if (screenshots) {
        "screenshotTestImplementation"(platform(libs.compose.bom))
        "screenshotTestImplementation"(libs.compose.ui.tooling)
        "screenshotTestImplementation"(libs.screenshot.validation.api)
    }
}
