package com.example.minilauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.widget.ImageView
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextClock
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import java.util.Calendar
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
import android.os.Build
import java.text.Collator
import java.text.Normalizer
import kotlin.math.abs

class MainActivity : Activity() {

    private data class AppItem(
        val label: String,
        val packageName: String,
        val activityName: String,
        val group: Int, // 0..25 = A..Z, 26 = それ以外(#)
        val original: String, // 端末上の本来のアプリ名（label は変更後の表示名）
        val sortKey: String = label, // 並び替え・グルーピング用のローマ字キー（表示には使わない）
    ) {
        val key: String get() = "$packageName/$activityName"
    }

    private companion object {
        const val SLOT_RIGHT = "swipeRight"
        const val SLOT_UP = "swipeUp"
        const val PREF_BROWSER = "browserPkg"
        const val PREF_PLAYER = "playerPkg"
        const val PREF_TEXT = "textSize"
        const val PREF_HIDDEN = "hiddenApps"
        const val PREF_CLOCK = "clockApp"
        const val PREF_CAL_APP = "calendarApp"
        const val PREF_CAL_SHOW = "calendarShow"
        const val PREF_COLOR = "textColor"
        const val DEFAULT_COLOR = "#FFFFFF"
        val TEXT_SCALES = floatArrayOf(0.85f, 1.0f, 1.2f, 1.4f)
        val TEXT_LABELS = arrayOf("小", "標準", "大", "特大")
    }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private var apps: List<AppItem> = emptyList() // 非表示のアプリも含む全アプリ
    // 一覧・検索から隠すアプリ（スワイプ起動や音楽プレイヤーには引き続き使える）
    private val hiddenKeys: MutableSet<String> by lazy {
        (prefs.getStringSet(PREF_HIDDEN, null) ?: emptySet()).toMutableSet()
    }
    private var shown: List<AppItem> = emptyList() // 検索で絞り込んだ後の表示リスト
    private val favorites = mutableListOf<String>()
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private val namePrefs by lazy { getSharedPreferences("names", MODE_PRIVATE) } // 変更したアプリ名

    // 文字の色（設定でカラーコードを入力。初期値は白 #FFFFFF）
    private val uiColor: Int get() = Color.parseColor(currentColorCode())

    private fun alphaOf(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    // 全角・小文字・先頭の # なし・3桁（#FFF）にも対応して「#RRGGBB」に整える。読み取れなければ null
    private fun normalizeColor(raw: String): String? {
        var t = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .filter { !it.isWhitespace() }
            .removePrefix("#")
            .uppercase()
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6 || !t.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return "#$t"
    }

    private fun currentColorCode(): String =
        normalizeColor(prefs.getString(PREF_COLOR, null) ?: DEFAULT_COLOR) ?: DEFAULT_COLOR

    private val textIndex: Int get() = prefs.getInt(PREF_TEXT, 1).coerceIn(0, TEXT_SCALES.size - 1)
    private val textScale: Float get() = TEXT_SCALES[textIndex]

    private lateinit var pager: TwoPagePager
    private lateinit var favScroll: ScrollView
    private lateinit var favContainer: LinearLayout
    private lateinit var listView: ListView
    private lateinit var searchBox: EditText
    private lateinit var slider: AlphabetSlider
    private lateinit var bubble: TextView
    private lateinit var gearView: TextView
    private lateinit var clockTime: TextClock
    private lateinit var clockDate: TextClock
    private lateinit var analogClock: AnalogClockView
    private lateinit var calendarView: MonthCalendarView
    private val adapter = AppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        // ホームページ = 上に時計と音楽、下にお気に入り
        val homePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                buildHomeTop(),
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
                launch(shown[position])
            }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
                showListMenu(shown[position])
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
        gearView = TextView(this).apply {
            text = "⚙︎"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
            contentDescription = "設定"
            setOnClickListener { showSettings() }
        }
        appsPage.addView(
            gearView,
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
        applyUiColor()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        startMusic()
    }

    override fun onPause() {
        stopMusic()
        super.onPause()
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
        val visible = apps.filter { it.key !in hiddenKeys }
        shown = if (q.isEmpty()) visible else visible.filter { it.label.contains(q, ignoreCase = true) }
        slider.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    // ---- ホームのスワイプ操作（上=アプリ2、右=アプリ、下=通知パネル） ----

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
                    if (e1 == null || pager.page != 0) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    val min = dp(80)
                    when {
                        abs(dy) > abs(dx) * 1.5f && abs(dy) > min && abs(velocityY) > 800f -> {
                            // お気に入りが多くてスクロール中のときは、端まで行った後のスワイプだけ反応させる
                            if (dy < 0 && !favScroll.canScrollVertically(1)) {
                                launchSwipeSlot(SLOT_UP)
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

    // 画面の組み立て中（searchBox作成前）にアダプターから呼ばれても落ちないようにする
    private fun query(): String =
        if (::searchBox.isInitialized) searchBox.text.toString().trim() else ""

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
        val labels = arrayOf(
            "文字の大きさ（現在: ${TEXT_LABELS[textIndex]}）",
            "文字の色（現在: ${currentColorCode()}）",
            "右スワイプで開くアプリ（現在: ${swipeAppLabel(SLOT_RIGHT)}）",
            "上スワイプで開くアプリ（現在: ${swipeAppLabel(SLOT_UP)}）",
            "音楽プレイヤー（現在: ${playerLabel()}）",
            "時計をタップで開くアプリ（現在: ${clockLabel()}）",
            "ホームのカレンダー（現在: ${if (prefs.getBoolean(PREF_CAL_SHOW, true)) "表示" else "非表示"}）",
            "カレンダーをタップで開くアプリ（現在: ${calendarLabel()}）",
            "ブラウザ（現在: $browser）",
            "非表示のアプリ（現在: ${apps.count { it.key in hiddenKeys }}件）",
            "端末の設定を開く",
        )
        AlertDialog.Builder(this)
            .setTitle("設定")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showTextSizeChooser()
                    1 -> showColorDialog(currentColorCode())
                    2 -> showSwipeAppChooser(SLOT_RIGHT, "右スワイプで開くアプリ")
                    3 -> showSwipeAppChooser(SLOT_UP, "上スワイプで開くアプリ")
                    4 -> showPlayerChooser()
                    5 -> showClockAppChooser()
                    6 -> toggleCalendar()
                    7 -> showCalendarAppChooser()
                    8 -> showBrowserChooser()
                    9 -> showHiddenApps()
                    10 -> openDeviceSettings()
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

    // 文字の色: カラーコード（例 #FFFFFF）を入力。不正な入力は理由を出して入力し直せる
    private fun showColorDialog(prefill: String) {
        val input = EditText(this).apply {
            setText(prefill)
            setSelection(text.length)
            hint = DEFAULT_COLOR
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("文字の色")
            .setMessage("カラーコードを入力します（例: #FFFFFF）。初期値は白です。")
            .setView(box)
            .setPositiveButton("変更") { _, _ -> setUiColor(input.text.toString()) }
            .setNegativeButton("キャンセル", null)
            .setNeutralButton("白に戻す") { _, _ -> setUiColor(DEFAULT_COLOR) }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun setUiColor(raw: String) {
        val code = normalizeColor(raw)
        if (code == null) {
            Toast.makeText(this, "6桁のカラーコードで入力してください（例: #FFFFFF）", Toast.LENGTH_LONG).show()
            showColorDialog(raw)
            return
        }
        prefs.edit().putString(PREF_COLOR, code).apply()
        applyUiColor()
    }

    // 設定した色を、ランチャーの文字（お気に入り・一覧・検索欄・時計・スライダー・歯車）にすぐ反映する。
    // 音楽カードは、ジャケットの色に合わせて自動で白/黒を切り替えるので対象外。
    private fun applyUiColor() {
        val c = uiColor
        searchBox.setTextColor(c)
        searchBox.setHintTextColor(alphaOf(c, 140))
        searchBox.setBackgroundColor(alphaOf(c, 40))
        bubble.setTextColor(c)
        gearView.setTextColor(c)
        clockTime.setTextColor(c)
        clockDate.setTextColor(alphaOf(c, 200))
        analogClock.setColor(c)
        calendarView.setColor(c)
        slider.textColor = c
        renderFavorites()
        adapter.notifyDataSetChanged()
    }

    private fun swipeAppLabel(slot: String): String {
        val key = prefs.getString(slot, null)
        return apps.firstOrNull { it.key == key }?.label ?: "未設定"
    }

    private fun showSwipeAppChooser(slot: String, title: String) {
        val labels = (listOf("未設定") + apps.map { it.label }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                val editor = prefs.edit()
                if (which == 0) editor.remove(slot) else editor.putString(slot, apps[which - 1].key)
                editor.apply()
            }
            .show()
    }

    private fun playerLabel(): String {
        val pkg = selectedPlayer ?: return "自動"
        return apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
    }

    // ホームの音楽カードが操作するアプリを選ぶ（「自動」は再生中のアプリ）
    private fun showPlayerChooser() {
        val labels = (listOf("自動（再生中のアプリ）") + apps.map { it.label }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("音楽プレイヤー")
            .setItems(labels) { _, which ->
                val editor = prefs.edit()
                if (which == 0) editor.remove(PREF_PLAYER) else editor.putString(PREF_PLAYER, apps[which - 1].packageName)
                editor.apply()
                refreshSessions()
            }
            .show()
    }

    // ホームの時計をタップしたときに開くアプリ。未設定のときは端末の時計アプリ（アラーム画面）
    private fun openClockApp() {
        val key = prefs.getString(PREF_CLOCK, null)
        val app = apps.firstOrNull { it.key == key }
        if (app != null) {
            launch(app)
            return
        }
        try {
            startActivity(
                Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "設定で時計を開くアプリを選んでください", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clockLabel(): String {
        val key = prefs.getString(PREF_CLOCK, null)
        return apps.firstOrNull { it.key == key }?.label ?: "自動"
    }

    private fun showClockAppChooser() {
        val labels = (listOf("自動（端末の時計アプリ）") + apps.map { it.label }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("時計をタップで開くアプリ")
            .setItems(labels) { _, which ->
                val editor = prefs.edit()
                if (which == 0) editor.remove(PREF_CLOCK) else editor.putString(PREF_CLOCK, apps[which - 1].key)
                editor.apply()
            }
            .show()
    }

    // ホームのカレンダーの表示/非表示を切り替える
    private fun toggleCalendar() {
        val show = !prefs.getBoolean(PREF_CAL_SHOW, true)
        prefs.edit().putBoolean(PREF_CAL_SHOW, show).apply()
        calendarView.visibility = if (show) View.VISIBLE else View.GONE
    }

    // カレンダーをタップしたときに開くアプリ。未設定のときは端末のカレンダー（今日の表示）
    private fun openCalendarApp() {
        val key = prefs.getString(PREF_CAL_APP, null)
        val app = apps.firstOrNull { it.key == key }
        if (app != null) {
            launch(app)
            return
        }
        try {
            startActivity(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            Toast.makeText(this, "設定でカレンダーを開くアプリを選んでください", Toast.LENGTH_SHORT).show()
        }
    }

    private fun calendarLabel(): String {
        val key = prefs.getString(PREF_CAL_APP, null)
        return apps.firstOrNull { it.key == key }?.label ?: "自動"
    }

    private fun showCalendarAppChooser() {
        val labels = (listOf("自動（端末のカレンダー）") + apps.map { it.label }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("カレンダーをタップで開くアプリ")
            .setItems(labels) { _, which ->
                val editor = prefs.edit()
                if (which == 0) editor.remove(PREF_CAL_APP) else editor.putString(PREF_CAL_APP, apps[which - 1].key)
                editor.apply()
            }
            .show()
    }

    // 端末本体の設定アプリを開く
    private fun openDeviceSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
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

    // ---- ホームの時計（アナログ + デジタル）と音楽プレイヤー ----

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var musicAlbum: TextView
    private lateinit var musicTitle: TextView
    private lateinit var musicArtist: TextView
    private lateinit var artView: ImageView
    private lateinit var fadeView: View
    private lateinit var cardBg: GradientDrawable
    private lateinit var fadeBg: GradientDrawable
    private lateinit var playIcon: MediaIconView
    private lateinit var controlIcons: List<MediaIconView>
    private var currentController: MediaController? = null
    private var artToken = 0
    private var sessionsListenerRegistered = false

    private val listenerComponent by lazy { ComponentName(this, MediaListenerService::class.java) }
    private val sessionManager by lazy { getSystemService(MediaSessionManager::class.java) }

    // 操作する音楽アプリ（設定で選ぶ）。null のときは「自動」＝再生中のアプリ
    private val selectedPlayer: String? get() = prefs.getString(PREF_PLAYER, null)

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pickController(list ?: emptyList())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = showTrack(metadata)

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            showPlaying(state?.state == PlaybackState.STATE_PLAYING)
            if (selectedPlayer == null) refreshSessions() // 自動のときは、再生中のアプリに追従する
        }

        override fun onSessionDestroyed() = refreshSessions()
    }

    private fun buildHomeTop(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), 0)
        }

        // 時計: 左にアナログ、右にデジタル（時刻と日付）
        val digital = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, 0, 0)
        }
        clockTime = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(uiColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
        }
        digital.addView(clockTime)
        clockDate = TextClock(this).apply {
            format24Hour = "M月d日（E）"
            format12Hour = "M月d日（E）"
            setTextColor(alphaOf(uiColor, 200))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        digital.addView(clockDate)
        analogClock = AnalogClockView(this)
        val clockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(analogClock, LinearLayout.LayoutParams(dp(120), dp(120)))
            addView(
                digital,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        clockRow.setOnClickListener { openClockApp() }
        box.addView(clockRow, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))

        box.addView(
            buildMusicCard(),
            LinearLayout.LayoutParams(match, dp(116)).apply { topMargin = dp(16) },
        )

        // 音楽カードの下に今月のカレンダー。タップでカレンダーアプリを開く
        calendarView = MonthCalendarView(this).apply {
            setColor(uiColor)
            visibility = if (prefs.getBoolean(PREF_CAL_SHOW, true)) View.VISIBLE else View.GONE
            setOnClickListener { openCalendarApp() }
        }
        box.addView(
            calendarView,
            LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) },
        )
        return box
    }

    // 音楽カード: 左に曲情報と操作ボタン、右にジャケット（左端はカードの色に溶ける）。
    // カードの色はジャケットの平均色、文字とボタンは明るさに合わせて白/黒を切り替える
    private fun buildMusicCard(): View {
        val h = dp(116)
        cardBg = GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
        val card = FrameLayout(this).apply {
            background = cardBg
            clipToOutline = true
        }

        artView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        card.addView(artView, FrameLayout.LayoutParams(h, h, Gravity.END))

        fadeBg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0, 0))
        fadeView = View(this).apply {
            background = fadeBg
            visibility = View.GONE
        }
        val fadeWidth = (h * 0.6f).toInt()
        card.addView(
            fadeView,
            FrameLayout.LayoutParams(fadeWidth, h, Gravity.END).apply { marginEnd = h - fadeWidth },
        )

        fun label(sizeSp: Float) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        musicAlbum = label(11f)
        musicTitle = label(18f)
        musicArtist = label(14f)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(96), 0)
            addView(musicAlbum)
            addView(musicTitle)
            addView(musicArtist)
            setOnClickListener { onMusicInfoClick() }
        }
        card.addView(texts, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        fun icon(type: Int, onClick: () -> Unit) = MediaIconView(this, type).apply {
            setOnClickListener { onClick() }
        }
        val volDown = icon(MediaIconView.VOL_DOWN) { adjustVolume(AudioManager.ADJUST_LOWER) }
        val prev = icon(MediaIconView.PREV) { mediaAction(MediaIconView.PREV) }
        playIcon = icon(MediaIconView.PLAY) { mediaAction(MediaIconView.PLAY) }
        val next = icon(MediaIconView.NEXT) { mediaAction(MediaIconView.NEXT) }
        val volUp = icon(MediaIconView.VOL_UP) { adjustVolume(AudioManager.ADJUST_RAISE) }
        controlIcons = listOf(volDown, prev, playIcon, next, volUp)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, 0, dp(4))
            for (v in controlIcons) addView(v, LinearLayout.LayoutParams(dp(36), dp(40)))
        }
        card.addView(
            controls,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START),
        )

        showMusicMessage("再生中の曲はありません", "")
        return card
    }

    // ---- 再生情報の取得（通知へのアクセスが許可されているとき） ----

    private fun startMusic() {
        if (!isNotificationAccessEnabled()) {
            showMusicMessage(
                "タップして通知へのアクセスを許可",
                "曲名とジャケットの表示に使います",
            )
            return
        }
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            sessionsListenerRegistered = true
        } catch (_: SecurityException) {
        }
        refreshSessions()
    }

    private fun stopMusic() {
        if (sessionsListenerRegistered) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (_: Exception) {
            }
            sessionsListenerRegistered = false
        }
        detachController()
    }

    private fun detachController() {
        currentController?.unregisterCallback(controllerCallback)
        currentController = null
    }

    private fun refreshSessions() {
        if (!isNotificationAccessEnabled()) return
        val list = try {
            sessionManager.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) {
            emptyList<MediaController>()
        }
        pickController(list)
    }

    private fun pickController(list: List<MediaController>) {
        val target = selectedPlayer
        val c = if (target != null) {
            list.firstOrNull { it.packageName == target }
        } else {
            list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list.firstOrNull { it.sessionToken == currentController?.sessionToken }
                ?: list.firstOrNull()
        }
        if (c != null && c.sessionToken == currentController?.sessionToken) return
        detachController()
        currentController = c
        if (c == null) {
            val app = apps.firstOrNull { it.packageName == target }
            if (app != null) {
                showMusicMessage("${app.label} は再生していません", "タップで開く")
            } else {
                showMusicMessage("再生中の曲はありません", "")
            }
            return
        }
        c.registerCallback(controllerCallback, handler)
        showTrack(c.metadata)
        showPlaying(c.playbackState?.state == PlaybackState.STATE_PLAYING)
    }

    // ---- カードの表示 ----

    private fun showMusicMessage(title: String, sub: String) {
        artToken++
        musicAlbum.visibility = View.GONE
        musicTitle.text = title
        musicArtist.text = sub
        showArt(null)
        showPlaying(false)
    }

    private fun showTrack(md: MediaMetadata?) {
        musicTitle.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "（曲名なし）"
        musicArtist.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        musicAlbum.text = album
        musicAlbum.visibility = if (album.isEmpty()) View.GONE else View.VISIBLE
        loadArt(md)
    }

    private fun showPlaying(playing: Boolean) {
        playIcon.type = if (playing) MediaIconView.PAUSE else MediaIconView.PLAY
        playIcon.invalidate()
    }

    // ジャケット: 曲情報に画像が入っていればそれを、無ければ画像のURI（content/file）を読む
    private fun loadArt(md: MediaMetadata?) {
        val token = ++artToken
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) {
            showArt(bmp)
            return
        }
        val uri = (md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI))?.let { Uri.parse(it) }
        showArt(null)
        if (uri == null || uri.scheme !in listOf("content", "file", "android.resource")) return
        Thread {
            val decoded = try {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
            handler.post { if (token == artToken && decoded != null) showArt(decoded) }
        }.start()
    }

    private fun showArt(bmp: Bitmap?) {
        if (bmp == null) {
            artView.visibility = View.GONE
            fadeView.visibility = View.GONE
            applyCardColors(null)
            return
        }
        artView.setImageBitmap(bmp)
        artView.visibility = View.VISIBLE
        fadeView.visibility = View.VISIBLE
        val one = Bitmap.createScaledBitmap(bmp, 1, 1, true)
        val avg = one.getPixel(0, 0) or 0xFF000000.toInt()
        applyCardColors(avg)
    }

    private fun applyCardColors(bg: Int?) {
        val light = bg != null &&
            (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0 > 0.6
        val fg = if (light) Color.rgb(24, 24, 24) else Color.WHITE
        cardBg.setColor(bg ?: Color.argb(48, 255, 255, 255))
        if (bg != null) fadeBg.colors = intArrayOf(bg, bg and 0x00FFFFFF)
        fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
        musicTitle.setTextColor(fg)
        musicAlbum.setTextColor(withAlpha(fg, 190))
        musicArtist.setTextColor(withAlpha(fg, 220))
        controlIcons.forEach { it.setIconColor(fg) }
    }

    // ---- 操作 ----

    // 選んだアプリの再生を直接操作する。そのアプリが動いていないときは、アプリを開く
    private fun mediaAction(kind: Int) {
        val c = currentController
        if (c != null) {
            val t = c.transportControls
            when (kind) {
                MediaIconView.PREV -> t.skipToPrevious()
                MediaIconView.NEXT -> t.skipToNext()
                else ->
                    if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            }
            return
        }
        val app = apps.firstOrNull { it.packageName == selectedPlayer }
        if (app != null) {
            launch(app)
            return
        }
        // 通知へのアクセス未許可など: 再生中のアプリにメディアキーを送る
        sendMediaKey(
            when (kind) {
                MediaIconView.PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                MediaIconView.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            },
        )
    }

    private fun sendMediaKey(keyCode: Int) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun adjustVolume(direction: Int) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    // 曲名の行をタップ: 許可がなければ許可画面へ、再生中ならそのアプリを、停止中なら選んだアプリを開く
    private fun onMusicInfoClick() {
        if (!isNotificationAccessEnabled()) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: ActivityNotFoundException) {
            }
            return
        }
        val c = currentController
        if (c != null) {
            try {
                c.sessionActivity?.send()
            } catch (_: Exception) {
            }
            return
        }
        apps.firstOrNull { it.packageName == selectedPlayer }?.let { launch(it) }
    }

    // 曲名の表示には「通知へのアクセス」の許可が必要（Androidの仕組み上、ここ経由でしか再生情報を読めない）
    private fun isNotificationAccessEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun appLabelOf(pkg: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }

    private fun loadApps() {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = packageManager
        val collator = Collator.getInstance()
        apps = pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                val original = it.loadLabel(pm).toString()
                val pkg = it.activityInfo.packageName
                val act = it.activityInfo.name
                val label = namePrefs.getString("$pkg/$act", null) ?: original
                val key = romajiKey(label)
                AppItem(label, pkg, act, groupOf(key), original, key)
            }
            .sortedWith(
                Comparator<AppItem> { a, b -> a.group.compareTo(b.group) }
                    .thenComparator { a, b -> collator.compare(a.sortKey, b.sortKey) },
            )
        applyFilter()

        // アンインストール済みのお気に入りを掃除する（一覧が空のときは触らない）
        if (apps.isNotEmpty()) {
            val valid = apps.map { it.key }.toSet()
            if (favorites.retainAll(valid)) saveFavorites()
            if (hiddenKeys.retainAll(valid)) saveHidden()
        }
        renderFavorites()
    }

    // ひらがな・カタカナをローマ字に変換する（漢字はそのまま残る）。API 24 未満や失敗時は変換なし
    private val kanaToLatin: android.icu.text.Transliterator? by lazy {
        if (Build.VERSION.SDK_INT < 24) null
        else try {
            android.icu.text.Transliterator.getInstance("Hiragana-Latin; Katakana-Latin; Latin-ASCII")
        } catch (_: Exception) {
            null
        }
    }

    // 並び替え用のキー。全角英数は半角に、よくある漢字語とかなはローマ字にする（表示名そのものは変えない）
    private fun romajiKey(label: String): String {
        val t = KanjiReadings.toKana(Normalizer.normalize(label, Normalizer.Form.NFKC).trim())
        return try {
            kanaToLatin?.transliterate(t) ?: t
        } catch (_: Exception) {
            t
        }
    }

    private fun groupOf(key: String): Int {
        val c = key.firstOrNull()?.uppercaseChar() ?: return 26
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
                    text = "左にスワイプしてアプリ一覧を開き、アプリを長押ししてお気に入りに追加します。"
                    setTextColor(alphaOf(uiColor, 180))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * textScale)
                },
            )
            return
        }

        for (app in items) {
            favContainer.addView(
                TextView(this).apply {
                    text = app.label
                    setTextColor(uiColor)
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
        val isUp = prefs.getString(SLOT_UP, null) == app.key
        showActionMenu(
            app,
            listOf(
                (if (isFav) "お気に入りから外す" else "お気に入りに追加") to { toggleFavorite(app) },
                "名前を変更" to { showRenameDialog(app) },
                (if (isRight) "右スワイプの設定を解除" else "右スワイプで開くアプリにする") to
                    { toggleSwipeSlot(SLOT_RIGHT, app) },
                (if (isUp) "上スワイプの設定を解除" else "上スワイプで開くアプリにする") to
                    { toggleSwipeSlot(SLOT_UP, app) },
                "非表示にする" to { hideApp(app) },
                "アプリ情報" to { openAppInfo(app) },
            ),
        )
    }

    private fun showFavoriteMenu(app: AppItem) {
        showActionMenu(
            app,
            listOf(
                "上へ移動" to { moveUp(app) },
                "お気に入りから外す" to { toggleFavorite(app) },
                "名前を変更" to { showRenameDialog(app) },
                "非表示にする" to { hideApp(app) },
                "アプリ情報" to { openAppInfo(app) },
            ),
        )
    }

    // 長押しメニューの共通部分。ユーザーアプリにはアンインストールを末尾に足す
    private fun showActionMenu(app: AppItem, actions: List<Pair<String, () -> Unit>>) {
        val all = if (isUninstallable(app)) actions + ("アンインストール" to { uninstall(app) }) else actions
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(all.map { it.first }.toTypedArray()) { _, which -> all[which].second() }
            .show()
    }

    // ---- 非表示・アンインストール ----

    private fun saveHidden() {
        prefs.edit().putStringSet(PREF_HIDDEN, hiddenKeys.toSet()).apply()
    }

    private fun hideApp(app: AppItem) {
        hiddenKeys.add(app.key)
        saveHidden()
        if (favorites.remove(app.key)) saveFavorites()
        loadApps()
        android.widget.Toast.makeText(
            this, "「${app.label}」を非表示にしました（設定から戻せます）", android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    private fun showHiddenApps() {
        val list = apps.filter { it.key in hiddenKeys }
        if (list.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("非表示のアプリ")
                .setMessage("非表示にしているアプリはありません。")
                .setPositiveButton("閉じる", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("タップで表示に戻す")
            .setItems(list.map { it.label }.toTypedArray()) { _, which ->
                hiddenKeys.remove(list[which].key)
                saveHidden()
                loadApps()
                showHiddenApps() // 続けて戻せるよう開き直す（残りが無ければメッセージ表示）
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    // 端末に最初から入っているアプリ（システムアプリ）はアンインストールできない
    private fun isUninstallable(app: AppItem): Boolean = try {
        val flags = packageManager.getApplicationInfo(app.packageName, 0).flags
        flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0
    } catch (_: Exception) {
        false
    }

    // システムの確認ダイアログが出る。完了後は onResume の loadApps() で一覧から消える
    @Suppress("DEPRECATION")
    private fun uninstall(app: AppItem) {
        val intent = Intent(Intent.ACTION_DELETE)
            .setData(Uri.fromParts("package", app.packageName, null))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }

    // アプリ名の変更。空にするか「元に戻す」で、端末上の本来の名前に戻る
    private fun showRenameDialog(app: AppItem) {
        val input = EditText(this).apply {
            setText(app.label)
            setSelection(text.length)
            setSingleLine(true)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("名前を変更")
            .setView(box)
            .setPositiveButton("変更") { _, _ -> setCustomName(app, input.text.toString().trim()) }
            .setNegativeButton("キャンセル", null)
        if (app.label != app.original) {
            builder.setNeutralButton("元に戻す") { _, _ -> setCustomName(app, "") }
        }
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun setCustomName(app: AppItem, name: String) {
        val editor = namePrefs.edit()
        if (name.isEmpty() || name == app.original) editor.remove(app.key) else editor.putString(app.key, name)
        editor.apply()
        loadApps() // 並び順・お気に入り・検索結果もまとめて更新する
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
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setTextColor(uiColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setSingleLine(true)
                setPadding(0, dp(10), 0, dp(10))
            }
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f * textScale)
            view.text = shown[position].label
            view.setTextColor(uiColor)
            return view
        }
    }
}


// 曲名を読むには「通知へのアクセス」を受け取るサービスが必要。中身は空でよい。
class MediaListenerService : NotificationListenerService()

// ホームに置く今月のカレンダー（日曜始まり）。今日は丸で強調する。
// 画面に戻ってきたときに月や日付が変わっていても、描き直しと高さの計算をやり直す。
class MonthCalendarView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val rowH = 24f * density
    private var color = Color.WHITE

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val holidayColor = Color.rgb(255, 138, 128)

    // 祝日は月ごとに一度だけ計算する
    private var holidayYear = -1
    private var holidayMonth = -1
    private var holidays: Set<Int> = emptySet()

    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7)
        style = Paint.Style.FILL
    }

    fun setColor(c: Int) {
        color = c
        invalidate()
    }

    // 今月の1日が日曜から数えて何番目か（0=日曜）と、その月の日数
    private fun firstOffset(cal: Calendar): Int {
        val first = cal.clone() as Calendar
        first.set(Calendar.DAY_OF_MONTH, 1)
        return first.get(Calendar.DAY_OF_WEEK) - 1
    }

    private fun weeks(cal: Calendar): Int =
        (firstOffset(cal) + cal.getActualMaximum(Calendar.DAY_OF_MONTH) + 6) / 7

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = ((1 + weeks(Calendar.getInstance())) * rowH).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            requestLayout()
            invalidate()
        }
    }

    // 行の中央に文字を置いたときのベースライン
    private fun baseline(cy: Float): Float = cy - (textPaint.descent() + textPaint.ascent()) / 2f

    override fun onDraw(canvas: Canvas) {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_MONTH)
        val offset = firstOffset(cal)
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val colW = width / 7f

        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        if (year != holidayYear || month != holidayMonth) {
            holidays = JapaneseHolidays.daysIn(year, month)
            holidayYear = year
            holidayMonth = month
        }

        val names = "日月火水木金土"
        textPaint.color = Color.argb(150, Color.red(color), Color.green(color), Color.blue(color))
        for (i in 0 until 7) {
            canvas.drawText(names[i].toString(), (i + 0.5f) * colW, baseline(rowH / 2f), textPaint)
        }

        for (d in 1..days) {
            val idx = offset + d - 1
            val col = idx % 7
            val cx = (col + 0.5f) * colW
            val cy = (1 + idx / 7 + 0.5f) * rowH
            if (d == today) {
                canvas.drawCircle(cx, cy, rowH * 0.42f, todayPaint)
                textPaint.color = Color.BLACK
            } else {
                textPaint.color = when {
                    d in holidays || col == 0 -> holidayColor
                    col == 6 -> Color.rgb(130, 177, 255)
                    else -> color
                }
            }
            canvas.drawText(d.toString(), cx, baseline(cy), textPaint)
        }
    }
}

// 日本の祝日（内閣府の定め）を端末内で計算する。ネットや権限は使わない。
// 春分・秋分の日は 1980〜2099 年に使える近似式。2020・2021 年のオリンピック特例は入れていない。
object JapaneseHolidays {

    private fun key(month: Int, day: Int) = month * 100 + day

    private fun cal(year: Int, month: Int, day: Int): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day)
        }

    // その月の第 n 月曜日の日付
    private fun nthMonday(year: Int, month: Int, n: Int): Int {
        val dow = cal(year, month, 1).get(Calendar.DAY_OF_WEEK)
        return 1 + (Calendar.MONDAY - dow + 7) % 7 + 7 * (n - 1)
    }

    private fun equinox(year: Int, base: Double): Int =
        (base + 0.242194 * (year - 1980) - ((year - 1980) / 4)).toInt()

    // 指定した月の祝日（振替休日・国民の休日を含む）の「日」の集まり
    fun daysIn(year: Int, month: Int): Set<Int> {
        val base = mutableSetOf(
            key(1, 1), key(1, nthMonday(year, 1, 2)), key(2, 11), key(2, 23),
            key(3, equinox(year, 20.8431)), key(4, 29), key(5, 3), key(5, 4), key(5, 5),
            key(7, nthMonday(year, 7, 3)), key(8, 11), key(9, nthMonday(year, 9, 3)),
            key(9, equinox(year, 23.2488)), key(10, nthMonday(year, 10, 2)), key(11, 3), key(11, 23),
        )
        val all = base.toMutableSet()

        // 振替休日: 祝日が日曜なら、次に祝日でない日が休みになる
        for (k in base.sorted()) {
            val c = cal(year, k / 100, k % 100)
            if (c.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) continue
            do {
                c.add(Calendar.DAY_OF_MONTH, 1)
            } while (key(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)) in all)
            all.add(key(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)))
        }

        // 国民の休日: 前日と翌日が祝日にはさまれた平日
        val c = cal(year, month, 1)
        for (d in 1..c.getActualMaximum(Calendar.DAY_OF_MONTH)) {
            val day = cal(year, month, d)
            val prev = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
            val next = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
            fun k(x: Calendar) = key(x.get(Calendar.MONTH) + 1, x.get(Calendar.DAY_OF_MONTH))
            if (k(day) !in all && day.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY &&
                k(prev) in all && k(next) in all
            ) {
                all.add(k(day))
            }
        }

        return all.filter { it / 100 == month }.map { it % 100 }.toSet()
    }
}

// ホームに置くアナログ時計（秒針つき）。画面に見えている間だけ毎秒描き直す。
class AnalogClockView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private fun paint(color: Int, widthDp: Float, style: Paint.Style = Paint.Style.STROKE) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.style = style
            strokeWidth = widthDp * density
            strokeCap = Paint.Cap.ROUND
        }

    private val ring = paint(Color.WHITE, 2f)
    private val tick = paint(Color.WHITE, 2f)
    private val hourHand = paint(Color.WHITE, 4.5f)
    private val minuteHand = paint(Color.WHITE, 3f)
    private val accent = Color.rgb(255, 193, 7)
    private val secondHand = paint(accent, 1.5f)
    private val hub = paint(accent, 1f, Paint.Style.FILL)

    // 文字盤・目盛り・時針・分針の色（秒針と中心は強調色のまま）
    fun setColor(color: Int) {
        ring.color = color
        tick.color = color
        hourHand.color = color
        minuteHand.color = color
        invalidate()
    }

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == View.VISIBLE) restart()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) restart() else removeCallbacks(ticker)
    }

    private fun restart() {
        removeCallbacks(ticker)
        post(ticker)
    }

    // 中心から角度 deg（12時が0度、時計回り）の向きに、半径 from..to の線を引く
    private fun line(canvas: Canvas, cx: Float, cy: Float, deg: Float, from: Float, to: Float, p: Paint) {
        val rad = Math.toRadians(deg.toDouble())
        val sin = Math.sin(rad).toFloat()
        val cos = Math.cos(rad).toFloat()
        canvas.drawLine(cx + from * sin, cy - from * cos, cx + to * sin, cy - to * cos, p)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - ring.strokeWidth
        canvas.drawCircle(cx, cy, r, ring)

        for (i in 0 until 12) {
            val major = i % 3 == 0
            tick.strokeWidth = (if (major) 2.5f else 1.5f) * density
            line(canvas, cx, cy, i * 30f, r * (if (major) 0.80f else 0.88f), r * 0.95f, tick)
        }

        val cal = Calendar.getInstance()
        val sec = cal.get(Calendar.SECOND).toFloat()
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hour = cal.get(Calendar.HOUR) + min / 60f
        line(canvas, cx, cy, hour * 30f, 0f, r * 0.5f, hourHand)
        line(canvas, cx, cy, min * 6f, 0f, r * 0.75f, minuteHand)
        line(canvas, cx, cy, sec * 6f, -r * 0.15f, r * 0.85f, secondHand)
        canvas.drawCircle(cx, cy, 3f * density, hub)
    }
}


// 音楽カードのボタン。画像を使わず、図形を描いて表示する。
class MediaIconView(context: Context, var type: Int) : View(context) {

    companion object {
        const val VOL_DOWN = 0
        const val PREV = 1
        const val PLAY = 2
        const val PAUSE = 3
        const val NEXT = 4
        const val VOL_UP = 5
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 0.09f // 描画時に 0〜1 の座標系へ拡大するので、この単位で指定する
    }
    private val path = Path()
    private val small = RectF(0.33f, 0.28f, 0.77f, 0.72f)
    private val large = RectF(0.17f, 0.12f, 0.93f, 0.88f)

    init {
        setIconColor(Color.WHITE)
    }

    fun setIconColor(color: Int) {
        fill.color = color
        stroke.color = color
        invalidate()
    }

    private fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): Path {
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.close()
        return path
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val s = size * 0.46f
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        canvas.scale(s, s)
        when (type) {
            PLAY -> canvas.drawPath(triangle(0.22f, 0.08f, 0.92f, 0.5f, 0.22f, 0.92f), fill)
            PAUSE -> {
                canvas.drawRect(0.15f, 0.1f, 0.4f, 0.9f, fill)
                canvas.drawRect(0.6f, 0.1f, 0.85f, 0.9f, fill)
            }
            NEXT -> {
                canvas.drawPath(triangle(0.08f, 0.1f, 0.68f, 0.5f, 0.08f, 0.9f), fill)
                canvas.drawRect(0.72f, 0.1f, 0.92f, 0.9f, fill)
            }
            PREV -> {
                canvas.drawRect(0.08f, 0.1f, 0.28f, 0.9f, fill)
                canvas.drawPath(triangle(0.92f, 0.1f, 0.32f, 0.5f, 0.92f, 0.9f), fill)
            }
            VOL_DOWN, VOL_UP -> {
                path.reset()
                path.moveTo(0.02f, 0.36f)
                path.lineTo(0.24f, 0.36f)
                path.lineTo(0.52f, 0.1f)
                path.lineTo(0.52f, 0.9f)
                path.lineTo(0.24f, 0.64f)
                path.lineTo(0.02f, 0.64f)
                path.close()
                canvas.drawPath(path, fill)
                canvas.drawArc(small, -50f, 100f, false, stroke)
                if (type == VOL_UP) canvas.drawArc(large, -50f, 100f, false, stroke)
            }
        }
        canvas.restore()
    }
}

/**
 * アプリ名によく出てくる漢字語の読み（ひらがな）。
 * 並び替え用のローマ字キーを作るためだけに使い、表示名は変えない。
 * 載っていない漢字はそのまま残り、「#」グループになる。足したい語は下の WORDS に追記する。
 */
object KanjiReadings {

    private val WORDS: Map<String, String> = mapOf(
        // 端末の基本アプリ
        "設定" to "せってい", "電話" to "でんわ", "連絡先" to "れんらくさき", "連絡" to "れんらく",
        "時計" to "とけい", "電卓" to "でんたく", "計算機" to "けいさんき", "計算" to "けいさん",
        "写真" to "しゃしん", "音楽" to "おんがく", "動画" to "どうが", "映画" to "えいが",
        "地図" to "ちず", "天気" to "てんき", "気象" to "きしょう", "辞書" to "じしょ", "辞典" to "じてん",
        "翻訳" to "ほんやく", "検索" to "けんさく", "管理" to "かんり", "通知" to "つうち",
        "録音" to "ろくおん", "録画" to "ろくが", "画面" to "がめん", "画像" to "がぞう",
        "撮影" to "さつえい", "編集" to "へんしゅう", "加工" to "かこう", "共有" to "きょうゆう",
        "診断" to "しんだん", "安全" to "あんぜん", "手帳" to "てちょう", "日記" to "にっき",
        "予定" to "よてい", "目覚まし" to "めざまし", "懐中電灯" to "かいちゅうでんとう",
        "電灯" to "でんとう", "方位磁針" to "ほういじしん", "文字" to "もじ", "入力" to "にゅうりょく",
        "文書" to "ぶんしょ", "表計算" to "ひょうけいさん", "名刺" to "めいし", "手紙" to "てがみ",
        "伝言" to "でんごん", "住所" to "じゅうしょ", "会議" to "かいぎ", "仕事" to "しごと",
        "放送" to "ほうそう", "番組" to "ばんぐみ", "配信" to "はいしん",
        // 端末の管理・メンテナンス系
        "電池" to "でんち", "充電" to "じゅうでん", "省電力" to "しょうでんりょく", "容量" to "ようりょう",
        "整理" to "せいり", "掃除" to "そうじ", "最適化" to "さいてきか", "速度" to "そくど",
        "測定" to "そくてい", "拡大" to "かくだい", "拡張" to "かくちょう", "機能" to "きのう",
        "更新" to "こうしん", "情報" to "じょうほう", "接続" to "せつぞく", "無線" to "むせん",
        // 本・ニュース・学習
        "新聞" to "しんぶん", "漫画" to "まんが", "読書" to "どくしょ", "電子" to "でんし",
        "書籍" to "しょしゃく", "学習" to "がくしゅう", "英語" to "えいご", "日本語" to "にほんご",
        "漢字" to "かんじ", "国語" to "こくご", "数学" to "すうがく",
        // 乗り物・旅行
        "乗換案内" to "のりかえあんない", "乗換" to "のりかえ", "乗り換え" to "のりかえ", "案内" to "あんない",
        "配車" to "はいしゃ", "旅行" to "りょこう", "航空" to "こうくう", "鉄道" to "てつどう",
        "電車" to "でんしゃ", "地下鉄" to "ちかてつ", "駅" to "えき", "路線" to "ろせん",
        "運転" to "うんてん", "免許" to "めんきょ", "乗車" to "じょうしゃ", "切符" to "きっぷ",
        "自転車" to "じてんしゃ", "登山" to "とざん",
        // お金・買い物
        "銀行" to "ぎんこう", "決済" to "けっさい", "家計簿" to "かけいぼ", "家計" to "かけい",
        "財布" to "さいふ", "支払い" to "しはらい", "送金" to "そうきん", "証券" to "しょうけん",
        "保険" to "ほけん", "郵便" to "ゆうびん", "買い物" to "かいもの", "注文" to "ちゅうもん",
        "予約" to "よやく", "割引" to "わりびき", "宅配" to "たくはい", "出前" to "でまえ",
        "無料" to "むりょう", "公式" to "こうしき", "楽天" to "らくてん", "市場" to "いちば",
        "求人" to "きゅうじん", "不動産" to "ふどうさん",
        // 健康・生活
        "病院" to "びょういん", "健康" to "けんこう", "運動" to "うんどう", "歩数" to "ほすう",
        "睡眠" to "すいみん", "体重" to "たいじゅう", "血圧" to "けつあつ", "料理" to "りょうり",
        "家族" to "かぞく", "子供" to "こども", "育児" to "いくじ", "防災" to "ぼうさい",
        "地震" to "じしん", "雨雲" to "あまぐも", "天文" to "てんもん", "緊急" to "きんきゅう",
    )

    private val MAX_LEN = WORDS.keys.maxOf { it.length }

    // 辞書にある語を、長い語から順に探してひらがなに置き換える。無い文字はそのまま
    fun toKana(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            var matched = false
            var len = minOf(MAX_LEN, text.length - i)
            while (len >= 1) {
                val reading = WORDS[text.substring(i, i + len)]
                if (reading != null) {
                    sb.append(reading)
                    i += len
                    matched = true
                    break
                }
                len--
            }
            if (!matched) {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }
}
