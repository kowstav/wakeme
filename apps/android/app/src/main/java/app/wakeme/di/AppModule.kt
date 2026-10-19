package app.wakeme.di

import android.content.Context
import androidx.room.Room
import app.wakeme.data.local.LocalDb
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.time.Duration
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(20))
        .writeTimeout(Duration.ofSeconds(20))
        .callTimeout(Duration.ofSeconds(30))
        .retryOnConnectionFailure(false) // retries are explicit and idempotency-aware in ApiClient
        .build()

    @Provides
    @Singleton
    fun db(@ApplicationContext context: Context): LocalDb =
        Room.databaseBuilder(context, LocalDb::class.java, "wakeme.db")
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()
}
