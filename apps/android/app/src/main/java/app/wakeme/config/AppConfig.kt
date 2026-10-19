package app.wakeme.config

import app.wakeme.BuildConfig

/** Non-secret client configuration injected at build time (see gradle.properties). */
object AppConfig {
    val apiBaseUrl: String = BuildConfig.API_BASE_URL.trimEnd('/')
    val supabaseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    val supabaseAnonKey: String = BuildConfig.SUPABASE_ANON_KEY
    val revenueCatPublicKey: String = BuildConfig.REVENUECAT_PUBLIC_KEY
    val googleServerClientId: String = BuildConfig.GOOGLE_SERVER_CLIENT_ID
    val inviteHost: String = BuildConfig.INVITE_HOST

    val firebaseConfigured: Boolean get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()
    val supabaseConfigured: Boolean get() = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()
    val billingConfigured: Boolean get() = revenueCatPublicKey.isNotBlank()

    /** Dev backend (services/api dev.ts) issues local test identities when Supabase isn't configured. */
    val useDevAuth: Boolean get() = BuildConfig.DEBUG && !supabaseConfigured
}
