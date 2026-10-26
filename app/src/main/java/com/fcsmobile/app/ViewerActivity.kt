package com.fcsmobile.app

import android.graphics.Typeface
import android.text.TextUtils
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ViewerActivity : AppCompatActivity() {
    private val style by lazy { AppStyle(this) }
    private var api: LocalApi? = null
    private var player: ExoPlayer? = null
    private var videoView: PlayerView? = null
    private var imageView: ImageView? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaJob: Job? = null
    private var neighborJob: Job? = null
    private var current: JSONObject? = null
    private var previous: JSONObject? = null
    private var next: JSONObject? = null
    private var neighborsReady = false
    private var pendingDirection: Int? = null
    private var connected = false
    private var changing = false
    private var registered = false
    private lateinit var title: TextView
    private lateinit var date: TextView
    private lateinit var status: TextView
    private lateinit var frame: SwipeMediaFrame
    private lateinit var modify: Button
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) { runOnUiThread { finish() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = try { JSONObject(savedInstanceState?.getString("media") ?: intent.getStringExtra("media") ?: "") }
        catch (error: Exception) { finish(); null }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        current?.let { outState.putString("media", it.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        val media = current ?: return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        registered = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            setBackgroundColor(style.background)
        }
        title = label(media.getString("name")).apply {
            textSize = 18f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        date = label("").apply { textSize = 13f; setTextColor(style.muted) }
        root.addView(title); root.addView(date)
        frame = SwipeMediaFrame(this).apply {
            navigate = { direction -> this@ViewerActivity.navigate(direction) }
            isFocusable = true
            setOnKeyListener { _, key, event ->
                if (event.action == KeyEvent.ACTION_UP && key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    this@ViewerActivity.navigate(if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1); true
                } else false
            }
        }
        ViewCompat.addAccessibilityAction(frame, "Média précédent") { _, _ -> navigate(-1); true }
        ViewCompat.addAccessibilityAction(frame, "Média suivant") { _, _ -> navigate(1); true }
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        status = label("Connexion au PC…").apply { textSize = 13f; setTextColor(style.muted) }
        root.addView(status)
        val actions = LinearLayout(this)
        actions.addView(Button(this).apply {
            text = "Retour"; style.button(this); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        modify = Button(this).apply { isEnabled = false; setOnClickListener { confirmModification() } }
        actions.addView(modify, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        root.addView(actions)
        setContentView(root)
        updateLabels(media)
        scope.launch {
            try {
                val connection = LocalApi(this@ViewerActivity).also { api = it }
                withContext(Dispatchers.IO) { connection.checkConnection() }
                connected = true
                showMedia(media)
                while (isActive) {
                    delay(10_000)
                    withContext(Dispatchers.IO) { connection.checkConnection() }
                }
            } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
                connected = false
                mediaJob?.cancel(); neighborJob?.cancel()
                clearMedia(); modify.isEnabled = false
                status.text = "Média inaccessible : ${error.message}"
            }
        }
    }

    private fun updateLabels(media: JSONObject) {
        title.text = media.getString("name")
        date.text = DateFormat.getDateTimeInstance().format(Date(media.getLong("taken_at") * 1000))
        val trash = media.optString("state") == "trash"
        modify.text = if (trash) "Restaurer" else "Corbeille"
        style.button(modify, primary = trash, destructive = !trash)
    }

    private fun savedStatus(media: JSONObject): String = if (media.optString("state") == "trash") {
        "Corbeille · expiration : " + DateFormat.getDateInstance().format(Date(media.getLong("expires_at") * 1000))
    } else "Conservé sur le PC"

    private fun showMedia(media: JSONObject) {
        mediaJob?.cancel(); neighborJob?.cancel()
        // A video keeps its poster underneath. Releasing the decoder reveals
        // that image instead of an empty surface while the next media loads.
        stopVideo()
        current = media
        previous = null; next = null; neighborsReady = false; pendingDirection = null
        modify.isEnabled = false
        status.text = "Chargement du média…"
        if (imageView == null) {
            frame.removeAllViews()
            frame.addView(ProgressBar(this), FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }
        val connection = api ?: return
        val id = media.getString("id")
        mediaJob = scope.launch {
            try {
                val isVideo = media.getString("mime").startsWith("video/")
                val bitmap = withContext(Dispatchers.IO) {
                    connection.bitmap("/api/media/$id/${if (isVideo) "thumbnail" else "preview"}")
                }
                ensureActive()
                val image = ImageView(this@ViewerActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = media.getString("name")
                    setImageBitmap(bitmap)
                }
                // Replace only when ready, in the same UI frame, at full opacity.
                for (index in 0 until frame.childCount) frame.getChildAt(index).animate().cancel()
                frame.removeAllViews()
                frame.addView(image, FrameLayout.LayoutParams(-1, -1))
                imageView = image
                updateLabels(media)
                modify.isEnabled = connected && !changing
                status.text = savedStatus(media)
                if (isVideo) {
                    val source = DefaultMediaSourceFactory(OkHttpDataSource.Factory(connection.client))
                    val video = ExoPlayer.Builder(this@ViewerActivity).setMediaSourceFactory(source).build()
                    player = video
                    val view = PlayerView(this@ViewerActivity).apply {
                        player = video
                        useController = false
                    }
                    videoView = view
                    // Prepare behind the poster without detaching the surface
                    // when its first frame arrives. The poster stays underneath.
                    frame.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
                    video.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            if (player !== video || !connected) return
                            frame.bringChildToFront(view)
                            view.useController = true
                            video.playWhenReady = true
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            if (player === video) {
                                stopVideo()
                                status.text = "Lecture indisponible : ${error.localizedMessage}"
                            }
                        }
                    })
                    video.setMediaItem(MediaItem.fromUri(connection.baseUrl + "/api/media/$id/original"))
                    video.prepare()
                }
            } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
                stopVideo()
                if (imageView == null) frame.removeAllViews()
                status.text = "Média inaccessible : ${error.message}"
            }
        }
        loadNeighbors()
    }

    private fun loadNeighbors() {
        if (neighborJob?.isActive == true) return
        val connection = api ?: return
        val media = current ?: return
        val id = media.getString("id")
        val trash = if (media.optString("state") == "trash") 1 else 0
        neighborJob = scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { connection.json("/api/media/$id/neighbors?trash=$trash") }
                previous = data.optJSONObject("previous"); next = data.optJSONObject("next")
                neighborsReady = true
                val direction = pendingDirection
                pendingDirection = null
                if (direction != null) navigate(direction)
            } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
                pendingDirection = null
                Toast.makeText(this@ViewerActivity, "Navigation indisponible : ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun navigate(direction: Int) {
        if (!connected || changing) return
        if (!neighborsReady) {
            pendingDirection = direction
            loadNeighbors()
            return
        }
        val target = if (direction > 0) next else previous
        if (target == null) {
            Toast.makeText(this, if (direction > 0) "Fin de la galerie" else "Début de la galerie", Toast.LENGTH_SHORT).show()
        } else showMedia(target)
    }

    private fun confirmModification() {
        if (!connected || changing) return
        val media = current ?: return
        val trash = media.optString("state") == "trash"
        changing = true // Keep the confirmation tied to the displayed media.
        AlertDialog.Builder(this)
            .setTitle(if (trash) "Restaurer ce média ?" else "Mettre dans la corbeille ?")
            .setMessage(if (trash) "Le média reviendra dans la bibliothèque." else "La copie du PC sera supprimée définitivement après 30 jours. Le fichier du téléphone reste intact.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton(if (trash) "Restaurer" else "Corbeille") { _, _ ->
                modify.isEnabled = false
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            api!!.json("/api/media/${media.getString("id")}/${if (trash) "restore" else "trash"}", JSONObject())
                        }
                        finish()
                    } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
                        status.text = error.message; modify.isEnabled = connected
                    } finally { changing = false }
                }
            }
            .setOnDismissListener { if (modify.isEnabled) changing = false }
            .show()
    }

    private fun stopVideo() {
        player?.release(); player = null
        videoView?.let { view ->
            view.animate().cancel()
            view.player = null
            frame.removeView(view)
        }
        videoView = null
    }

    private fun clearMedia() {
        stopVideo()
        if (!::frame.isInitialized) return
        for (index in 0 until frame.childCount) frame.getChildAt(index).animate().cancel()
        frame.removeAllViews()
        imageView = null
    }

    override fun onStop() {
        connected = false; changing = false
        scope.cancel()
        clearMedia()
        api?.close(); api = null
        if (registered) { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback); registered = false }
        // No media is kept visible or accessible in an offline screen.
        findViewById<android.view.ViewGroup>(android.R.id.content).removeAllViews()
        super.onStop()
    }

    private fun label(value: String) = TextView(this).apply {
        text = value; setTextColor(style.ink); textSize = 16f; setPadding(0, dp(6), 0, dp(6))
        includeFontPadding = false
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
