package co.median.android

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.ActionBar
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import co.median.android.icons.Icon
import co.median.median_core.AppConfig
import com.google.android.material.appbar.MaterialToolbar
import org.json.JSONArray
import org.json.JSONObject
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.regex.Pattern


class ActionManager(private val main: MainActivity) {

    private var isRoot = true
    private var actionBar: ActionBar? = null
    private val toolbar: MaterialToolbar = main.findViewById(R.id.toolbar)
    private val titleImageView = main.findViewById<ImageView>(R.id.title_image)
    private val itemToUrl: HashMap<MenuItem, String> = HashMap()
    private val menuItemSize: Int =
        main.resources.getDimensionPixelSize(R.dimen.action_menu_icon_size)
    private val colorForeground = ContextCompat.getColor(main, R.color.titleTextColor)
    private var menu: Menu? = null
    private var searchView: SearchView? = null
    private var currentMenuID: String? = null
    private var leftActionConfigured = false

    init {
        main.setSupportActionBar(toolbar)
    }

    fun setupActionBar(isRoot: Boolean) {
        this.isRoot = isRoot
        this.actionBar = main.supportActionBar ?: return
        val appConfig = AppConfig.getInstance(main)

        actionBar?.apply {
            if (isRoot) {
                if (!appConfig.showNavigationMenu) {
                    setDisplayHomeAsUpEnabled(false)
                }
            } else {
                setDisplayHomeAsUpEnabled(true)
                setDisplayShowHomeEnabled(true)
            }
        }

        toolbar.apply {

            // Menu item on click events
            setOnMenuItemClickListener { menuItem ->
                handleMenuClick(menuItem)
            }

            // Home/Up button
            toolbar.navigationIcon?.colorFilter =
                BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                    colorForeground,
                    BlendModeCompat.SRC_ATOP
                )

            // Overflow menu icon (3 dots icon) color
            toolbar.overflowIcon?.colorFilter =
                BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                    colorForeground,
                    BlendModeCompat.SRC_ATOP
                )
        }
    }

    private fun handleMenuClick(menuItem: MenuItem): Boolean {
        val url = itemToUrl[menuItem]
        url?.let {
            when (it) {
                ACTION_SHARE -> main.sharePage(null, null)
                ACTION_REFRESH -> main.onRefresh()
                ACTION_SEARCH -> {
                    // ignore action as this is handled by the SearchView widget
                }

                else -> {
                    this.main.urlLoader.loadUrl(url, true);
                }
            }
            return true
        } ?: return false
    }

    fun checkActions(url: String?) {
        if (url.isNullOrBlank()) return

        setTitleDisplayForUrl(url)

        val appConfig = AppConfig.getInstance(this.main)

        val regexes = appConfig.actionRegexes
        val ids = appConfig.actionIDs
        if (regexes == null || ids == null) {
            setMenuID(null)
            return
        }

        for (i in regexes.indices) {
            val regex = regexes[i]
            if (regex.matcher(url).matches()) {
                setMenuID(ids[i])
                return
            }
        }
    }

    private fun setMenuID(menuID: String?) {
        val changed = if (this.currentMenuID == null) {
            menuID != null
        } else {
            this.currentMenuID != menuID
        }

        if (changed) {
            this.currentMenuID = menuID
            main.invalidateOptionsMenu()
        }
    }

    fun addActions(menu: Menu) {
        this.menu = menu
        this.itemToUrl.clear()

        val appConfig = AppConfig.getInstance(this.main)
        if (appConfig.actions == null || currentMenuID == null) return

        val actions = appConfig.actions[currentMenuID]
        if (actions == null || actions.length() == 0) {
            return
        }

        if (leftActionConfigured) {
            resetLeftNavigationMenu()
        }

        for (i in 0 until actions.length()) {

            val entry = actions.getJSONObject(i)

            if (i == 0
                && !leftActionConfigured
                && !appConfig.showNavigationMenu
                && addAsLeftActionMenu(entry)
            ) {
                leftActionConfigured = true
                continue
            }

            addMenuItem(menu, i, entry)
        }
    }

    private fun addMenuItem(menu: Menu, itemID: Int, entry: JSONObject?) {
        entry ?: return

        if (addSystemMenuItem(menu, entry, itemID))
            return

        val label = entry.optString("label")
        val icon = entry.optString("icon")
        val url = entry.optString("url")

        val drawableIcon = Icon(main, icon, menuItemSize, colorForeground).getDrawable()

        val menuItem = menu.add(Menu.NONE, itemID, Menu.NONE, label)
            .setIcon(drawableIcon)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)

        itemToUrl[menuItem] = url
    }

    private fun addSystemMenuItem(
        menu: Menu,
        entry: JSONObject,
        itemID: Int
    ): Boolean {
        val system = entry.optString("system")

        if (TextUtils.isEmpty(system))
            return false

        var label = entry.optString("label")
        var icon = entry.optString("icon")
        val url = entry.optString("url")

        val (action, defaultIcon, defaultLabel) = when (system) {
            "refresh" -> Triple(ACTION_REFRESH, "fa-rotate-right", "Refresh")
            "share" -> Triple(ACTION_SHARE, "fa-share", "Share")
            "search" -> Triple(ACTION_SEARCH, "fa fa-search", "Search")
            else -> return false
        }

        if (TextUtils.isEmpty(label)) label = defaultLabel
        if (TextUtils.isEmpty(icon)) icon = defaultIcon

        val drawableIcon = Icon(main, icon, menuItemSize, colorForeground).getDrawable()

        val menuItem = menu.add(Menu.NONE, itemID, Menu.NONE, label)
            .setIcon(drawableIcon)
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)

        if (action == ACTION_SEARCH) {
            createSearchView(menuItem, url, drawableIcon)
        }

        itemToUrl[menuItem] = action
        return true
    }

    private fun addAsLeftActionMenu(entry: JSONObject?): Boolean {
        entry ?: return false

        if (!isRoot)
        // Consume the event but keep the menu hidden on non-root windows for the native back button.
            return true

        // Show navigation button
        var url = entry.optString("url")
        var icon = entry.optString("icon")
        val system = entry.optString("system")

        if (!system.isNullOrBlank()) {
            when (system) {
                "refresh" -> {
                    url = ACTION_REFRESH
                    if (!icon.isNullOrBlank()) icon = "fa-rotate-right"
                }

                "share" -> {
                    url = ACTION_SHARE
                    if (!icon.isNullOrBlank()) icon = "fa fa-search"
                }

                "search" -> {
                    // The "Search" system menu is not supported as a Navigation menu yet.
                    return false
                }
            }
        }

        val drawableIcon = Icon(main, icon, menuItemSize, colorForeground).getDrawable()
        toolbar.navigationIcon = drawableIcon
        toolbar.setNavigationIconTint(colorForeground)

        toolbar.setNavigationOnClickListener {
            when (url) {
                ACTION_SHARE -> main.sharePage(null, null)
                ACTION_REFRESH -> main.onRefresh()
                else -> this.main.urlLoader.loadUrl(url, true)
            }
        }
        return true
    }

    private fun resetLeftNavigationMenu() {
        toolbar.apply {
            navigationIcon = null
            setNavigationOnClickListener(null)
        }
        leftActionConfigured = false
    }

    @SuppressLint("RestrictedApi")
    private fun createSearchView(menuItem: MenuItem, url: String, icon: Drawable?) {
        this.searchView = SearchView(main).apply {
            layoutParams = Toolbar.LayoutParams(
                Toolbar.LayoutParams.MATCH_PARENT,
                Toolbar.LayoutParams.WRAP_CONTENT
            )
            maxWidth = Int.MAX_VALUE

            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    try {
                        val q = URLEncoder.encode(query, "UTF-8")
                        main.loadUrl(url + q)
                    } catch (e: UnsupportedEncodingException) {
                        return true
                    }

                    return true
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    // do nothing
                    return true
                }
            })

            setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus && !isIconified) {
                    closeSearchView()
                }
            }

            // edit text color
            val editText =
                findViewById<SearchView.SearchAutoComplete>(androidx.appcompat.R.id.search_src_text)

            editText?.apply {
                setTextColor(colorForeground)
                var hintColor = colorForeground
                hintColor = Color.argb(
                    192, Color.red(hintColor), Color.green(hintColor),
                    Color.blue(hintColor)
                )
                setHintTextColor(hintColor)
            }

            // close button color
            val closeButton: ImageView? = findViewById(androidx.appcompat.R.id.search_close_btn)
            closeButton?.setColorFilter(colorForeground)
        }

        menuItem.apply {
            actionView = searchView
            setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)

            setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                    // hide other menus
                    setMenuItemsVisible(false, menuItem)
                    return true
                }

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    // re-show other menus
                    closeSearchView()
                    return true
                }
            })
        }
    }

    fun setMenuItemsVisible(visible: Boolean, exception: MenuItem) {
        if (menu == null) return

        menu?.let {
            for (i in 0 until it.size()) {
                val item: MenuItem = it.getItem(i)
                if (item === exception) {
                    continue
                }

                item.setVisible(visible)
                item.setEnabled(visible)
            }
        }

    }

    private fun closeSearchView() {
        searchView?.isIconified = true
        main.invalidateOptionsMenu()
    }

    fun canCloseSearchView(): Boolean {
        searchView?.let {
            if (it.hasFocus()) {
                closeSearchView()
                return true
            }
        }
        return false
    }

    fun setTitleDisplayForUrl(url: String?, allowPageTitle: Boolean = true) {
        if (actionBar == null || url.isNullOrBlank()) return

        val appConfig = AppConfig.getInstance(main)

        var urlHasNavTitle = false
        var urlHasActionMenu = false

        // Check for Nav title
        val urlNavTitle: HashMap<String, Any>? = appConfig.getNavigationTitleForUrl(url)

        if (urlNavTitle != null) {
            urlHasNavTitle = true
        }

        // Check for Action Menus
        val regexes: ArrayList<Pattern>? = appConfig.actionRegexes
        val ids: ArrayList<String>? = appConfig.actionIDs
        if (regexes != null && ids != null) {
            for (i in regexes.indices) {
                val regex = regexes[i]
                if (regex.matcher(url).matches()) {
                    val items: JSONArray? = appConfig.actions[ids[i]]
                    if (items != null && items.length() > 0) {
                        urlHasActionMenu = true
                    }
                    break
                }
            }
        }

        if (!appConfig.showActionBar && !appConfig.showNavigationMenu && !urlHasNavTitle && !urlHasActionMenu) {
            actionBar?.hide()
            return
        }

        // default title
        var title: String = if (main.webView.title != null) main.webView.title else main.getString(R.string.app_name)

        if (!urlHasNavTitle) {
            showImageOrTextTitle(appConfig.shouldShowNavigationTitleImageForUrl(url), title)
        } else {

            val urlNavTitleString = (urlNavTitle?.get("title") as? String).orEmpty()

            if (urlNavTitleString.isEmpty() && !allowPageTitle)
                // If config title is empty and allowPageTitle is false,
                // ignore using the page title as it may not be the actual title
                // due to the method being called before the URL is loaded.
                return

            title = urlNavTitleString.ifEmpty { title }

            main.setTitle(title)

            val showImage = urlNavTitle?.get("showImage") as? Boolean ?: false
            showImageOrTextTitle(showImage, title)
        }
        actionBar?.show()
    }

    fun setTitle(title: CharSequence) {
        if (title.isBlank()) return
        titleImageView.visibility = View.GONE
        toolbar.title = title
    }

    private fun showImageOrTextTitle(show: Boolean, title: String) {
        if (show) {
            titleImageView.visibility = View.VISIBLE
            toolbar.title = ""
        } else {
            titleImageView.visibility = View.GONE
            toolbar.title = title
        }
    }

    companion object {
        const val ACTION_SHARE: String = "share"
        const val ACTION_REFRESH: String = "refresh"
        const val ACTION_SEARCH: String = "search"
    }
}