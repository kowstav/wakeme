plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

fun prop(name: String): String = (findProperty(name) as String?).orEmpty()
fun quoted(name: String): String = "\"" + prop(name).replace("\"", "\\\"") + "\""

android {
    namespace = "app.wakeme"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.wakeme.android"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
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
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
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
}
