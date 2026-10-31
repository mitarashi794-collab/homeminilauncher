package com.example.minilauncher

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.Collator
import kotlin.math.abs

class MainActivity : Activity() {

    private data class AppItem(
        val label: String,
        val packageName: String,
        val activityName: String,
        val group: Int, // 0..25 = A..Z, 26 = それ以外(#)
    ) {
        val key: String get() = "$packageName/$activityName"
    }

    private companion object {
        const val WIDGET_HOST_ID = 1024
        const val REQ_BIND = 1
        const val REQ_CONFIGURE = 2
        const val SLOT_RIGHT = "swipeRight"
        const val PREF_BROWSER = "browserPkg"
        const val PREF_TEXT = "textSize"
        val TEXT_SCALES = floatArrayOf(0.85f, 1.0f, 1.2f, 1.4f)
        val TEXT_LABELS = arrayOf("小", "標準", "大", "特大")
    }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private var apps: List<AppItem> = emptyList()
    private var shown: List<AppItem> = emptyList() // 検索で絞り込んだ後の表示リスト
    private val favorites = mutableListOf<String>()
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }

    private val textIndex: Int get() = prefs.getInt(PREF_TEXT, 1).coerceIn(0, TEXT_SCALES.size - 1)
    private val textScale: Float get() = TEXT_SCALES[textIndex]

    private lateinit var pager: TwoPagePager
    private lateinit var favScroll: ScrollView
    private lateinit var favContainer: LinearLayout
    private lateinit var widgetBox: LinearLayout
    private lateinit var widgetManager: AppWidgetManager
    private lateinit var widgetHost: AppWidgetHost
    private val widgetIds = mutableListOf<Int>()
    private var pendingWidgetId = -1
    private lateinit var listView: ListView
    private lateinit var searchBox: EditText
    private lateinit var slider: AlphabetSlider
    private lateinit var bubble: TextView
    private val adapter = AppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(this, WIDGET_HOST_ID)
        widgetIds.addAll(
            (prefs.getString("widgets", "") ?: "").split(",").mapNotNull { it.toIntOrNull() },
        )

        favorites.addAll(
            (prefs.getString("favorites", "") ?: "").split("\n").filter { it.isNotEmpty() },
        )

        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            // 壁紙の上に薄い暗幕を重ねて文字を読みやすくする
            setBackgroundColor(Color.argb(140, 0, 0, 0))
        }

        // ---- 1ページ目: お気に入りホーム（下寄せ） ----
        favContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(28), dp(24), dp(28), dp(56))
        }
        favScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                favContainer,
                FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        // ホームページ = 上にウィジェット、下にお気に入り。長押しでウィジェットの追加/削除
        widgetBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnLongClickListener {
                showWidgetMenu()
                true
            }
        }
        favContainer.setOnLongClickListener {
            showWidgetMenu()
            true
        }
        val homePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                widgetBox,
                LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(favScroll, LinearLayout.LayoutParams(match, 0, 1f))
        }

        // ---- 2ページ目: アプリ一覧 + アルファベットスライダー ----
        val appsPage = FrameLayout(this)

        listView = ListView(this).apply {
            divider = null
            setSelector(android.R.color.transparent)
            isVerticalScrollBarEnabled = false
            setPadding(dp(24), dp(8), dp(48), dp(8))
            clipToPadding = false
            adapter = this@MainActivity.adapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                if (position < shown.size) launch(shown[position]) else webSearch(query())
            }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
                if (position < shown.size) showListMenu(shown[position])
                true
            }
        }

        searchBox = EditText(this).apply {
            hint = "アプリを検索"
            setHintTextColor(Color.argb(140, 255, 255, 255))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * textScale)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            setBackgroundColor(Color.argb(40, 255, 255, 255))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    applyFilter()
                    listView.setSelection(0)
                }
            })
            // キーボードの実行ボタン: 候補があれば先頭のアプリを起動、なければWeb検索
            setOnEditorActionListener { _, _, _ ->
                val first = shown.firstOrNull()
                if (first != null) launch(first) else if (query().isNotEmpty()) webSearch(query())
                true
            }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                searchBox,
                LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(dp(24), dp(8), dp(48), dp(0)) },
            )
            addView(listView, LinearLayout.LayoutParams(match, 0, 1f))
        }
        appsPage.addView(column, FrameLayout.LayoutParams(match, match))

        bubble = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            visibility = View.GONE
        }
        appsPage.addView(bubble, FrameLayout.LayoutParams(dp(120), dp(120), Gravity.CENTER))

        slider = AlphabetSlider(this).apply {
            onSelect = { index ->
                jumpTo(index)
                bubble.text = AlphabetSlider.LETTERS[index]
                bubble.visibility = View.VISIBLE
            }
            onRelease = { bubble.visibility = View.GONE }
        }
        appsPage.addView(
            slider,
            FrameLayout.LayoutParams(dp(36), match, Gravity.END)
                .apply { topMargin = dp(64); bottomMargin = dp(64) },
        )

        // アプリ一覧の右下の歯車（設定）。一覧ページの上にだけ載っている
        val gear = TextView(this).apply {
            text = "⚙︎"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
            contentDescription = "設定"
            setOnClickListener { showSettings() }
        }
        appsPage.addView(
            gear,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END)
                .apply { rightMargin = dp(4); bottomMargin = dp(8) },
        )

        pager = TwoPagePager(this).apply {
            addView(homePage, FrameLayout.LayoutParams(match, match))
            addView(appsPage, FrameLayout.LayoutParams(match, match))
            onPageChanged = { p -> if (p == 0) resetSearch() }
        }
        root.addView(pager, FrameLayout.LayoutParams(match, match))

        setContentView(root)
        renderWidgets()
    }

    override fun onStart() {
        super.onStart()
        try {
            widgetHost.startListening()
        } catch (_: Exception) {
        }
    }

    override fun onStop() {
        try {
            widgetHost.stopListening()
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    // ホームボタンを押したらお気に入りホームに戻る
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_MAIN) {
            pager.goTo(0)
            resetSearch()
        }
    }

    // ランチャーなので戻るボタンでは閉じない。検索中は検索を消し、その次にホームへ戻る
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            searchBox.text.isNotEmpty() -> resetSearch()
            pager.page == 1 -> pager.goTo(0)
        }
    }

    private fun resetSearch() {
        searchBox.setText("")
        hideKeyboard()
        listView.setSelection(0)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
    }

    // 検索語を含むアプリ名だけに絞る（大文字小文字は区別しない）。検索中はスライダーを隠す
    private fun applyFilter() {
        val q = searchBox.text.toString().trim()
        shown = if (q.isEmpty()) apps else apps.filter { it.label.contains(q, ignoreCase = true) }
        slider.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    // ---- ホームのスワイプ操作（上=アプリ、右=アプリ2、下=通知） ----

    private val gestureDetector by lazy {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (e1 == null || pager.page != 0 || startedOnWidget(e1)) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    val min = dp(80)
                    when {
                        abs(dy) > abs(dx) * 1.5f && abs(dy) > min && abs(velocityY) > 800f -> {
                            // お気に入りが多くてスクロール中のときは、端まで行った後のスワイプだけ反応させる
                            if (dy < 0 && !favScroll.canScrollVertically(1)) {
                                openSearch()
                            } else if (dy > 0 && !favScroll.canScrollVertically(-1)) {
                                expandNotifications()
                            }
                        }
                        abs(dx) > abs(dy) * 1.5f && dx > min && velocityX > 800f ->
                            launchSwipeSlot(SLOT_RIGHT)
                    }
                    return false
                }
            },
        )
    }

    // 触れる側を邪魔しないよう、イベントは観察するだけ
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // スクロールするウィジェット上のスワイプは、ウィジェット側の操作として扱う
    private fun startedOnWidget(e: MotionEvent): Boolean {
        if (widgetBox.childCount == 0) return false
        val loc = IntArray(2)
        widgetBox.getLocationOnScreen(loc)
        return e.rawX >= loc[0] && e.rawX <= loc[0] + widgetBox.width &&
            e.rawY >= loc[1] && e.rawY <= loc[1] + widgetBox.height
    }

    private fun toggleSwipeSlot(slot: String, app: AppItem) {
        val editor = prefs.edit()
        if (prefs.getString(slot, null) == app.key) editor.remove(slot) else editor.putString(slot, app.key)
        editor.apply()
    }

    private fun launchSwipeSlot(slot: String) {
        val key = prefs.getString(slot, null)
        val app = apps.firstOrNull { it.key == key }
        if (app != null) {
            launch(app)
        } else {
            Toast.makeText(this, "アプリ一覧でアプリを長押しして設定します", Toast.LENGTH_SHORT).show()
        }
    }

    // 通知パネルを開く公開APIはないため、リフレクションで呼ぶ（失敗しても何も起きない）
    private fun expandNotifications() {
        try {
            val service = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(service)
        } catch (_: Exception) {
        }
    }

    // ---- 検索・Web検索・設定 ----

    private fun query(): String = searchBox.text.toString().trim()

    // 上スワイプ: アプリ一覧（検索欄）へ飛んでキーボードを出す
    private fun openSearch() {
        pager.goTo(1)
        searchBox.requestFocus()
        searchBox.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }

    // 設定で選んだブラウザ（未設定なら端末の標準）でGoogle検索。http(s)で始まる入力はそのまま開く
    private fun webSearch(q: String) {
        if (q.isEmpty()) return
        val uri = if (q.startsWith("http://") || q.startsWith("https://")) {
            Uri.parse(q)
        } else {
            Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        prefs.getString(PREF_BROWSER, null)?.let { intent.setPackage(it) }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // 指定のブラウザが消えた等: 標準のブラウザで開き直す
            intent.setPackage(null)
            try {
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    private fun browsers() =
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE),
            0,
        ).distinctBy { it.activityInfo.packageName }

    private fun showSettings() {
        val pkg = prefs.getString(PREF_BROWSER, null)
        val browser = if (pkg == null) "システム標準" else appLabelOf(pkg)
        val swipeKey = prefs.getString(SLOT_RIGHT, null)
        val swipeApp = apps.firstOrNull { it.key == swipeKey }?.label ?: "未設定"
        val labels = arrayOf(
            "文字の大きさ（現在: ${TEXT_LABELS[textIndex]}）",
            "右スワイプで開くアプリ（現在: $swipeApp）",
            "ブラウザ（現在: $browser）",
        )
        AlertDialog.Builder(this)
            .setTitle("設定")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showTextSizeChooser()
                    1 -> showSwipeAppChooser()
                    2 -> showBrowserChooser()
                }
            }
            .show()
    }

    private fun showTextSizeChooser() {
        AlertDialog.Builder(this)
            .setTitle("文字の大きさ")
            .setSingleChoiceItems(TEXT_LABELS, textIndex) { dialog, which ->
                prefs.edit().putInt(PREF_TEXT, which).apply()
                applyTextScale()
                dialog.dismiss()
            }
            .show()
    }

    // 設定したサイズを、お気に入り・アプリ一覧・検索欄にすぐ反映する
    private fun applyTextScale() {
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * textScale)
        renderFavorites()
        adapter.notifyDataSetChanged()
    }

    private fun showSwipeAppChooser() {
        val labels = (listOf("未設定") + apps.map { it.label }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("右スワイプで開くアプリ")
            .setItems(labels) { _, which ->
                val editor = prefs.edit()
                if (which == 0) editor.remove(SLOT_RIGHT) else editor.putString(SLOT_RIGHT, apps[which - 1].key)
                editor.apply()
            }
            .show()
    }

    private fun showBrowserChooser() {
        val pm = packageManager
        val list = browsers().sortedBy { it.loadLabel(pm).toString().lowercase() }
        val labels = (listOf("システム標準のブラウザ") + list.map { it.loadLabel(pm).toString() })
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("ブラウザ")
            .setItems(labels) { _, which ->
                val editor = prefs.edit()
                if (which == 0) {
                    editor.remove(PREF_BROWSER)
                } else {
                    editor.putString(PREF_BROWSER, list[which - 1].activityInfo.packageName)
                }
                editor.apply()
            }
            .show()
    }

    // ---- ウィジェット ----

    private fun saveWidgetIds() {
        prefs.edit().putString("widgets", widgetIds.joinToString(",")).apply()
    }

    private fun renderWidgets() {
        widgetBox.removeAllViews()
        var removed = false
        val iter = widgetIds.iterator()
        while (iter.hasNext()) {
            val id = iter.next()
            val info = widgetManager.getAppWidgetInfo(id)
            if (info == null) {
                // 提供元アプリがアンインストールされた等
                widgetHost.deleteAppWidgetId(id)
                iter.remove()
                removed = true
                continue
            }
            widgetBox.addView(buildWidgetView(id, info))
        }
        if (removed) saveWidgetIds()
        widgetBox.setPadding(dp(28), if (widgetBox.childCount > 0) dp(24) else 0, dp(28), 0)
    }

    private fun buildWidgetView(id: Int, info: AppWidgetProviderInfo): View {
        val view = widgetHost.createView(this, id, info)
        view.setOnLongClickListener {
            showWidgetMenu()
            true
        }
        val density = resources.displayMetrics.density
        val wDp = (resources.displayMetrics.widthPixels / density - 56).toInt()
        val hDp = (info.minHeight / density).toInt()
        view.updateAppWidgetSize(Bundle(), wDp, hDp, wDp, hDp)
        view.layoutParams = LinearLayout.LayoutParams(match, info.minHeight.coerceAtLeast(dp(60)))
            .apply { bottomMargin = dp(8) }
        return view
    }

    private fun showWidgetMenu() {
        val labels = mutableListOf("ウィジェットを追加")
        if (widgetIds.isNotEmpty()) labels.add("ウィジェットを削除")
        AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) showAddWidgetDialog() else showRemoveWidgetDialog()
            }
            .show()
    }

    private fun appLabelOf(pkg: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }

    private fun showAddWidgetDialog() {
        val pm = packageManager
        val providers = widgetManager.installedProviders
            .sortedBy { it.loadLabel(pm).lowercase() }
        if (providers.isEmpty()) return
        val labels = providers
            .map { "${it.loadLabel(pm)}（${appLabelOf(it.provider.packageName)}）" }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("ウィジェットを追加")
            .setItems(labels) { _, which -> startBind(providers[which]) }
            .show()
    }

    private fun showRemoveWidgetDialog() {
        val pm = packageManager
        val labels = widgetIds
            .map { widgetManager.getAppWidgetInfo(it)?.loadLabel(pm)?.toString() ?: "ウィジェット" }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("ウィジェットを削除")
            .setItems(labels) { _, which -> removeWidget(widgetIds[which]) }
            .show()
    }

    private fun removeWidget(id: Int) {
        widgetHost.deleteAppWidgetId(id)
        widgetIds.remove(id)
        saveWidgetIds()
        renderWidgets()
    }

    // 追加の流れ: ID確保 → バインド（必要ならシステムの許可画面）→ 設定画面（あれば）→ 配置
    private fun startBind(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        if (widgetManager.bindAppWidgetIdIfAllowed(id, info.provider)) {
            configureOrAdd(id)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            try {
                startActivityForResult(intent, REQ_BIND)
            } catch (_: ActivityNotFoundException) {
                cancelPendingWidget()
            }
        }
    }

    private fun configureOrAdd(id: Int) {
        val info = widgetManager.getAppWidgetInfo(id)
        if (info?.configure == null) {
            addWidget(id)
            return
        }
        try {
            widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
        } catch (_: Exception) {
            cancelPendingWidget()
        }
    }

    private fun addWidget(id: Int) {
        widgetIds.add(id)
        saveWidgetIds()
        pendingWidgetId = -1
        renderWidgets()
        pager.goTo(0)
    }

    private fun cancelPendingWidget() {
        if (pendingWidgetId != -1) widgetHost.deleteAppWidgetId(pendingWidgetId)
        pendingWidgetId = -1
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = pendingWidgetId
        if (id == -1) return
        when (requestCode) {
            REQ_BIND ->
                if (resultCode == RESULT_OK) configureOrAdd(id) else cancelPendingWidget()
            REQ_CONFIGURE ->
                if (resultCode == RESULT_OK) addWidget(id) else cancelPendingWidget()
        }
    }

    private fun loadApps() {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = packageManager
        val collator = Collator.getInstance()
        apps = pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                val label = it.loadLabel(pm).toString()
                AppItem(label, it.activityInfo.packageName, it.activityInfo.name, groupOf(label))
            }
            .sortedWith(
                Comparator<AppItem> { a, b -> a.group.compareTo(b.group) }
                    .thenComparator { a, b -> collator.compare(a.label, b.label) },
            )
        applyFilter()

        // アンインストール済みのお気に入りを掃除する（一覧が空のときは触らない）
        if (apps.isNotEmpty()) {
            val valid = apps.map { it.key }.toSet()
            if (favorites.retainAll(valid)) saveFavorites()
        }
        renderFavorites()
    }

    private fun groupOf(label: String): Int {
        val c = label.firstOrNull()?.uppercaseChar() ?: return 26
        return if (c in 'A'..'Z') c - 'A' else 26
    }

    // 指定した文字以降で最初に存在するアプリへジャンプする
    private fun jumpTo(letterIndex: Int) {
        val position = shown.indexOfFirst { it.group >= letterIndex }
        val target = if (position >= 0) position else shown.size - 1
        if (target >= 0) listView.setSelectionFromTop(target, 0)
    }

    // ---- お気に入り ----

    private fun renderFavorites() {
        favContainer.removeAllViews()
        val byKey = apps.associateBy { it.key }
        val items = favorites.mapNotNull { byKey[it] }

        if (items.isEmpty()) {
            favContainer.addView(
                TextView(this).apply {
                    text = "左にスワイプしてアプリ一覧を開き、アプリを長押ししてお気に入りに追加します。\n\nこの画面を長押しすると、ウィジェットを追加できます。"
                    setTextColor(Color.argb(180, 255, 255, 255))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * textScale)
                },
            )
            return
        }

        for (app in items) {
            favContainer.addView(
                TextView(this).apply {
                    text = app.label
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f * textScale)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener { launch(app) }
                    setOnLongClickListener {
                        showFavoriteMenu(app)
                        true
                    }
                },
            )
        }
    }

    private fun saveFavorites() {
        prefs.edit().putString("favorites", favorites.joinToString("\n")).apply()
    }

    private fun toggleFavorite(app: AppItem) {
        if (!favorites.remove(app.key)) favorites.add(app.key)
        saveFavorites()
        renderFavorites()
    }

    private fun moveUp(app: AppItem) {
        val i = favorites.indexOf(app.key)
        if (i > 0) {
            favorites.removeAt(i)
            favorites.add(i - 1, app.key)
            saveFavorites()
            renderFavorites()
        }
    }

    // ---- メニュー ----

    private fun showListMenu(app: AppItem) {
        val isFav = app.key in favorites
        val isRight = prefs.getString(SLOT_RIGHT, null) == app.key
        val labels = arrayOf(
            if (isFav) "お気に入りから外す" else "お気に入りに追加",
            if (isRight) "右スワイプの設定を解除" else "右スワイプで開くアプリにする",
            "アプリ情報",
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> toggleFavorite(app)
                    1 -> toggleSwipeSlot(SLOT_RIGHT, app)
                    2 -> openAppInfo(app)
                }
            }
            .show()
    }

    private fun showFavoriteMenu(app: AppItem) {
        val labels = arrayOf("上へ移動", "お気に入りから外す", "アプリ情報")
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> moveUp(app)
                    1 -> toggleFavorite(app)
                    2 -> openAppInfo(app)
                }
            }
            .show()
    }

    // ---- 起動 ----

    private fun launch(app: AppItem) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, app.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            loadApps()
        } catch (_: SecurityException) {
            loadApps()
        }
    }

    // アプリ情報（アンインストールもここから）を開く
    private fun openAppInfo(app: AppItem) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", app.packageName, null))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
        ).toInt()

    private inner class AppAdapter : BaseAdapter() {
        // 検索語があるときは末尾に「Webで検索」の行を足す
        private fun hasWebRow() = query().isNotEmpty()

        override fun getCount() = shown.size + if (hasWebRow()) 1 else 0
        override fun getItem(position: Int) = shown.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setSingleLine(true)
                setPadding(0, dp(10), 0, dp(10))
            }
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f * textScale)
            if (position < shown.size) {
                view.text = shown[position].label
                view.setTextColor(Color.WHITE)
            } else {
                view.text = "「${query()}」をWebで検索"
                view.setTextColor(Color.rgb(255, 193, 7))
            }
            return view
        }
    }
}
