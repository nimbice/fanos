package io.github.nimbice.fanos.core.network.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.nimbice.fanos.core.network.BrowserFetcher
import io.github.nimbice.fanos.core.network.HostLimiter
import io.github.nimbice.fanos.core.network.OkHttpSourceHttp
import io.github.nimbice.fanos.core.network.UserAgentInterceptor
import io.github.nimbice.fanos.core.network.WebViewCookieJar
import io.github.nimbice.fanos.source.api.SourceHttp
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun cookieJar(): WebViewCookieJar = WebViewCookieJar()

    @Provides
    @Singleton
    fun okHttpClient(@ApplicationContext context: Context, cookieJar: WebViewCookieJar): OkHttpClient =
        OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "http"), 50L * 1024 * 1024))
            .cookieJar(cookieJar)
            .addInterceptor(UserAgentInterceptor(context))
            .addNetworkInterceptor(HostLimiter())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun sourceHttp(client: OkHttpClient, browser: BrowserFetcher): SourceHttp = OkHttpSourceHttp(client, browser)
}
