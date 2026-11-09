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
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.util.Base64
import java.nio.charset.StandardCharsets

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
        private const val AIR_SUPABASE_URL = "https://rkwpxzotvneoavrczkzo.supabase.co"
        private const val AIR_SUPABASE_KEY = "sb_publishable_XZ71KE8FXhRoPdC1K7tEJw_tN4wReB_"
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
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
    private lateinit var tabStrip: LinearLayout
    private lateinit var backButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var reloadButton: TextView
    private lateinit var profileButton: TextView
    private lateinit var inAppPanel: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeProfile = basePrefs.getString("last_profile", "Personal") ?: "Personal"
        migrateLegacyPreferences()
        window.statusBarColor = Color.rgb(11, 16, 32)
        window.navigationBarColor = Color.rgb(11, 16, 32)
        window.decorView.systemUiVisibility = 0
        CookieManager.getInstance().setAcceptCookie(true)
        buildInterface()
        applyBrowserTheme()
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
        profileButton = actionText("● ${activeProfile.take(6)}") { showProfilesScreen() }.apply {
            textSize = 11f
            setTextColor(Color.rgb(151, 204, 255))
            setPadding(dp(5), 0, dp(5), 0)
            minWidth = dp(64)
            contentDescription = "Profiles: $activeProfile"
            background = rounded(Color.rgb(28, 39, 61), dp(12))
            isClickable = true
            isFocusable = true
        }
        header.addView(profileButton, LinearLayout.LayoutParams(dp(70), dp(42)))
        profileButton.setOnClickListener { showProfilesScreen() }
        header.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        tabCount = actionText("▢ 1") { showTabsDialog() }
        header.addView(tabCount, buttonParams())
        header.addView(actionText("⚙") { showSettingsScreen() }, buttonParams())
        header.addView(actionText("⋮") { showMainMenu(it) }, buttonParams())
        root.addView(header, LinearLayout.LayoutParams(-1, dp(46)))

        val tabScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        tabStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tabScroll.addView(tabStrip, LinearLayout.LayoutParams(-2, dp(40)))
        root.addView(tabScroll, LinearLayout.LayoutParams(-1, dp(42)))

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
        addressRow.addView(actionText("☆") { addBookmark() }, buttonParams())
        addressRow.addView(actionText("Aa") { toggleReadingMode() }, buttonParams())
        addressRow.addView(actionText("Go") { navigateFromAddress() }, buttonParams())
        root.addView(addressRow, LinearLayout.LayoutParams(-1, dp(52)))

        inAppPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(Color.rgb(15, 23, 40)) }
        root.addView(inAppPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        webContainer = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
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
        if (!prefs.getBoolean("restore_tabs", true)) { addTab(HOME_BASE); return }
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
        webView.settings.javaScriptEnabled = prefs.getBoolean("javascript", true)
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = prefs.getBoolean("load_images", true)
        webView.settings.textZoom = prefs.getInt("page_zoom", 100)
        webView.settings.userAgentString = if (prefs.getBoolean("desktop_site", false)) DESKTOP_USER_AGENT else null
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
                    refreshTabStrip()
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
        refreshTabStrip()
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
        refreshTabStrip()
    }

    private fun refreshTabStrip() {
        if (!::tabStrip.isInitialized) return
        tabStrip.removeAllViews()
        tabs.forEachIndexed { index, tab ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (tab.id == activeTabId) Color.rgb(37, 76, 121) else Color.rgb(25, 40, 62), dp(9))
                setPadding(dp(9), 0, dp(4), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { switchToTab(tab.id) }
            }
            chip.addView(TextView(this).apply {
                text = index.toString() + ". " + tab.title.ifBlank { "New tab" }
                textSize = 11f
                setTextColor(Color.WHITE)
                maxWidth = dp(125)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(-2, dp(32)))
            chip.addView(TextView(this).apply {
                text = "×"; textSize = 16f; setTextColor(Color.rgb(190, 205, 225)); gravity = Gravity.CENTER
                setPadding(dp(7), 0, dp(4), 0)
                setOnClickListener { closeTab(tab.id) }
            }, LinearLayout.LayoutParams(-2, dp(32)))
            val lp = LinearLayout.LayoutParams(-2, dp(32)).apply { rightMargin = dp(6) }
            tabStrip.addView(chip, lp)
        }
        val plus = TextView(this).apply {
            text = "+"; textSize = 21f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = rounded(Color.rgb(28, 39, 61), dp(9)); setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { addTab() }
        }
        tabStrip.addView(plus, LinearLayout.LayoutParams(-2, dp(32)))
    }

    private fun showTabsDialog() {
        showInAppPanel("Open tabs") {
            addView(panelButton("＋  New tab") { hideInAppPanel(); addTab() }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
            tabs.toList().forEachIndexed { index, tab ->
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(4), dp(4)); background = rounded(Color.rgb(28, 39, 61), dp(12)) }
                row.addView(TextView(this@MainActivity).apply {
                    text = (if (tab.id == activeTabId) "✓  " else "") + "${index + 1}. " + tab.title.ifBlank { "New tab" }
                    textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL; maxLines = 2
                    setOnClickListener { hideInAppPanel(); switchToTab(tab.id) }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                row.addView(panelButton("×") { closeTab(tab.id); showTabsDialog() }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(row, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(8) })
            }
        }
    }

    private fun showInAppPanel(title: String, content: LinearLayout.() -> Unit) {
        webContainer.visibility = View.GONE
        inAppPanel.visibility = View.VISIBLE
        inAppPanel.removeAllViews()
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(this@MainActivity).apply { text = title; textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        heading.addView(panelButton("Done") { hideInAppPanel() })
        inAppPanel.addView(heading)
        val scroll = android.widget.ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(12)) }
        scroll.addView(body)
        inAppPanel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.content()
    }

    private fun hideInAppPanel() { inAppPanel.visibility = View.GONE; webContainer.visibility = View.VISIBLE }

    private fun panelButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setPadding(dp(12), dp(8), dp(12), dp(8)); background = rounded(Color.rgb(39, 137, 232), dp(12))
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }

    private fun panelLabel(label: String) = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(Color.rgb(158, 172, 197)); setPadding(dp(4), dp(14), dp(4), dp(6))
    }

    private fun showProfilesScreen() {
        showInAppPanel("Profiles") {
            val saved = try { JSONArray(basePrefs.getString("profiles", "[\"Personal\"]")) } catch (_: Exception) { JSONArray().put("Personal") }
            val names = mutableListOf<String>()
            for (i in 0 until saved.length()) saved.optString(i).takeIf { it.isNotBlank() }?.let { names.add(it) }
            if ("Personal" !in names) names.add(0, "Personal")
            names.forEach { name ->
                addView(panelButton((if (name == activeProfile) "✓  " else "") + name) {
                    if (name != activeProfile) { saveTabs(); activeProfile = name; basePrefs.edit().putString("last_profile", name).apply(); recreate() }
                }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            }
            addView(panelButton("＋ Add profile") {
                val input = EditText(this@MainActivity).apply { hint = "Profile name"; setSingleLine(true) }
                AlertDialog.Builder(this@MainActivity).setTitle("Create profile").setView(input)
                    .setNegativeButton("Cancel", null).setPositiveButton("Create") { _, _ ->
                        val name = input.text.toString().trim().take(28)
                        if (name.isNotBlank() && names.none { it.equals(name, true) }) {
                            val updated = JSONArray(); (names + name).forEach { updated.put(it) }
                            saveTabs(); basePrefs.edit().putString("profiles", updated.toString()).putString("last_profile", name).apply()
                            activeProfile = name; recreate()
                        } else Toast.makeText(this@MainActivity, "Choose a new profile name.", Toast.LENGTH_SHORT).show()
                    }.show()
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
    }

    private fun applyBrowserTheme() {
        val theme = prefs.getString("theme", "dark") ?: "dark"
        val palette = when (theme) {
            "midnight" -> intArrayOf(Color.rgb(9, 13, 29), Color.rgb(17, 26, 49), Color.rgb(75, 86, 200))
            "ocean" -> intArrayOf(Color.rgb(9, 43, 59), Color.rgb(16, 65, 87), Color.rgb(8, 127, 158))
            "purple" -> intArrayOf(Color.rgb(37, 22, 56), Color.rgb(53, 32, 71), Color.rgb(134, 80, 199))
            "forest" -> intArrayOf(Color.rgb(18, 43, 34), Color.rgb(29, 59, 46), Color.rgb(38, 115, 75))
            "sunset" -> intArrayOf(Color.rgb(58, 33, 30), Color.rgb(81, 45, 41), Color.rgb(198, 93, 54))
            "rose" -> intArrayOf(Color.rgb(56, 32, 45), Color.rgb(75, 42, 61), Color.rgb(182, 79, 131))
            "light" -> intArrayOf(Color.rgb(220, 232, 246), Color.rgb(247, 250, 255), Color.rgb(23, 104, 214))
            else -> intArrayOf(Color.rgb(11, 16, 32), Color.rgb(28, 39, 61), Color.rgb(39, 137, 232))
        }
        val light = theme == "light"
        val foreground = if (light) Color.rgb(20, 36, 58) else Color.WHITE
        window.statusBarColor = palette[0]
        window.navigationBarColor = palette[0]
        root.setBackgroundColor(palette[0])
        if (::addressBar.isInitialized) {
            addressBar.setTextColor(foreground)
            addressBar.setHintTextColor(if (light) Color.rgb(83, 108, 138) else Color.rgb(160, 173, 195))
            addressBar.background = rounded(palette[1], dp(16))
        }
        if (::inAppPanel.isInitialized) inAppPanel.setBackgroundColor(palette[0])
        if (::profileButton.isInitialized) {
            profileButton.setTextColor(if (light) Color.rgb(30, 82, 135) else Color.rgb(151, 204, 255))
            profileButton.background = rounded(palette[1], dp(12))
        }
    }

    private fun showSettingsScreen() {
        showInAppPanel("Settings") {
            addView(panelLabel("GENERAL"))
            addView(panelButton("Colour theme: " + (prefs.getString("theme", "dark") ?: "dark").replaceFirstChar { it.uppercase() }) {
                val themes = arrayOf("Dark", "Midnight", "Ocean", "Purple", "Forest", "Sunset", "Rose", "Light")
                AlertDialog.Builder(this@MainActivity).setTitle("Colour theme")
                    .setSingleChoiceItems(themes, themes.indexOf((prefs.getString("theme", "dark") ?: "dark").replaceFirstChar { it.uppercase() }).coerceAtLeast(0)) { d, w ->
                        prefs.edit().putString("theme", themes[w].lowercase()).apply(); d.dismiss(); applyBrowserTheme(); showSettingsScreen()
                    }.setNegativeButton("Cancel", null).show()
            }, settingParams())
            addView(panelButton("Default search engine: " + (prefs.getString("search_engine", "Google") ?: "Google")) {
                val engines = arrayOf("Google", "Bing", "DuckDuckGo")
                AlertDialog.Builder(this@MainActivity).setTitle("Default search engine")
                    .setSingleChoiceItems(engines, engines.indexOf(prefs.getString("search_engine", "Google")).coerceAtLeast(0)) { d, w ->
                        prefs.edit().putString("search_engine", engines[w]).apply(); d.dismiss(); showSettingsScreen()
                    }.setNegativeButton("Cancel", null).show()
            }, settingParams())
            addView(panelButton("Manage profiles") { showProfilesScreen() }, settingParams())
            addView(panelLabel("BROWSING"))
            addView(panelButton("JavaScript: " + if (prefs.getBoolean("javascript", true)) "On" else "Off") {
                prefs.edit().putBoolean("javascript", !prefs.getBoolean("javascript", true)).apply()
                tabs.forEach { it.webView.settings.javaScriptEnabled = prefs.getBoolean("javascript", true) }; showSettingsScreen()
            }, settingParams())
            addView(panelButton("Load images: " + if (prefs.getBoolean("load_images", true)) "On" else "Off") {
                prefs.edit().putBoolean("load_images", !prefs.getBoolean("load_images", true)).apply()
                tabs.forEach { it.webView.settings.loadsImagesAutomatically = prefs.getBoolean("load_images", true) }; showSettingsScreen()
            }, settingParams())
            addView(panelButton("Desktop site: " + if (prefs.getBoolean("desktop_site", false)) "On" else "Off") {
                prefs.edit().putBoolean("desktop_site", !prefs.getBoolean("desktop_site", false)).apply()
                tabs.forEach { it.webView.settings.userAgentString = if (prefs.getBoolean("desktop_site", false)) DESKTOP_USER_AGENT else null; it.webView.reload() }; showSettingsScreen()
            }, settingParams())
            addView(panelButton("Page zoom: " + prefs.getInt("page_zoom", 100) + "%") {
                val nums = intArrayOf(75, 90, 100, 110, 125, 150)
                AlertDialog.Builder(this@MainActivity).setTitle("Page zoom")
                    .setSingleChoiceItems(nums.map { "$it%" }.toTypedArray(), nums.indexOf(prefs.getInt("page_zoom", 100)).coerceAtLeast(0)) { d, w ->
                        prefs.edit().putInt("page_zoom", nums[w]).apply(); tabs.forEach { it.webView.settings.textZoom = nums[w] }; d.dismiss(); showSettingsScreen()
                    }.setNegativeButton("Cancel", null).show()
            }, settingParams())
            addView(panelLabel("STARTUP"))
            addView(panelButton("Restore open tabs: " + if (prefs.getBoolean("restore_tabs", true)) "On" else "Off") {
                prefs.edit().putBoolean("restore_tabs", !prefs.getBoolean("restore_tabs", true)).apply(); showSettingsScreen()
            }, settingParams())
            addView(panelLabel("AIR ACCOUNT & SYNC"))
            addView(panelButton(if (storedAirSession() == null) "Sign in / Create AIR Account" else "AIR Account: " + (storedAirSession()?.optJSONObject("user")?.optString("email") ?: "Signed in")) { showAirAccountScreen() }, settingParams())
            addView(panelButton("Back up or restore with AIR Sync") { showAirSyncPrompt() }, settingParams())
            addView(panelLabel("UPDATES"))
            addView(panelButton("Automatic updates: " + if (prefs.getBoolean("automatic_updates", true)) "On" else "Off") {
                prefs.edit().putBoolean("automatic_updates", !prefs.getBoolean("automatic_updates", true)).apply(); showSettingsScreen()
            }, settingParams())
            addView(panelButton("Check for updates") { checkForUpdates(true) }, settingParams())
            addView(panelLabel("PRIVACY & DATA"))
            addView(panelButton("Clear browsing history") { clearHistory() }, settingParams())
            addView(panelButton("Clear cookies and website data") { clearWebsiteData() }, settingParams())
            addView(panelButton("Clear cache") { tabs.forEach { it.webView.clearCache(true) }; Toast.makeText(this@MainActivity, "Cache cleared.", Toast.LENGTH_SHORT).show() }, settingParams())
            addView(panelLabel("ABOUT"))
            addView(TextView(this@MainActivity).apply { text = "AIR Browser for Android\nAIR Sync encrypts your backup before upload. Passwords, cookies and active sessions are not included."; textSize = 13f; setTextColor(Color.rgb(158, 172, 197)); setPadding(dp(4), dp(8), dp(4), dp(8)) })
        }
    }
    private fun settingParams() = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) }
    private fun clearWebsiteData() {
        AlertDialog.Builder(this).setTitle("Clear website data?").setMessage("This clears cookies and cached site data. You may be signed out of websites.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                CookieManager.getInstance().removeAllCookies { }; CookieManager.getInstance().flush()
                tabs.forEach { it.webView.clearCache(true); it.webView.clearFormData() }
                Toast.makeText(this, "Website data cleared.", Toast.LENGTH_SHORT).show()
            }.show()
    }
    private fun storedAirSession(): JSONObject? = try { prefs.getString("air_auth_session", null)?.let { JSONObject(it) } } catch (_: Exception) { null }
    private fun showAirAccountScreen() {
        showInAppPanel("AIR Account") {
            val session = storedAirSession()
            if (session != null) {
                addView(TextView(this@MainActivity).apply { text = "Signed in as " + (session.optJSONObject("user")?.optString("email") ?: "AIR Account"); setTextColor(Color.WHITE); textSize = 15f; setPadding(0, dp(10), 0, dp(14)) })
                addView(panelButton("Sign out") { prefs.edit().remove("air_auth_session").apply(); showAirAccountScreen() }, settingParams())
            } else {
                val email = EditText(this@MainActivity).apply { hint = "Email address"; setSingleLine(true) }
                val password = EditText(this@MainActivity).apply { hint = "Password"; inputType = 129; setSingleLine(true) }
                addView(panelLabel("EMAIL")); addView(email, LinearLayout.LayoutParams(-1, dp(50)))
                addView(panelLabel("PASSWORD")); addView(password, LinearLayout.LayoutParams(-1, dp(50)))
                addView(panelButton("Sign in") { performAirAuth(email.text.toString().trim(), password.text.toString(), false) }, settingParams())
                addView(panelButton("Create AIR Account") { performAirAuth(email.text.toString().trim(), password.text.toString(), true) }, settingParams())
                addView(TextView(this@MainActivity).apply { text = "Use your existing desktop AIR Account. Passwords must be at least 8 characters."; setTextColor(Color.LTGRAY); textSize = 12f })
            }
        }
    }
    private fun performAirAuth(email: String, password: String, signup: Boolean) {
        if (!email.contains("@") || password.length < 8) { Toast.makeText(this, "Enter a valid email and a password of at least 8 characters.", Toast.LENGTH_LONG).show(); return }
        Thread {
            try {
                val endpoint = if (signup) "/auth/v1/signup" else "/auth/v1/token?grant_type=password"
                val result = airRequest("POST", endpoint, JSONObject().put("email", email).put("password", password), null)
                if (result.has("access_token")) { prefs.edit().putString("air_auth_session", result.toString()).apply(); runOnUiThread { showAirAccountScreen(); Toast.makeText(this, "Signed in to AIR Account.", Toast.LENGTH_SHORT).show() } }
                else if (signup) runOnUiThread { Toast.makeText(this, "Account created. Check your email if confirmation is required, then sign in.", Toast.LENGTH_LONG).show() }
                else throw Exception("No session was returned.")
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "AIR Account: " + (e.message ?: "Sign-in failed"), Toast.LENGTH_LONG).show() } }
        }.start()
    }
    private fun showAirSyncPrompt() {
        if (storedAirSession() == null) { showAirAccountScreen(); Toast.makeText(this, "Sign in first.", Toast.LENGTH_SHORT).show(); return }
        val pass = EditText(this).apply { hint = "Sync passphrase (10+ characters)"; inputType = 129 }
        AlertDialog.Builder(this).setTitle("AIR Sync").setMessage("Use the same passphrase on desktop and mobile. Data is encrypted before upload. Passwords, cookies and active sessions are excluded.")
            .setView(pass).setItems(arrayOf("Back up this device", "Restore from cloud")) { _, which ->
                val phrase = pass.text.toString()
                if (phrase.length < 10) Toast.makeText(this, "Use at least 10 characters.", Toast.LENGTH_LONG).show() else runAirSync(phrase, which == 0)
            }.setNegativeButton("Cancel", null).show()
    }
    private fun runAirSync(passphrase: String, backup: Boolean) {
        Thread {
            try {
                val session = refreshAirSession(); val profile = activeProfile
                if (backup) {
                    val encrypted = encryptSyncPayload(buildSyncPayload(profile).toString(), passphrase)
                    val row = JSONObject().put("user_id", session.getJSONObject("user").getString("id")).put("profile_key", profile).put("profile_name", profile).put("encrypted_payload", encrypted.toString()).put("encryption_version", 1).put("updated_at", java.time.Instant.now().toString())
                    airRequest("POST", "/rest/v1/air_sync_profiles?on_conflict=user_id%2Cprofile_key", row, session.getString("access_token"), "resolution=merge-duplicates,return=minimal")
                    runOnUiThread { Toast.makeText(this, "AIR Sync backup complete for $profile.", Toast.LENGTH_LONG).show() }
                } else {
                    val rows = airRequestArray("/rest/v1/air_sync_profiles?select=profile_name,encrypted_payload&profile_key=eq." + URLEncoder.encode(profile, "UTF-8") + "&limit=1", session.getString("access_token"))
                    if (rows.length() == 0) throw Exception("No cloud backup exists for this profile yet.")
                    val packed = JSONObject(rows.getJSONObject(0).optString("encrypted_payload"))
                    val data = JSONObject(decryptSyncPayload(packed, passphrase))
                    if (data.optString("profile") != profile) throw Exception("Backup belongs to another profile.")
                    runOnUiThread { AlertDialog.Builder(this).setTitle("Restore AIR Sync?").setMessage("This replaces bookmarks, history, settings and open tabs for $profile.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Restore") { _, _ ->
                            try { applySyncPayload(data); Toast.makeText(this, "AIR Sync restore complete.", Toast.LENGTH_LONG).show() }
                            catch (e: Exception) { Toast.makeText(this, "Restore failed: " + e.message, Toast.LENGTH_LONG).show() }
                        }.show() }
                }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "AIR Sync failed: " + (e.message ?: "Unknown error"), Toast.LENGTH_LONG).show() } }
        }.start()
    }
    private fun buildSyncPayload(profile: String): JSONObject {
        val bookmarks = JSONArray(); val savedBookmarks = readArray("bookmarks")
        for (i in 0 until savedBookmarks.length()) bookmarks.put(savedBookmarks.optJSONObject(i)?.optString("url") ?: savedBookmarks.optString(i))
        val history = JSONArray(); val savedHistory = readArray("history")
        for (i in 0 until savedHistory.length()) history.put(savedHistory.optJSONObject(i)?.optString("url") ?: savedHistory.optString(i))
        val settings = JSONObject()
        prefs.all.forEach { (k, v) -> if (k in setOf("theme","search_engine","restore_tabs","automatic_updates","javascript","load_images","desktop_site","page_zoom","homepage")) when(v) {
            is Boolean -> settings.put(k,v); is Int -> settings.put(k,v); is String -> settings.put(k,v)
        } }
        val openTabs = JSONArray(); tabs.filter { it.url.startsWith("http://") || it.url.startsWith("https://") }.forEach { openTabs.put(it.url) }
        return JSONObject().put("profile",profile).put("bookmarks",bookmarks).put("history",history).put("settings",settings).put("startBackground","").put("openTabs",openTabs)
    }
    private fun applySyncPayload(data: JSONObject) {
        fun toEntries(key: String): JSONArray {
            val out = JSONArray(); val source = data.optJSONArray(key) ?: JSONArray()
            for (i in 0 until source.length()) { val url = source.optString(i); if (url.startsWith("https://") || url.startsWith("http://")) out.put(JSONObject().put("title", Uri.parse(url).host ?: url).put("url",url)) }
            return out
        }
        prefs.edit().putString("bookmarks",toEntries("bookmarks").toString()).putString("history",toEntries("history").toString()).apply()
        val settings = data.optJSONObject("settings")
        if (settings != null) { val e = prefs.edit(); settings.keys().forEach { k -> when(val v=settings.opt(k)) { is Boolean -> e.putBoolean(k,v); is Int -> e.putInt(k,v); is String -> e.putString(k,v) } }; e.apply(); applyBrowserTheme() }
        val urls = data.optJSONArray("openTabs") ?: JSONArray()
        tabs.toList().forEach { closeTab(it.id) }
        if (urls.length()==0) addTab(HOME_BASE) else for (i in 0 until minOf(urls.length(),MAX_RESTORED_TABS)) addTab(urls.optString(i),switchTo=i==minOf(urls.length(),MAX_RESTORED_TABS)-1)
    }
    private fun encryptSyncPayload(plain: String, passphrase: String): JSONObject {
        val salt=ByteArray(16).also { SecureRandom().nextBytes(it) }; val iv=ByteArray(12).also { SecureRandom().nextBytes(it) }
        val spec=PBEKeySpec(passphrase.toCharArray(),salt,250000,256); val key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded; spec.clearPassword()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv))
        return JSONObject().put("version",1).put("salt",Base64.getEncoder().encodeToString(salt)).put("iv",Base64.getEncoder().encodeToString(iv)).put("ciphertext",Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))))
    }
    private fun decryptSyncPayload(p: JSONObject, passphrase: String): String {
        if(p.optInt("version")!=1) throw Exception("Unsupported backup version.")
        val spec=PBEKeySpec(passphrase.toCharArray(),Base64.getDecoder().decode(p.getString("salt")),250000,256); val key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded; spec.clearPassword()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,Base64.getDecoder().decode(p.getString("iv"))))
        return String(cipher.doFinal(Base64.getDecoder().decode(p.getString("ciphertext"))),StandardCharsets.UTF_8)
    }
    private fun refreshAirSession(): JSONObject {
        var session=storedAirSession() ?: throw Exception("Sign in to AIR Account first.")
        val refresh=session.optString("refresh_token")
        if(refresh.isNotBlank()){ session=airRequest("POST","/auth/v1/token?grant_type=refresh_token",JSONObject().put("refresh_token",refresh),null); prefs.edit().putString("air_auth_session",session.toString()).apply() }
        return session
    }
    private fun airRequest(method: String, endpoint: String, body: JSONObject?, token: String?, prefer: String? = null): JSONObject {
        val c=(URL(AIR_SUPABASE_URL+endpoint).openConnection() as HttpURLConnection)
        c.requestMethod=method; c.connectTimeout=20000; c.readTimeout=20000; c.setRequestProperty("apikey",AIR_SUPABASE_KEY); c.setRequestProperty("Content-Type","application/json")
        if(token!=null)c.setRequestProperty("Authorization","Bearer $token"); if(prefer!=null)c.setRequestProperty("Prefer",prefer)
        if(body!=null){c.doOutput=true;c.outputStream.use{it.write(body.toString().toByteArray(StandardCharsets.UTF_8))}}
        val status=c.responseCode; val stream=if(status in 200..299)c.inputStream else c.errorStream; val raw=stream?.bufferedReader()?.use{it.readText()} ?: ""
        if(status !in 200..299)throw Exception("Server $status: "+raw.take(180))
        return if(raw.isBlank()) JSONObject() else JSONObject(raw)
    }
    private fun airRequestArray(endpoint: String, token: String): JSONArray {
        val c=(URL(AIR_SUPABASE_URL+endpoint).openConnection() as HttpURLConnection)
        c.requestMethod="GET"; c.connectTimeout=20000; c.readTimeout=20000; c.setRequestProperty("apikey",AIR_SUPABASE_KEY); c.setRequestProperty("Authorization","Bearer $token")
        val status=c.responseCode; val stream=if(status in 200..299)c.inputStream else c.errorStream; val raw=stream?.bufferedReader()?.use{it.readText()} ?: ""
        if(status !in 200..299)throw Exception("Server $status: "+raw.take(180))
        return JSONArray(raw)
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
        popup.menu.add(0, 13, 12, "Profiles")
        popup.menu.add(0, 14, 13, "Settings")
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
                13 -> showProfilesScreen()
                14 -> showSettingsScreen()
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
                        saveTabs()
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
                                    saveTabs()
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