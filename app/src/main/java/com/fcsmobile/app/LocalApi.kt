package com.fcsmobile.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume

class LocalApi(context: Context) : Closeable {
    private val app = context.applicationContext
    private val settings = AppSettings(app)
    private val network: Network = wifi(app) ?: throw IOException("Connexion Wi-Fi locale requise")
    val baseUrl: String
    val client: OkHttpClient
    private val imageClient: OkHttpClient
    init {
        require(isLocalHost(settings.host)) { "Saisir une adresse IPv4 privée du PC" }
        require(settings.token.isNotBlank()) { "Clé de connexion requise" }
        baseUrl = "http://${settings.host}:5001"
        client = OkHttpClient.Builder().socketFactory(network.socketFactory).proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.MINUTES).callTimeout(5, TimeUnit.MINUTES)
            .addInterceptor { chain ->
                if (wifi(app) != network) throw IOException("Connexion locale interrompue")
                val url = chain.request().url
                if (url.host != settings.host || url.port != 5001 || url.scheme != "http") throw IOException("Adresse hors du serveur local")
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${settings.token}")
                    .header("Cache-Control", "no-store").build())
            }.build()
        // Images cannot occupy the metadata/status request queue.
        imageClient = client.newBuilder().dispatcher(Dispatcher().apply {
            maxRequests = 4
            maxRequestsPerHost = 4
        }).build()
    }
    fun request(path: String) = Request.Builder().url(baseUrl + path)
    suspend fun json(path: String, body: JSONObject? = null): JSONObject {
        val builder = request(path)
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        return execute(builder.build())
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun response(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.close() }
            }
        })
    }
    suspend fun execute(request: Request): JSONObject = response(request).use { response ->
        val text = response.body?.string() ?: throw IOException("Réponse vide du serveur")
        val data = try { JSONObject(text) } catch (error: Exception) { throw IOException("Serveur FCS Mobile incompatible") }
        if (!response.isSuccessful) throw IOException(data.optString("error", "Erreur ${response.code}"))
        data
    }
    suspend fun bitmap(path: String): Bitmap = suspendCancellableCoroutine { continuation ->
        val transport = if (path.endsWith("/thumbnail")) imageClient else client
        val call = transport.newCall(request(path).build())
        // Keep cancellation attached until the body is decoded, not only until
        // headers arrive, so rapidly swiping does not leave old transfers running.
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bitmap = response.use {
                        if (!it.isSuccessful) throw IOException("Aperçu indisponible")
                        it.body?.byteStream()?.use { stream -> BitmapFactory.decodeStream(stream) }
                            ?: throw IOException("Format d'aperçu indisponible")
                    }
                    if (continuation.isActive) continuation.resume(bitmap)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
    suspend fun checkConnection(): JSONObject {
        val status = json("/api/status")
        if (status.optString("service") != "FCS Mobile" || status.optInt("protocol") != 2) throw IOException("Serveur incompatible")
        return status
    }
    override fun close() { imageClient.dispatcher.cancelAll(); client.dispatcher.cancelAll(); client.connectionPool.evictAll() }

    companion object {
        fun isLocalHost(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return false
            val nums = parts.map { it.toIntOrNull() ?: return false }
            if (nums.any { it !in 0..255 }) return false
            return nums[0] == 10 || (nums[0] == 192 && nums[1] == 168) || (nums[0] == 172 && nums[1] in 16..31)
        }
        fun wifi(context: Context): Network? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            return cm.allNetworks.firstOrNull { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }
        fun charging(context: Context): Boolean {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            // A charge limit can pause charging while AC/USB/wireless power
            // remains connected. Such a phone is still plugged in for backups.
            return if (battery?.hasExtra(BatteryManager.EXTRA_PLUGGED) == true) {
                battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            } else context.getSystemService(BatteryManager::class.java).isCharging
        }
    }
}
