package com.jonasschnell.quickscope.data

import com.jonasschnell.quickscope.BuildConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

class Network(private val auth: AuthManager, private val store: Store) {
    /** Wird bei HTTP 426 (Upgrade Required) auf API-Pfaden ausser /api/app/ aufgerufen. */
    @Volatile var onUpgradeRequired: (() -> Unit)? = null

    private val apiUrl = BuildConfig.BASE_URL.toHttpUrl()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain -> retryingProceed(chain) })
        .addInterceptor(Interceptor { chain -> chain.proceed(decorate(chain.request())) })
        .addInterceptor(upgradeInterceptor())
        .authenticator(object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                if (!isApiHost(response.request)) return null
                if (response.priorResponse != null) {
                    // Auch mit frischem Token abgelehnt: Login anfordern, statt still haengen zu bleiben.
                    auth.notifyRejectedAfterRefresh()
                    return null
                }
                val rejected = response.request.header("Authorization")?.removePrefix("Bearer ")
                val fresh = runBlocking { auth.refreshAfterRejection(rejected) } ?: return null
                return response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
            }
        })
        .build()

    /** Fuer den SSE-Stream: gleiche Auth (Authenticator, Header), aber ohne GET-Retry-Interceptor und ohne Read-Timeout. */
    val streamClient: OkHttpClient = client.newBuilder()
        .apply {
            interceptors().clear()
            addInterceptor(Interceptor { chain -> chain.proceed(decorate(chain.request())) })
            addInterceptor(upgradeInterceptor())
        }
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val api: QuickscopeApi = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(client)
        .addConverterFactory(AppJson.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
        .build()
        .create(QuickscopeApi::class.java)

    /**
     * Wiederholt idempotente GETs zum eigenen Host bei 502/503/504 und bei IOException (Backend startet gerade neu):
     * bis zu 3 Wiederholungen nach 1 s, 2 s, 4 s. Nie fuer POST/PUT/DELETE (Uploads) oder fremde Hosts.
     */
    private fun retryingProceed(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET" || !isApiHost(request)) return chain.proceed(request)
        var attempt = 0
        while (true) {
            val result: Result<Response> = try {
                Result.success(chain.proceed(request))
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                Result.failure(e)
            }
            val response = result.getOrNull()
            val retryable = response?.code in RETRY_CODES || result.isFailure
            if (!retryable || attempt >= RETRY_DELAYS_MS.size) return response ?: throw result.exceptionOrNull()!!
            response?.close()
            if (chain.call().isCanceled()) throw IOException("Abgebrochen")
            try {
                // In kleinen Schritten schlafen, damit cancel() greift.
                val until = System.currentTimeMillis() + RETRY_DELAYS_MS[attempt]
                while (true) {
                    val left = until - System.currentTimeMillis()
                    if (left <= 0 || chain.call().isCanceled()) break
                    Thread.sleep(minOf(left, SLEEP_STEP_MS))
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Unterbrochen", e)
            }
            if (chain.call().isCanceled()) throw IOException("Abgebrochen")
            attempt++
        }
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000)
        val RETRY_CODES = setOf(502, 503, 504)
        const val SLEEP_STEP_MS = 250L
    }

    /** Das Token gehoert nur an den eigenen API-Host (nicht an Bild-CDNs wie lh3.googleusercontent.com). */
    private fun isApiHost(request: Request): Boolean {
        val url = request.url
        return url.host == apiUrl.host && url.port == apiUrl.port && url.scheme == apiUrl.scheme
    }

    private fun upgradeInterceptor() = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code == 426 && isApiHost(request) && !request.url.encodedPath.startsWith("/api/app/")) onUpgradeRequired?.invoke()
        response
    }

    private fun decorate(request: Request): Request {
        // Fremde Hosts (Bild-CDNs): nie Token oder App-Header, nur ein beschreibender User-Agent
        if (!isApiHost(request)) return request.newBuilder().header("User-Agent", "Quickscope/${BuildConfig.VERSION_NAME} (Android)").build()
        val builder = request.newBuilder().header("X-App-Version", BuildConfig.VERSION_CODE.toString())
        auth.token?.let { builder.header("Authorization", "Bearer $it") }
        return builder.build()
    }
}

/** Liest das Feld `message` aus dem Spring-Fehlerbody (`{timestamp,status,error,message,path}`); null, wenn nicht vorhanden. */
fun backendMessage(e: retrofit2.HttpException): String? = try {
    val body = e.response()?.errorBody()?.string().orEmpty()
    (AppJson.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject)
        ?.get("message")?.let { it as? kotlinx.serialization.json.JsonPrimitive }
        ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
} catch (_: Exception) {
    null
}
