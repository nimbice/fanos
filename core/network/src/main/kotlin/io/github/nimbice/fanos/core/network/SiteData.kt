package io.github.nimbice.fanos.core.network

import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * What the sites leave on this device: the cookies and storage the built-in browser holds (sign-ins, security
 * checks passed), shared with the HTTP client, and the cache of pages and pictures. Settings shows how much, and
 * clears it on request.
 */
@Singleton
class SiteData @Inject constructor(private val client: OkHttpClient) {
    /** What the cache of pages and pictures holds, in bytes. */
    suspend fun cacheSize(): Long = withContext(Dispatchers.IO) { client.cache?.size() ?: 0L }

    /** Drops the cached pages and pictures; sign-ins stay. */
    suspend fun clearCache() {
        withContext(Dispatchers.IO) { client.cache?.evictAll() }
    }

    /** Signs out of every site: every cookie and all the browser's storage go, and the cache with them. */
    suspend fun signOutOfSites() {
        withContext(Dispatchers.Main) {
            val cookies = CookieManager.getInstance()
            suspendCancellableCoroutine { done -> cookies.removeAllCookies { done.resume(Unit) } }
            cookies.flush()
            WebStorage.getInstance().deleteAllData()
        }
        clearCache()
    }
}
