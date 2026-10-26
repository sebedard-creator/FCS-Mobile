package com.fcsmobile.app

import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

private data class Fingerprint(val digest: String, val size: Long)

private class HashCache(context: Context) : SQLiteOpenHelper(context, "fcs-hashes.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE hashes(uri TEXT PRIMARY KEY, signature TEXT NOT NULL, digest TEXT NOT NULL, actual_size INTEGER NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Only derived fingerprints live here; originals are never stored in this database.
        db.execSQL("DROP TABLE IF EXISTS hashes")
        onCreate(db)
    }
    fun cached(media: DeviceMedia): Fingerprint? = readableDatabase.rawQuery("SELECT digest,actual_size FROM hashes WHERE uri=? AND signature=?", arrayOf(media.uri.toString(), media.signature)).use {
        if (it.moveToFirst()) Fingerprint(it.getString(0), it.getLong(1)) else null
    }
    fun save(media: DeviceMedia, fingerprint: Fingerprint) { writableDatabase.execSQL("INSERT OR REPLACE INTO hashes VALUES(?,?,?,?)", arrayOf(media.uri.toString(), media.signature, fingerprint.digest, fingerprint.size)) }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var api: LocalApi? = null
    private val notifications = BackupNotifications(context, id.hashCode() and Int.MAX_VALUE)
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext Result.retry()
        try {
            val settings = AppSettings(applicationContext)
            withTimeoutOrNull(MAX_RUN_MS) { backup(settings) } ?: run {
                settings.status = "Lot de sauvegarde terminé — reprise à la prochaine tentative"
                Result.retry()
            }
        } finally {
            try { api?.close() } finally {
                api = null
                try { notifications.dismiss() } finally { mutex.unlock() }
            }
        }
    }

    private suspend fun backup(settings: AppSettings): Result {
        if (!settings.configured) return Result.failure()
        if (!MediaScanner.fullAccess(applicationContext)) {
            settings.status = "Accès complet aux photos et vidéos requis — ouvrir Réglages"
            return Result.failure()
        }
        if (!LocalApi.charging(applicationContext) || LocalApi.wifi(applicationContext) == null) {
            settings.status = "En attente de charge et du réseau local"
            return Result.retry()
        }
        var saved = 0
        var archived = 0
        var ignored = 0
        var errors = 0
        var total = 0
        var lastProgress = 0L
        suspend fun report(phase: String, force: Boolean = false) {
            val now = System.nanoTime()
            if (!force && now - lastProgress < 1_000_000_000) return
            lastProgress = now
            val progress = BackupProgress(phase, total, saved + archived + ignored + errors, saved, errors)
            setProgress(progress.data())
            notifications.update(progress)
        }
        val started = System.nanoTime()
        return try {
            settings.status = "Connexion au serveur local…"
            report("Connexion au serveur local", true)
            val connection = LocalApi(applicationContext)
            api = connection
            connection.checkConnection()
            settings.status = "Inventaire des photos et vidéos…"
            report("Inventaire des photos et vidéos", true)
            val files = MediaScanner.scan(applicationContext)
            total = files.size
            report("Vérification des médias", true)
            HashCache(applicationContext).use { cache ->
                // Batch inventory checks so a large, already archived library does not
                // spend an entire Android execution window on one HTTP call per photo.
                for (batch in files.chunked(100)) {
                    currentCoroutineContext().ensureActive()
                    if (isStopped) throw CancellationException()
                    if (!LocalApi.charging(applicationContext) || LocalApi.wifi(applicationContext) == null ||
                        System.nanoTime() - started > MAX_RUN_MS * 1_000_000) {
                        settings.status = "$saved transférés · reprise en attente de charge/réseau"
                        return Result.retry()
                    }
                    val prepared = mutableListOf<Pair<DeviceMedia, String>>()
                    for (media in batch) {
                        currentCoroutineContext().ensureActive()
                        if (settings.excluded.any { media.folder.startsWith(it.trim('/') + "/") }) { ignored++; continue }
                        settings.status = "Vérification : ${media.name} · $saved transférés · $errors erreurs"
                        report("Vérification des médias")
                        try {
                            val fingerprint = fingerprint(media, cache)
                            prepared.add(media.copy(size = fingerprint.size) to fingerprint.digest)
                        } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
                            errors++; settings.status = "Erreur ${media.name} : ${error.message}"
                        }
                        if (System.nanoTime() - started > MAX_RUN_MS * 1_000_000) break
                    }
                    if (prepared.isEmpty()) continue
                    val hashes = JSONArray()
                    prepared.forEach { hashes.put(it.second) }
                    val states = connection.json("/api/check", JSONObject().put("sha256", hashes)).getJSONObject("files")
                    for ((media, hash) in prepared) {
                        currentCoroutineContext().ensureActive()
                        val state = states.getJSONObject(hash).getString("state")
                        if (state == "saved") { archived++; report("Vérification des médias"); continue }
                        if (state == "trash" || state == "deleted") { ignored++; report("Vérification des médias"); continue }
                        if (state != "missing") throw IOException("État de sauvegarde inconnu")
                        if (!LocalApi.charging(applicationContext) || LocalApi.wifi(applicationContext) == null ||
                            System.nanoTime() - started > MAX_RUN_MS * 1_000_000) {
                            settings.status = "$saved transférés · suite de l'import à la prochaine tentative"
                            return Result.retry()
                        }
                        if (settings.excluded.any { media.folder.startsWith(it.trim('/') + "/") }) { ignored++; continue }
                        try {
                            settings.status = "Transfert : ${media.name} · $saved terminés · $errors erreurs"
                            report("Transfert vers le PC")
                            val response = upload(connection, media, hash, started)
                            if (response.optString("state") in listOf("trash", "deleted")) ignored++
                            else {
                                if (!response.optBoolean("verified") || response.optString("sha256") != hash || response.optLong("size") != media.size)
                                    throw IOException("La sauvegarde n'a pas été vérifiée")
                                saved++
                            }
                        } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
                            errors++; settings.status = "Erreur ${media.name} : ${error.message}"
                        }
                        report("Transfert vers le PC")
                    }
                }
            }
            report("Vérification terminée", true)
            if (System.nanoTime() - started > MAX_RUN_MS * 1_000_000) {
                settings.status = "$saved transférés · suite de l'import à la prochaine tentative"
                return Result.retry()
            }
            if (errors > 0) {
                settings.status = "Sauvegarde incomplète : $saved transférés · $errors erreurs. Nouvelle tentative prévue."
                Result.retry()
            } else {
                settings.recordSuccess(inputData.getBoolean(AUTOMATIC, false))
                settings.status = "Sauvegarde vérifiée : $saved transférés · $archived déjà archivés · $ignored supprimés volontairement"
                Result.success()
            }
        } catch (cancel: CancellationException) {
            settings.status = "Sauvegarde suspendue — reprise à la prochaine occasion"
            throw cancel
        } catch (error: IOException) {
            settings.status = "Connexion locale indisponible : ${error.message}"
            Result.retry()
        } catch (error: Exception) {
            settings.status = "Erreur de sauvegarde : ${error.message}"
            Result.retry()
        }
    }

    private suspend fun fingerprint(media: DeviceMedia, cache: HashCache): Fingerprint {
        cache.cached(media)?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        var actualSize = 0L
        applicationContext.contentResolver.openInputStream(media.uri)?.use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!LocalApi.charging(applicationContext)) throw IOException("Téléphone débranché")
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                actualSize += n
            }
        } ?: throw IOException("Média inaccessible")
        return Fingerprint(digest.digest().joinToString("") { "%02x".format(it) }, actualSize)
            .also { cache.save(media, it) }
    }

    private suspend fun upload(connection: LocalApi, media: DeviceMedia, hash: String, started: Long): JSONObject {
        val body = object : RequestBody() {
            override fun contentType() = media.mime.toMediaType()
            override fun contentLength() = media.size
            override fun writeTo(sink: BufferedSink) {
                applicationContext.contentResolver.openInputStream(media.uri)?.use { stream ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (isStopped || !LocalApi.charging(applicationContext) ||
                            System.nanoTime() - started > MAX_RUN_MS * 1_000_000) throw IOException("Sauvegarde suspendue")
                        val n = stream.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                    }
                } ?: throw IOException("Média inaccessible")
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", media.metadata(hash).toString())
            .addFormDataPart("file", media.name, body).build()
        return connection.execute(connection.request("/api/upload").post(multipart).build())
    }
    companion object {
        const val AUTOMATIC = "automatic"
        private const val MAX_RUN_MS = 7L * 60 * 1000
        private val mutex = Mutex()
    }
}
