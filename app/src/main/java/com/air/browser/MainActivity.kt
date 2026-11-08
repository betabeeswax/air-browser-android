package com.air.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private data class BrowserTab(
        val id: Int,
        val webView: WebView,
        var title: String = "New tab",
        var url: String = HOME_BASE
    )

    companion object {
        private const val HOME_BASE = "https://air-browser.local/"
        private const val PREFS = "air_browser_local"
        private const val MAX_RESTORED_TABS = 8
    }

    private var activeProfile = "Personal"
    private val prefs: android.content.SharedPreferences get() = getSharedPreferences(PREFS + "_" + activeProfile, Context.MODE_PRIVATE)
    private val basePrefs by lazy { getSharedPreferences(PREFS + "_profiles", Context.MODE_PRIVATE) }
    private val tabs = mutableListOf<BrowserTab>()
    private var nextTabId = 1
    private var activeTabId = -1

    private lateinit var root: LinearLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var addressBar: EditText
    private lateinit var tabCount: TextView
    private lateinit var backButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var reloadButton: TextView
    private lateinit var profileButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeProfile = basePrefs.getString("last_profile", "Personal") ?: "Personal"
        migrateLegacyPreferences()
        window.statusBarColor = Color.rgb(11, 16, 32)
        window.navigationBarColor = Color.rgb(11, 16, 32)
        window.decorView.systemUiVisibility = 0
        CookieManager.getInstance().setAcceptCookie(true)
        buildInterface()
        restoreTabs()
        if (prefs.getBoolean("automatic_updates", true)) Handler(Looper.getMainLooper()).postDelayed({ checkForUpdates(false) }, 4500)
    }

    private fun migrateLegacyPreferences() {
        val legacy = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val personal = getSharedPreferences(PREFS + "_Personal", Context.MODE_PRIVATE)
        if (!personal.contains("open_tabs") && legacy.all.isNotEmpty()) {
            val editor = personal.edit()
            legacy.all.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    else -> Unit
                }
            }
            editor.apply()
        }
    }

    private fun buildInterface() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 16, 32))
            setPadding(dp(10), dp(6), dp(10), dp(4))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, 0, 0)
        }
        val brand = TextView(this).apply {
            text = "AIR"
            textSize = 22f
            setTextColor(Color.rgb(76, 169, 255))
            typeface = Typeface.DEFAULT_BOLD
        }
        val browserLabel = TextView(this).apply {
            text = "  Browser"
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_launcher); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(32), dp(32)))
        header.addView(brand)
        header.addView(browserLabel)
        profileButton = actionText("● $activeProfile") { showProfilesDialog() }.apply { textSize = 12f; setTextColor(Color.rgb(151, 204, 255)); setPadding(dp(7), 0, dp(7), 0) }
        header.addView(profileButton, LinearLayout.LayoutParams(-2, dp(42)))
        header.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        tabCount = actionText("▢ 1") { showTabsDialog() }
        header.addView(tabCount, buttonParams())
        header.addView(actionText("⋮") { showMainMenu(it) }, buttonParams())
        root.addView(header, LinearLayout.LayoutParams(-1, dp(46)))

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backButton = actionText("‹") { goBack() }
        forwardButton = actionText("›") { goForward() }
        reloadButton = actionText("↻") { reloadPage() }
        addressRow.addView(backButton, buttonParams())
        addressRow.addView(forwardButton, buttonParams())
        addressRow.addView(reloadButton, buttonParams())

        addressBar = EditText(this).apply {
            hint = "Search the web"
            setHintTextColor(Color.rgb(160, 173, 195))
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(13), 0, dp(8), 0)
            background = rounded(Color.rgb(28, 39, 61), dp(16))
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE
                ) {
                    navigateFromAddress()
                    true
                } else false
            }
        }
        addressRow.addView(addressBar, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        })
        addressRow.addView(actionText("Go") { navigateFromAddress() }, buttonParams())
        root.addView(addressRow, LinearLayout.LayoutParams(-1, dp(52)))

        webContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        root.addView(webContainer, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        bottom.addView(bottomAction("⌂", "Home") { loadHome() }, equalWeight())
        bottom.addView(bottomAction("☆", "Bookmarks") { showBookmarks() }, equalWeight())
        bottom.addView(bottomAction("↓", "Downloads") { openDownloads() }, equalWeight())
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(48)))

        setContentView(root)
        updateNavigationButtons()
    }

    private fun restoreTabs() {
        val saved = try {
            JSONArray(prefs.getString("open_tabs", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        if (saved.length() == 0) {
            addTab(HOME_BASE)
            return
        }
        val count = minOf(saved.length(), MAX_RESTORED_TABS)
        for (i in 0 until count) {
            val url = saved.optString(i, HOME_BASE).ifBlank { HOME_BASE }
            addTab(url, switchTo = i == count - 1)
        }
    }

    private fun addTab(initialUrl: String = HOME_BASE, switchTo: Boolean = true): BrowserTab {
        val webView = WebView(this)
        webView.setBackgroundColor(Color.WHITE)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false

        val tab = BrowserTab(nextTabId++, webView)
        tabs.add(tab)
        webContainer.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "This link can't be opened.", Toast.LENGTH_SHORT).show()
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                tab.url = url
                if (activeTabId == tab.id) {
                    addressBar.setText(if (isHome(url)) "" else url)
                    updateNavigationButtons()
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                tab.url = url
                tab.title = view.title?.takeIf { it.isNotBlank() } ?: if (isHome(url)) "New tab" else url
                if (activeTabId == tab.id) {
                    addressBar.setText(if (isHome(url)) "" else url)
                    updateNavigationButtons()
                }
                if (!isHome(url)) addHistory(tab.title, url)
                saveTabs()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank()) {
                    tab.title = title
                    updateTabCount()
                }
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                val newTab = addTab(HOME_BASE, true)
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = newTab.webView
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        })

        if (switchTo) switchToTab(tab.id)
        if (isHome(initialUrl)) showHomeIn(tab) else webView.loadUrl(initialUrl)
        saveTabs()
        return tab
    }

    private fun switchToTab(id: Int) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        activeTabId = id
        tabs.forEach { it.webView.visibility = if (it.id == id) View.VISIBLE else View.GONE }
        addressBar.setText(if (isHome(tab.url)) "" else tab.url)
        updateTabCount()
        updateNavigationButtons()
    }

    private fun closeTab(id: Int) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val wasActive = activeTabId == id
        val tab = tabs.removeAt(index)
        webContainer.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()
        if (tabs.isEmpty()) {
            addTab(HOME_BASE)
        } else if (wasActive) {
            switchToTab(tabs[minOf(index, tabs.lastIndex)].id)
        }
        saveTabs()
        updateTabCount()
    }

    private fun showTabsDialog() {
        if (tabs.isEmpty()) return
        val labels = tabs.mapIndexed { index, tab ->
            "${index + 1}. ${tab.title.ifBlank { "New tab" }}"
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Open tabs")
            .setItems(labels) { _, which -> switchToTab(tabs[which].id) }
            .setPositiveButton("New tab") { _, _ -> addTab() }
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnShowListener {
            dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
                val tab = tabs.getOrNull(position)
                if (tab != null && tabs.size > 1) closeTab(tab.id)
                dialog.dismiss()
                true
            }
        }
        dialog.show()
    }

    private fun navigateFromAddress() {
        val raw = addressBar.text.toString().trim()
        if (raw.isEmpty()) return
        val encoded = try { URLEncoder.encode(raw, "UTF-8") } catch (_: Exception) { raw }
        val target = when {
            raw.startsWith("https://", true) || raw.startsWith("http://", true) -> raw
            raw.contains(" ") || !raw.contains(".") -> searchUrl(raw)
            else -> "https://$raw"
        }
        val tab = activeTab() ?: addTab()
        tab.url = target
        tab.webView.loadUrl(target)
        addressBar.clearFocus()
    }

    private fun loadHome() {
        val tab = activeTab() ?: addTab()
        showHomeIn(tab)
    }

    private fun showHomeIn(tab: BrowserTab) {
        tab.url = HOME_BASE
        tab.title = "New tab"
        tab.webView.loadDataWithBaseURL(HOME_BASE, homeHtml(), "text/html", "UTF-8", null)
        if (activeTabId == tab.id) {
            addressBar.setText("")
            updateNavigationButtons()
        }
        saveTabs()
    }

    private fun homeHtml(): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
        *{box-sizing:border-box}body{margin:0;background:#0b1020;color:#f5f8ff;font-family:Arial,sans-serif;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}
        main{width:100%;max-width:620px;text-align:center;margin-top:-4vh}.logo{font-size:54px;font-weight:800;letter-spacing:3px;color:#55adff}.sub{color:#9eacc5;margin:6px 0 18px}
        .clock{font-size:28px;font-weight:700;margin:0 0 4px}.date{color:#9eacc5;margin:0 0 24px}
        form{display:flex;gap:8px;background:#1c273d;border:1px solid #314361;border-radius:18px;padding:7px}
        input{min-width:0;flex:1;background:transparent;border:0;outline:0;color:white;font-size:16px;padding:10px}
        button{border:0;border-radius:13px;background:#2789e8;color:white;font-weight:700;padding:0 17px;font-size:15px}
        .links,.widgets{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-top:20px}
        a{color:#dce9ff;text-decoration:none;background:#151f33;border:1px solid #263650;border-radius:15px;padding:16px 6px;font-size:14px}
        .widgets a{display:flex;flex-direction:column;gap:6px}.widgets b{font-size:14px}.widgets small{color:#9eacc5;font-size:11px}
        .foot{display:block;color:#8292ad;margin-top:24px}
        </style></head><body><main><div class="logo">AIR</div><div class="sub">A clearer way to browse.</div><div class="clock" id="clock">--:--</div><div class="date" id="date">Loading date…</div>
        <form onsubmit="event.preventDefault();var q=document.getElementById('q').value.trim();if(q){var e='__SEARCH_ENGINE__';var u=e==='Bing'?'https://www.bing.com/search?q=':e==='DuckDuckGo'?'https://duckduckgo.com/?q=':'https://www.google.com/search?q=';location.href=u+encodeURIComponent(q)}">
        <input id="q" placeholder="Search the web" autocomplete="off"><button type="submit">Search</button></form>
        <div class="links"><a href="https://www.google.com">Google</a><a href="https://www.youtube.com">YouTube</a><a href="https://www.wikipedia.org">Wikipedia</a></div>
        <div class="widgets"><a href="https://www.google.com/search?q=weather"><b>☀ Weather</b><small>Local forecast</small></a><a href="https://quietchat-rho.vercel.app"><b>◉ QuietChat</b><small>Open your chats</small></a><a href="https://www.wikipedia.org"><b>✦ Discover</b><small>Learn something new</small></a></div>
        <small class="foot">Your tabs and bookmarks stay on this device.</small></main><script>function tick(){const d=new Date();document.getElementById('clock').textContent=d.toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'});document.getElementById('date').textContent=d.toLocaleDateString([],{weekday:'long',month:'long',day:'numeric'})}tick();setInterval(tick,1000)</script></body></html>
    """.trimIndent().replace("__SEARCH_ENGINE__", prefs.getString("search_engine", "Google") ?: "Google")

    private fun goBack() {
        val web = activeTab()?.webView ?: return
        if (web.canGoBack()) web.goBack()
    }

    private fun goForward() {
        val web = activeTab()?.webView ?: return
        if (web.canGoForward()) web.goForward()
    }

    private fun reloadPage() {
        val web = activeTab()?.webView ?: return
        if (isHome(activeTab()?.url)) loadHome() else web.reload()
    }

    private fun updateNavigationButtons() {
        val web = activeTab()?.webView
        backButton.alpha = if (web?.canGoBack() == true) 1f else 0.42f
        forwardButton.alpha = if (web?.canGoForward() == true) 1f else 0.42f
        reloadButton.alpha = 1f
    }

    private fun updateTabCount() {
        tabCount.text = "▢ ${tabs.size}"
    }

    private fun showMainMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "New tab")
        popup.menu.add(0, 2, 1, "Add bookmark")
        popup.menu.add(0, 3, 2, "Bookmarks")
        popup.menu.add(0, 4, 3, "History")
        popup.menu.add(0, 5, 4, "Downloads")
        popup.menu.add(0, 6, 5, "Share page")
        popup.menu.add(0, 7, 6, "Clear browsing history")
        popup.menu.add(0, 8, 7, "About AIR Browser")
        popup.menu.add(0, 9, 8, "Check for updates")
        popup.menu.add(0, 10, 9, "Automatic updates: " + if (prefs.getBoolean("automatic_updates", true)) "On" else "Off")
        popup.menu.add(0, 11, 10, "Open QuietChat")
        popup.menu.add(0, 12, 11, "Reading mode")
        popup.menu.add(0, 13, 12, "Profiles & search settings")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> addTab()
                2 -> addBookmark()
                3 -> showBookmarks()
                4 -> showHistory()
                5 -> openDownloads()
                6 -> sharePage()
                7 -> clearHistory()
                8 -> AlertDialog.Builder(this).setTitle("AIR Browser")
                    .setMessage("AIR Browser for Android\nEarly testing version")
                    .setPositiveButton("OK", null).show()
                9 -> checkForUpdates(true)
                10 -> { val enabled = !prefs.getBoolean("automatic_updates", true); prefs.edit().putBoolean("automatic_updates", enabled).apply(); Toast.makeText(this, "Automatic updates " + if (enabled) "enabled" else "disabled", Toast.LENGTH_SHORT).show() }
                11 -> addTab("https://quietchat-rho.vercel.app")
                12 -> toggleReadingMode()
                13 -> showProfilesDialog()
            }
            true
        }
        popup.show()
    }

    private fun toggleReadingMode() {
        val web = activeTab()?.webView ?: return
        val script = "(() => { const id='air-reading-mode'; const old=document.getElementById(id); if(old){old.remove();return 'off'} const s=document.createElement('style');s.id=id;s.textContent='body{max-width:820px!important;margin:32px auto!important;padding:24px!important;background:#f7f2e8!important;color:#242424!important;font:19px/1.8 Georgia,serif!important}body *{font-family:Georgia,serif!important;line-height:1.8!important}header,nav,footer,aside,[role=navigation]{display:none!important}img{max-width:100%!important;height:auto!important}';document.head.appendChild(s);return 'on' })()"
        web.evaluateJavascript(script, null)
        Toast.makeText(this, "Reading mode toggled", Toast.LENGTH_SHORT).show()
    }

    private fun checkForUpdates(manual: Boolean) {
        if (!manual && !prefs.getBoolean("automatic_updates", true)) return
        Thread {
            var latestBuild = 0
            var downloadUrl = ""
            try {
                val connection = (URL("https://api.github.com/repos/betabeeswax/air-browser-android/releases?per_page=10").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 10000; setRequestProperty("User-Agent", "AIR-Browser-Android")
                }
                val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                connection.disconnect()
                for (i in 0 until releases.length()) {
                    val release = releases.optJSONObject(i) ?: continue
                    if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                    val tag = release.optString("tag_name")
                    val build = Regex("(\\d+)$").find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val assets = release.optJSONArray("assets") ?: JSONArray()
                    var apk = ""
                    for (j in 0 until assets.length()) { val asset = assets.optJSONObject(j) ?: continue; if (asset.optString("name") == "AIR-Browser-Android.apk") apk = asset.optString("browser_download_url") }
                    if (build > latestBuild && apk.isNotBlank()) { latestBuild = build; downloadUrl = apk }
                }
            } catch (_: Exception) { }
            runOnUiThread {
                val currentBuild = packageManager.getPackageInfo(packageName, 0).versionCode
                if (latestBuild > currentBuild && downloadUrl.isNotBlank()) {
                    AlertDialog.Builder(this).setTitle("AIR Browser update available")
                        .setMessage("AIR Browser Android build $latestBuild is available. Would you like to update now or later?")
                        .setPositiveButton("Update now") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))) }
                        .setNegativeButton("Later", null).show()
                } else if (manual) {
                    Toast.makeText(this, if (latestBuild == 0) "Couldn't check for updates." else "AIR Browser is up to date.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun searchUrl(query: String): String {
        val encoded = try { URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { query }
        return when (prefs.getString("search_engine", "Google")) {
            "Bing" -> "https://www.bing.com/search?q=$encoded"
            "DuckDuckGo" -> "https://duckduckgo.com/?q=$encoded"
            else -> "https://www.google.com/search?q=$encoded"
        }
    }

    private fun showProfilesDialog() {
        val saved = try { JSONArray(basePrefs.getString("profiles", "[\"Personal\"]")) } catch (_: Exception) { JSONArray().put("Personal") }
        val names = mutableListOf<String>()
        for (i in 0 until saved.length()) saved.optString(i).takeIf { it.isNotBlank() }?.let { names.add(it) }
        if ("Personal" !in names) names.add(0, "Personal")
        val items = names.map { if (it == activeProfile) "$it  ✓" else it }.toMutableList()
        items.add("＋ Add profile")
        items.add("⚙ Search engine")
        AlertDialog.Builder(this).setTitle("Profile · $activeProfile")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < names.size -> {
                        activeProfile = names[which]
                        basePrefs.edit().putString("last_profile", activeProfile).apply()
                        recreate()
                    }
                    which == names.size -> {
                        val input = EditText(this).apply { hint = "Profile name"; setSingleLine(true) }
                        AlertDialog.Builder(this).setTitle("Create profile").setView(input)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Create") { _, _ ->
                                val name = input.text.toString().trim().take(28)
                                if (name.isNotBlank() && names.none { it.equals(name, true) }) {
                                    val updated = JSONArray()
                                    (names + name).forEach { updated.put(it) }
                                    basePrefs.edit().putString("profiles", updated.toString()).putString("last_profile", name).apply()
                                    activeProfile = name
                                    recreate()
                                } else Toast.makeText(this, "Choose a new profile name.", Toast.LENGTH_SHORT).show()
                            }.show()
                    }
                    else -> {
                        val engines = arrayOf("Google", "Bing", "DuckDuckGo")
                        val selected = engines.indexOf(prefs.getString("search_engine", "Google")).coerceAtLeast(0)
                        AlertDialog.Builder(this).setTitle("Default search engine")
                            .setSingleChoiceItems(engines, selected) { dialog, choice ->
                                prefs.edit().putString("search_engine", engines[choice]).apply()
                                dialog.dismiss()
                                Toast.makeText(this, "Search engine set to ${engines[choice]}", Toast.LENGTH_SHORT).show()
                            }.setNegativeButton("Cancel", null).show()
                    }
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun addBookmark() {
        val tab = activeTab() ?: return
        if (isHome(tab.url)) {
            Toast.makeText(this, "Open a website first.", Toast.LENGTH_SHORT).show()
            return
        }
        val items = readArray("bookmarks")
        val duplicate = (0 until items.length()).any { items.optJSONObject(it)?.optString("url") == tab.url }
        if (duplicate) {
            Toast.makeText(this, "Already bookmarked.", Toast.LENGTH_SHORT).show()
            return
        }
        items.put(JSONObject().put("title", tab.title).put("url", tab.url))
        prefs.edit().putString("bookmarks", items.toString()).apply()
        Toast.makeText(this, "Bookmark saved.", Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarks() = showSavedList("Bookmarks", "bookmarks", false)
    private fun showHistory() = showSavedList("History", "history", true)

    private fun showSavedList(title: String, key: String, history: Boolean) {
        val items = readArray(key)
        val entries = mutableListOf<JSONObject>()
        for (i in items.length() - 1 downTo 0) {
            items.optJSONObject(i)?.let { entries.add(it) }
        }
        if (entries.isEmpty()) {
            AlertDialog.Builder(this).setTitle(title).setMessage("Nothing here yet.")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = entries.map { it.optString("title", it.optString("url")) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(title).setItems(labels) { _, which ->
            val url = entries[which].optString("url")
            if (url.isNotBlank()) {
                val tab = activeTab() ?: addTab()
                tab.webView.loadUrl(url)
            }
        }.setNegativeButton("Close", null).show()
    }

    private fun addHistory(title: String, url: String) {
        if (url.isBlank() || isHome(url)) return
        val items = readArray("history")
        if (items.length() > 0 && items.optJSONObject(items.length() - 1)?.optString("url") == url) return
        items.put(JSONObject().put("title", title).put("url", url).put("visited", System.currentTimeMillis()))
        while (items.length() > 100) items.remove(0)
        prefs.edit().putString("history", items.toString()).apply()
    }

    private fun clearHistory() {
        AlertDialog.Builder(this).setTitle("Clear browsing history?")
            .setMessage("This removes the history saved by AIR Browser on this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                prefs.edit().remove("history").apply()
                Toast.makeText(this, "History cleared.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: Exception) {
            Toast.makeText(this, "Downloads are managed by Android.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("Downloading with AIR Browser")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "Download started.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Couldn't start this download.", Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePage() {
        val tab = activeTab() ?: return
        if (isHome(tab.url)) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, tab.url)
        }
        startActivity(Intent.createChooser(send, "Share page"))
    }

    private fun readArray(key: String): JSONArray = try {
        JSONArray(prefs.getString(key, "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    private fun saveTabs() {
        if (!::webContainer.isInitialized) return
        val array = JSONArray()
        tabs.forEach { array.put(if (isHome(it.url)) HOME_BASE else it.url) }
        prefs.edit().putString("open_tabs", array.toString()).apply()
    }

    private fun activeTab(): BrowserTab? = tabs.firstOrNull { it.id == activeTabId }

    private fun isHome(url: String?): Boolean =
        url.isNullOrBlank() || url == HOME_BASE || url.startsWith("https://air-browser.local/")

    private fun actionText(label: String, action: (View) -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = if (label == "⋮") 25f else 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(9), 0, dp(9), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener(action)
        }

    private fun bottomAction(symbol: String, label: String, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            addView(TextView(this@MainActivity).apply {
                text = symbol
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(93, 177, 255))
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(194, 207, 229))
            })
            setOnClickListener { action() }
        }

    private fun buttonParams() = LinearLayout.LayoutParams(dp(42), dp(42))
    private fun equalWeight() = LinearLayout.LayoutParams(0, -1, 1f)

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        tabs.forEach { it.webView.onPause() }
        saveTabs()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        tabs.forEach { it.webView.onResume() }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val web = activeTab()?.webView
        if (web?.canGoBack() == true) {
            web.goBack()
        } else if (tabs.size > 1) {
            closeTab(activeTabId)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        tabs.forEach {
            it.webView.stopLoading()
            it.webView.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }
}
