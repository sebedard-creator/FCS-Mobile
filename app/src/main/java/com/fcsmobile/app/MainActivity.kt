package com.fcsmobile.app

import android.Manifest
import android.content.Intent
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings
    private lateinit var body: FrameLayout
    private lateinit var connectionLabel: TextView
    private lateinit var backupBanner: LinearLayout
    private lateinit var backupLabel: TextView
    private lateinit var backupProgress: ProgressBar
    private lateinit var backupDot: View
    private data class NavigationItem(val view: LinearLayout, val icon: ImageView, val title: TextView)
    private val navigation = linkedMapOf<String, NavigationItem>()
    private var activeBackup: WorkInfo? = null
    private var backupDetails: TextView? = null
    private var page = "library"
    private var pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var polling: Job? = null
    private var api: LocalApi? = null
    private var online = false
    private var callbackRegistered = false
    private val style by lazy { AppStyle(this) }
    private val ink get() = style.ink
    private val muted get() = style.muted
    private val surface get() = style.surface
    private val accent get() = style.accent
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(32L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        AppSettings.schedule(this)
        if (MediaScanner.fullAccess(this)) {
            settings.status = "En attente de charge et du serveur local"
            AppSettings.requestBackup(this)
        }
        render()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (page == "backup" || page == "settings") render()
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) { runOnUiThread { markOffline("Réseau local déconnecté") } }
        override fun onAvailable(network: Network) { runOnUiThread { if (page == "library" || page == "trash") render() } }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        settings = AppSettings(this)
        page = state?.getString("page") ?: intent.getStringExtra("page") ?: if (settings.configured) "library" else "settings"
        window.statusBarColor = style.background
        window.navigationBarColor = style.background
        val root = column().apply { setBackgroundColor(style.background); setPadding(dp(16), dp(12), dp(16), dp(8)) }
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_gallery)
            imageTintList = ColorStateList.valueOf(accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
        brand.addView(label("FCS Mobile", 23).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        root.addView(brand)
        connectionLabel = label("Bibliothèque personnelle · réseau local", 12, muted)
        root.addView(connectionLabel)
        backupBanner = column().apply {
            background = style.touchable(style.accentContainer, 16)
            setPadding(dp(14), dp(8), dp(14), dp(12))
            visibility = View.GONE
            setOnClickListener { page = "backup"; render() }
        }
        backupLabel = label("", 14, accent)
        backupProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        backupBanner.addView(backupLabel)
        backupBanner.addView(backupProgress, LinearLayout.LayoutParams(-1, dp(6)))
        root.addView(backupBanner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })
        val nav = LinearLayout(this).apply {
            background = style.rounded(surface, 20)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf(Triple("library", "Galerie", R.drawable.ic_nav_gallery), Triple("trash", "Corbeille", R.drawable.ic_nav_trash),
            Triple("backup", "Sauvegarde", R.drawable.ic_nav_backup), Triple("settings", "Réglages", R.drawable.ic_nav_settings)).forEach { (id, title, drawable) ->
            val item = column().apply {
                gravity = Gravity.CENTER
                isFocusable = true
                contentDescription = title
                setOnClickListener { page = id; render() }
                setPadding(dp(2), dp(8), dp(2), dp(8))
            }
            val iconFrame = FrameLayout(this)
            val icon = ImageView(this).apply {
                setImageResource(drawable)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            iconFrame.addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            if (id == "backup") {
                backupDot = View(this).apply { background = style.rounded(accent, 4); visibility = View.GONE }
                iconFrame.addView(backupDot, FrameLayout.LayoutParams(dp(6), dp(6), Gravity.TOP or Gravity.END))
            }
            item.addView(iconFrame, LinearLayout.LayoutParams(dp(30), dp(24)))
            val caption = TextView(this).apply {
                text = title; textSize = 11f; gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            item.addView(caption, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            navigation[id] = NavigationItem(item, icon, caption)
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setContentView(root)
        AppSettings.schedule(this)
        WorkManager.getInstance(this).getWorkInfosByTagLiveData(BackupWorker::class.java.name).observe(this) { work ->
            val wasRunning = activeBackup != null
            activeBackup = work.filter { it.state == WorkInfo.State.RUNNING }
                .maxByOrNull { it.progress.getLong("updated_at", 0) }
            updateBackupIndicator()
            if (page == "backup") {
                if (wasRunning != (activeBackup != null)) renderBackup()
                else activeBackup?.let { backupDetails?.text = backupDescription(BackupProgress.from(it.progress)) }
            }
        }
        if (settings.configured && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("page") == "backup") { page = "backup"; render() }
    }

    private fun updateBackupIndicator() {
        val info = activeBackup
        backupBanner.visibility = if (info != null) View.VISIBLE else View.GONE
        backupDot.visibility = if (info != null) View.VISIBLE else View.GONE
        navigation["backup"]?.view?.contentDescription = if (info != null) "Sauvegarde en cours" else "Sauvegarde"
        if (info != null) {
            val progress = BackupProgress.from(info.progress)
            backupLabel.text = "↑ Sauvegarde en cours\n${progress.summary}"
            backupProgress.isIndeterminate = progress.total == 0
            backupProgress.max = progress.total.coerceAtLeast(1)
            backupProgress.progress = progress.completed.coerceIn(0, progress.total.coerceAtLeast(0))
        }
    }

    private fun backupDescription(progress: BackupProgress) = "↑ Sauvegarde en cours · ${progress.phase}" +
        if (progress.total > 0) "\n${progress.summary}" else ""

    private fun notificationsEnabled(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(BackupNotifications.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); super.onSaveInstanceState(outState) }
    override fun onStart() {
        super.onStart()
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback)
        callbackRegistered = true
        render()
        polling = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                if (page == "backup") renderBackup()
                if (settings.configured && (page == "library" || page == "trash")) {
                    try {
                        withContext(Dispatchers.IO) { connection().checkConnection() }
                        if (!online) render()
                    } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { markOffline("Bibliothèque inaccessible — rejoindre le réseau du PC") }
                }
            }
        }
    }
    override fun onStop() {
        polling?.cancel()
        pageScope.cancel()
        api?.close(); api = null
        cache.evictAll()
        online = false
        body.removeAllViews()
        if (callbackRegistered) {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
            callbackRegistered = false
        }
        super.onStop()
    }
    private fun connection(): LocalApi = api ?: LocalApi(this).also { api = it }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(text: String, size: Int = 16, color: Int = ink) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color); setPadding(0, dp(6), 0, dp(6))
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(text: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        this.text = text; style.button(this, primary); setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(6) }
    }
    private fun panel() = column().apply {
        background = style.rounded(surface, 16)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) }
    }
    private fun replace(view: View) { body.removeAllViews(); body.addView(view, FrameLayout.LayoutParams(-1, -1)) }
    private fun scroll(column: LinearLayout) = ScrollView(this).apply { addView(column) }
    private fun render() {
        navigation.forEach { (id, item) ->
            val selected = id == page
            item.view.isSelected = selected
            item.view.background = style.touchable(if (selected) style.accentContainer else Color.TRANSPARENT, 16)
            item.icon.imageTintList = ColorStateList.valueOf(if (selected) accent else muted)
            item.title.setTextColor(if (selected) accent else muted)
            item.title.typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }
        pageScope.cancel()
        pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        when (page) {
            "settings" -> renderSettings()
            "backup" -> renderBackup()
            else -> renderGallery()
        }
    }
    private fun markOffline(message: String) {
        online = false
        api?.close(); api = null
        cache.evictAll()
        connectionLabel.text = "Hors connexion locale"
        if (page == "library" || page == "trash") {
            pageScope.cancel()
            replace(column().apply {
                addView(label(message, 20))
                addView(label("La bibliothèque est disponible uniquement lorsque ce téléphone peut joindre ton PC par Wi-Fi.", 15, muted))
                addView(button("Réessayer") { render() })
                addView(button("Configurer le serveur") { page = "settings"; render() })
            })
        }
    }
    private fun renderGallery() {
        if (!settings.configured) { markOffline("Configurer le serveur dans Réglages"); return }
        if (LocalApi.wifi(this) == null) { markOffline("Connexion Wi-Fi locale requise"); return }
        val trash = page == "trash"
        val content = column()
        val heading = label(if (trash) "Corbeille · 30 jours" else "Bibliothèque", 23)
        content.addView(heading)
        val explanation = label(if (trash) "Les médias peuvent être restaurés pendant 30 jours." else "Photos et vidéos conservées sur ton PC", 13, muted)
        content.addView(explanation)
        var loading = false
        var hasMore = true
        val grid = GridView(this).apply {
            numColumns = 3; horizontalSpacing = dp(4); verticalSpacing = dp(4)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
        }
        val refresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(accent)
            setProgressBackgroundColorSchemeColor(surface)
            setOnChildScrollUpCallback { _, _ -> loading || grid.canScrollVertically(-1) }
            addView(grid, ViewGroup.LayoutParams(-1, -1))
        }
        val gallery = FrameLayout(this)
        gallery.addView(refresh, FrameLayout.LayoutParams(-1, -1))
        val dateIndicator = ScrollDateIndicator(this)
        gallery.addView(dateIndicator, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(16)
        })
        content.addView(gallery, LinearLayout.LayoutParams(-1, 0, 1f))
        val loadingMore = ProgressBar(this).apply {
            visibility = View.GONE
            contentDescription = "Chargement des médias suivants"
        }
        content.addView(loadingMore, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8); bottomMargin = dp(8)
        })
        replace(content)
        val items = mutableListOf<JSONObject>()
        val adapter = GalleryAdapter(items, grid)
        val galleryScope = pageScope
        val thumbnails = ThumbnailLoader(galleryScope, cache,
            fetch = { id -> connection().bitmap("/api/media/$id/thumbnail") },
            ready = { id, bitmap -> adapter.showThumbnail(id, bitmap) },
            failed = { id -> adapter.showError(id) })
        var scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE
        var updatePending = false
        fun updateThumbnails() {
            if (!galleryScope.isActive) return
            val first = grid.firstVisiblePosition.coerceAtLeast(0)
            val last = grid.lastVisiblePosition.coerceAtMost(items.lastIndex)
            if (last < first) { thumbnails.update(emptyList(), emptyList(), false); return }
            val visible = (first..last).map { items[it].getString("id") }
            val nearby = ((first - 3).coerceAtLeast(0)..(last + 6).coerceAtMost(items.lastIndex))
                .filter { it !in first..last }.map { items[it].getString("id") }
            thumbnails.update(visible, nearby, scrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING)
        }
        adapter.onBind = {
            if (!updatePending) {
                updatePending = true
                grid.post { updatePending = false; updateThumbnails() }
            }
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            if (online) startActivity(Intent(this, ViewerActivity::class.java).putExtra("media", items[position].toString()))
        }
        fun load(reset: Boolean = false) {
            if (loading || (!reset && !hasMore)) return
            loading = true
            refresh.isRefreshing = reset || items.isEmpty()
            loadingMore.visibility = if (!reset && items.isNotEmpty()) View.VISIBLE else View.GONE
            val offset = if (reset) 0 else items.size
            pageScope.launch {
                try {
                    val data = withContext(Dispatchers.IO) {
                        connection().checkConnection()
                        connection().json("/api/media?trash=${if (trash) 1 else 0}&offset=$offset&limit=120")
                    }
                    online = true; connectionLabel.text = "Connecté au PC · réseau local"
                    val array = data.getJSONArray("items")
                    if (reset) { thumbnails.reset(); items.clear() }
                    for (i in 0 until array.length()) items.add(array.getJSONObject(i))
                    val total = data.getInt("total")
                    heading.text = "${if (trash) "Corbeille" else "Bibliothèque"} · $total"
                    explanation.text = if (total == 0) {
                        if (trash) "La corbeille est vide." else "La bibliothèque est vide. Branche ton téléphone pour lancer la sauvegarde."
                    } else if (trash) "Les médias peuvent être restaurés pendant 30 jours." else "Photos et vidéos conservées sur ton PC"
                    hasMore = array.length() > 0 && items.size < total
                    adapter.notifyDataSetChanged()
                    if (reset) grid.setSelection(0)
                } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { markOffline(error.message ?: "Serveur inaccessible") }
                finally { loading = false; refresh.isRefreshing = false; loadingMore.visibility = View.GONE }
            }
        }
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                scrollState = state
                dateIndicator.onScrollStateChanged(state)
                updateThumbnails()
            }
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (visibleItemCount > 0 && firstVisibleItem in items.indices) {
                    val first = grid.getChildAt(0)
                    val height = first?.height?.takeIf { it > 0 } ?: (grid.width / 3).coerceAtLeast(1)
                    val position = firstVisibleItem / 3f - (first?.top ?: 0).toFloat() / height
                    dateIndicator.onScroll(position, items[firstVisibleItem].getLong("taken_at"))
                }
                if (visibleItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 36) load()
                updateThumbnails()
            }
        })
        refresh.setOnRefreshListener { load(reset = true) }
        ViewCompat.addAccessibilityAction(grid, "Actualiser") { _, _ -> load(reset = true); true }
        load()
    }

    private class GalleryCell(val image: ImageView, val caption: TextView) {
        var id: String? = null
        var bitmap: Bitmap? = null
    }

    private inner class GalleryAdapter(private val items: List<JSONObject>, private val grid: GridView) : BaseAdapter() {
        var onBind: () -> Unit = {}
        private val dates = DateFormat.getDateInstance(DateFormat.SHORT)
        private val edge = (resources.displayMetrics.widthPixels - dp(40)) / 3

        fun showThumbnail(id: String, bitmap: Bitmap) {
            for (index in 0 until grid.childCount) {
                val cell = grid.getChildAt(index).tag as? GalleryCell ?: continue
                if (cell.id == id) { cell.bitmap = bitmap; cell.image.setImageBitmap(bitmap) }
            }
        }

        fun showError(id: String) {
            for (index in 0 until grid.childCount) {
                val cell = grid.getChildAt(index).tag as? GalleryCell ?: continue
                if (cell.id == id) cell.caption.text = "Aperçu indisponible"
            }
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val card = (recycled as? FrameLayout) ?: FrameLayout(this@MainActivity).apply {
                background = style.rounded(surface, 8)
                clipToOutline = true
                val image = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                val caption = label("", 11).apply { setBackgroundColor(style.mediaCaption); setPadding(dp(6), dp(4), dp(6), dp(4)) }
                addView(image, FrameLayout.LayoutParams(-1, -1))
                addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                tag = GalleryCell(image, caption)
            }
            if (card.layoutParams?.height != edge) card.layoutParams = AbsListView.LayoutParams(-1, edge)
            val item = items[position]
            val id = item.getString("id")
            val cell = card.tag as GalleryCell
            if (cell.id != id) {
                cell.id = id
                cell.bitmap = null
                cell.image.setImageDrawable(null)
            }
            cell.caption.text = (if (item.getString("mime").startsWith("video/")) "▶ " else "") +
                dates.format(Date(item.getLong("taken_at") * 1000))
            cell.image.contentDescription = item.getString("name")
            cache.get(id)?.let { bitmap ->
                if (cell.bitmap !== bitmap) { cell.bitmap = bitmap; cell.image.setImageBitmap(bitmap) }
            }
            if (cell.bitmap == null) onBind()
            return card
        }
    }

    private fun renderBackup() {
        val content = column()
        content.addView(label("Sauvegarde", 23))
        content.addView(label("Automatique · réseau local · téléphone branché", 14, muted))
        val statusPanel = panel()
        statusPanel.addView(label("État actuel", 13, muted))
        backupDetails = null
        activeBackup?.let {
            val progress = BackupProgress.from(it.progress)
            backupDetails = label(backupDescription(progress), 18, accent)
            statusPanel.addView(backupDetails)
        }
        if (activeBackup == null) statusPanel.addView(label(settings.status))
        content.addView(statusPanel)
        val history = panel()
        if (settings.lastSuccess > 0) history.addView(label("Dernière vérification complète\n" + DateFormat.getDateTimeInstance().format(Date(settings.lastSuccess)), 14, muted))
        history.addView(label(if (settings.lastAutomaticSuccess > 0) "Dernière sauvegarde automatique\n" + DateFormat.getDateTimeInstance().format(Date(settings.lastAutomaticSuccess))
            else "Aucune sauvegarde automatique vérifiée enregistrée", 14, muted))
        content.addView(history)
        val conditions = panel()
        conditions.addView(label("Conditions de sauvegarde", 15))
        conditions.addView(label(if (LocalApi.charging(this)) "✓ Téléphone branché" else "En attente : brancher le téléphone", 14, muted))
        conditions.addView(label(if (LocalApi.wifi(this) != null) "✓ Wi-Fi connecté · le PC doit être joignable" else "En attente : rejoindre le réseau local", 14, muted))
        content.addView(conditions)
        if (!notificationsEnabled()) content.addView(button("Autoriser les notifications de sauvegarde") { requestNotifications() })
        if (!MediaScanner.fullAccess(this)) content.addView(button("Autoriser toutes les photos et vidéos") { requestPermissions() })
        content.addView(button(if (activeBackup != null) "Sauvegarde en cours" else "Programmer une sauvegarde", primary = true) {
            if (!settings.configured) { page = "settings"; render() }
            else if (!MediaScanner.fullAccess(this)) requestPermissions()
            else {
                if (activeBackup != null) return@button
                settings.status = "Sauvegarde demandée — en attente de charge et du serveur local"
                AppSettings.requestBackup(this); renderBackup()
            }
        }.apply { isEnabled = activeBackup == null })
        content.addView(label("Les fichiers exclus ne sont pas envoyés. Les médias déjà archivés restent conservés. Les erreurs déclenchent une nouvelle tentative; elles ne sont pas comptées comme des sauvegardes réussies.", 14, muted))
        replace(scroll(content))
    }

    private fun requestPermissions() {
        val requested = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            requested += Manifest.permission.READ_MEDIA_IMAGES
            requested += Manifest.permission.READ_MEDIA_VIDEO
            if (Build.VERSION.SDK_INT >= 34) requested += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        } else requested += Manifest.permission.READ_EXTERNAL_STORAGE
        requested += Manifest.permission.ACCESS_MEDIA_LOCATION
        permissions.launch(requested.toTypedArray())
    }

    private fun renderSettings() {
        val content = column()
        content.addView(label("Réglages", 23))
        content.addView(label("Connexion et préférences de sauvegarde", 14, muted))
        val server = panel()
        server.addView(label("Serveur local", 18))
        val host = EditText(this).apply {
            id = View.generateViewId()
            setText(settings.host); hint = "192.168.1.100"; isSingleLine = true; inputType = InputType.TYPE_CLASS_PHONE
            style.field(this)
        }
        server.addView(label("Adresse IPv4 du PC", 13, muted).apply { labelFor = host.id })
        server.addView(host)
        val token = EditText(this).apply {
            id = View.generateViewId()
            setText(settings.token)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            style.field(this)
        }
        server.addView(label("Clé de connexion", 13, muted).apply { labelFor = token.id })
        server.addView(token)
        server.addView(label("Disponible dans l’administration sur le PC", 12, muted))
        val auto = SwitchCompat(this).apply {
            text = "Sauvegarde automatique"; textSize = 15f; setTextColor(ink); isChecked = settings.automatic
            minHeight = dp(48); setPadding(0, dp(4), 0, dp(4))
        }
        val automatic = panel()
        automatic.addView(auto)
        automatic.addView(label("Uniquement sur le réseau local, lorsque le téléphone est branché.", 13, muted))
        content.addView(server)
        content.addView(automatic)
        val message = label("", 14, accent)
        content.addView(button("Enregistrer et tester", primary = true) {
            val value = host.text.toString().trim()
            if (!LocalApi.isLocalHost(value) || token.text.isBlank()) { message.text = "Adresse IPv4 privée et clé requises"; return@button }
            api?.close(); api = null; cache.evictAll()
            settings.host = value; settings.token = token.text.toString().trim(); settings.automatic = auto.isChecked
            AppSettings.schedule(this)
            if (!notificationsEnabled()) requestNotifications()
            message.text = "Réglages enregistrés. Test de connexion…"
            pageScope.launch {
                try {
                    val status = withContext(Dispatchers.IO) { connection().checkConnection() }
                    message.text = "Connecté · ${status.getInt("media_count")} médias dans la bibliothèque"
                    if (!MediaScanner.fullAccess(this@MainActivity)) requestPermissions()
                } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { message.text = "Réglages enregistrés · ${error.message}" }
            }
        })
        content.addView(message)
        val folders = panel()
        folders.addView(label("Dossiers sauvegardés", 18))
        folders.addView(label("Tous les dossiers sont inclus par défaut. Décoche ceux à ignorer. Cela ne supprime aucun média déjà archivé.", 14, muted))
        folders.addView(button("Choisir les dossiers") { chooseFolders() })
        folders.addView(label(if (settings.excluded.isEmpty()) "Aucun dossier exclu" else "Dossiers exclus :\n" + settings.excluded.sorted().joinToString("\n"), 13, muted))
        content.addView(folders)
        val permissions = panel()
        permissions.addView(label("Autorisations", 18))
        permissions.addView(button("Photos et vidéos") { requestPermissions() })
        permissions.addView(button(if (notificationsEnabled()) "Réglages des notifications" else "Autoriser les notifications") { requestNotifications() })
        content.addView(permissions)
        content.addView(label("La galerie et la corbeille sont accessibles uniquement sur le réseau local. FCS Mobile ne supprime jamais les fichiers du téléphone.", 14, muted))
        replace(scroll(content))
    }
    private fun chooseFolders() {
        if (!MediaScanner.fullAccess(this)) { requestPermissions(); return }
        pageScope.launch {
            try {
                val folders = withContext(Dispatchers.IO) { (MediaScanner.scan(this@MainActivity, false).map { it.folder } + settings.excluded).distinct().sorted() }
                if (folders.isEmpty()) { Toast.makeText(this@MainActivity, "Aucun dossier de médias trouvé", Toast.LENGTH_LONG).show(); return@launch }
                val checked = folders.map { folder -> settings.excluded.none { folder.startsWith(it.trim('/') + "/") } }.toBooleanArray()
                AlertDialog.Builder(this@MainActivity).setTitle("Dossiers à sauvegarder")
                    .setMultiChoiceItems(folders.toTypedArray(), checked) { dialog, index, selected ->
                        val prefix = folders[index]
                        folders.forEachIndexed { other, folder ->
                            if (folder.startsWith(prefix) || (selected && prefix.startsWith(folder))) {
                                checked[other] = selected
                                (dialog as AlertDialog).listView.setItemChecked(other, selected)
                            }
                        }
                    }
                    .setPositiveButton("Enregistrer") { _, _ ->
                        settings.excluded = folders.filterIndexed { index, _ -> !checked[index] }.toSet()
                        render()
                    }.setNegativeButton("Annuler", null).show()
            } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { Toast.makeText(this@MainActivity, error.message, Toast.LENGTH_LONG).show() }
        }
    }
}
