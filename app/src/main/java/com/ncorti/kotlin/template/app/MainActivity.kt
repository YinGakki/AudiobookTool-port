package com.ncorti.kotlin.template.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class MainActivity : Activity() {

    // --- 监控规则 (可修改) ---
    data class MonitorRule(val keyword: String, val threshold: Int, val alertMessage: String)
    private val RULES = listOf(
        MonitorRule("Error", 3, "严重错误 (Error x3)"),
        MonitorRule("Timeout", 3, "网络超时 (Timeout x3)"),
        MonitorRule("Exception", 3, "程序异常 (Exception x3)"),
        MonitorRule("失败", 3, "操作失败报警")
    )
    private val CHECK_INTERVAL_MS = 30000L 
    private val NOTIFY_COOLDOWN_MS = 60000L 

    // --- 变量 ---
    private var lastNotifyTime = 0L
    private lateinit var etAlias: EditText
    private lateinit var etUrl: EditText
    private lateinit var etPort: EditText
    private lateinit var btnGo: Button
    private lateinit var listViewHistory: ListView
    private lateinit var layoutHome: LinearLayout
    private lateinit var webviewContainer: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var btnHome: Button; private lateinit var btnRefresh: Button; private lateinit var btnSwitch: Button; private lateinit var btnClose: Button

    private var historyList = ArrayList<String>() // 存储格式: "别名|URL" 或 "URL"
    private lateinit var historyAdapter: ArrayAdapter<String>
    private val tabs = ArrayList<WebView>() 
    private var currentTabIndex = -1 
    private val MONITOR_CHANNEL_ID = "monitor_channel"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createNotificationChannel()
        checkNotificationPermission()
        initViews()
        loadHistory()
        setupHistoryList()
        setupListeners()
    }

    private fun initViews() {
        etAlias = findViewById(R.id.etAlias)
        etUrl = findViewById(R.id.etUrl)
        etPort = findViewById(R.id.etPort)
        btnGo = findViewById(R.id.btnGo)
        listViewHistory = findViewById(R.id.listViewHistory)
        layoutHome = findViewById(R.id.layoutHome)
        webviewContainer = findViewById(R.id.webviewContainer)
        bottomBar = findViewById(R.id.bottomBar)
        btnHome = findViewById(R.id.btnHome); btnRefresh = findViewById(R.id.btnRefresh); btnSwitch = findViewById(R.id.btnSwitch); btnClose = findViewById(R.id.btnClose)
    }

    private fun setupListeners() {
        btnGo.setOnClickListener {
            val alias = etAlias.text.toString().trim().ifEmpty { "未命名" }
            val url = etUrl.text.toString().trim()
            val port = etPort.text.toString().trim()
            
            if (url.isEmpty()) { Toast.makeText(this, "请输入网址", Toast.LENGTH_SHORT).show(); return@setOnClickListener }

            var finalUrl = url
            if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) finalUrl = "http://$finalUrl"
            if (port.isNotEmpty() && !finalUrl.substringAfterLast(":").all { it.isDigit() }) finalUrl = "$finalUrl:$port"

            // 保存格式: Alias|URL
            val historyItem = "$alias|$finalUrl"
            addToHistory(historyItem)
            
            // 启动保活服务 (模仿 Termux)
            startKeepAliveService(alias, finalUrl)
            
            // 打开页面
            createNewTab(finalUrl, alias)
        }
        btnHome.setOnClickListener { showHomeScreen() }
        btnRefresh.setOnClickListener { if (currentTabIndex >= 0) tabs[currentTabIndex].reload() }
        btnClose.setOnClickListener { closeCurrentTab() }
        btnSwitch.setOnClickListener { showSwitchTabDialog() }
    }

    private fun startKeepAliveService(alias: String, url: String) {
        val intent = Intent(this, KeepAliveService::class.java)
        intent.putExtra("ALIAS", alias)
        intent.putExtra("URL", url)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun createNewTab(url: String, alias: String) {
        val newWebView = WebView(this)
        newWebView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setupWebViewSettings(newWebView, alias)
        newWebView.tag = alias // 把别名存到 tag 里方便取用
        tabs.add(newWebView)
        webviewContainer.addView(newWebView)
        newWebView.loadUrl(url)
        switchToTab(tabs.size - 1)
    }

    private fun setupWebViewSettings(webView: WebView, alias: String) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidMonitor")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean { view?.loadUrl(url ?: ""); return true }

            // --- 核心：自动登录逻辑 ---
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                // 1. 先查有没有保存过密码
                val savedCreds = getSavedCredentials(host ?: "")
                if (savedCreds != null) {
                    // 有存档，直接自动登录
                    handler?.proceed(savedCreds.first, savedCreds.second)
                } else {
                    // 没存档，弹窗询问
                    val layout = LinearLayout(this@MainActivity); layout.orientation = LinearLayout.VERTICAL; layout.setPadding(50, 40, 50, 10)
                    val etUser = EditText(this@MainActivity); etUser.hint = "用户名"; layout.addView(etUser)
                    val etPass = EditText(this@MainActivity); etPass.hint = "密码"; etPass.inputType = 129; layout.addView(etPass)
                    
                    AlertDialog.Builder(this@MainActivity).setTitle("验证并保存").setView(layout).setCancelable(false)
                        .setPositiveButton("登录") { _, _ ->
                            val user = etUser.text.toString()
                            val pass = etPass.text.toString()
                            // 保存密码到本地
                            saveCredentials(host ?: "", user, pass)
                            handler?.proceed(user, pass)
                        }
                        .setNegativeButton("取消") { _, _ -> handler?.cancel() }
                        .show()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectMonitorScript(view)
                // 页面加载完毕，发送登录成功通知
                sendNotification("运行状态", "别名: $alias\n状态: 正常运行中")
            }
        }
    }

    // --- 密码保存/读取逻辑 ---
    private fun saveCredentials(host: String, user: String, pass: String) {
        val prefs = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString(host, "$user:$pass").apply()
    }

    private fun getSavedCredentials(host: String): Pair<String, String>? {
        val prefs = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
        val saved = prefs.getString(host, null) ?: return null
        val parts = saved.split(":")
        if (parts.size == 2) return Pair(parts[0], parts[1])
        return null
    }

    // --- 历史记录 (Alias|URL) ---
    private fun setupHistoryList() {
        historyAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, historyList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val raw = getItem(position) ?: ""
                // 显示时，把 "Alias|URL" 变成好看的格式
                if (raw.contains("|")) {
                    val parts = raw.split("|")
                    view.text = "${parts[0]}\n${parts[1]}"
                } else {
                    view.text = raw
                }
                view.textSize = 14f
                return view
            }
        }
        listViewHistory.adapter = historyAdapter
        listViewHistory.setOnItemClickListener { _, _, position, _ ->
            val item = historyList[position]
            if (item.contains("|")) {
                val parts = item.split("|")
                startKeepAliveService(parts[0], parts[1])
                createNewTab(parts[1], parts[0])
            } else {
                createNewTab(item, "未命名")
            }
        }
        listViewHistory.setOnItemLongClickListener { _, _, position, _ ->
            historyList.removeAt(position); historyAdapter.notifyDataSetChanged(); saveHistory(); true
        }
    }

    private fun addToHistory(item: String) {
        if (historyList.contains(item)) historyList.remove(item)
        historyList.add(0, item); historyAdapter.notifyDataSetChanged(); saveHistory()
    }
    private fun saveHistory() { getPreferences(Context.MODE_PRIVATE).edit().putStringSet("HISTORY_V2", HashSet(historyList)).apply() }
    private fun loadHistory() {
        val set = getPreferences(Context.MODE_PRIVATE).getStringSet("HISTORY_V2", null)
        historyList.clear(); if (set != null) historyList.addAll(set)
    }

    // --- 下面是之前的监控和通知代码，保持不变 ---
    inner class WebAppInterface(private val mContext: Context) {
        @JavascriptInterface
        fun postMessage(alertMessage: String) {
            Handler(Looper.getMainLooper()).post {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastNotifyTime > NOTIFY_COOLDOWN_MS) {
                    lastNotifyTime = currentTime
                    sendNotification("监控报警", alertMessage)
                }
            }
        }
    }
    
    private fun injectMonitorScript(webView: WebView?) {
        val rulesJson = RULES.joinToString(prefix = "[", postfix = "]", separator = ",") { "{key:'${it.keyword}', num:${it.threshold}, msg:'${it.alertMessage}'}" }
        val jsCode = """
            if (!window.isMonitorRunning) {
                window.isMonitorRunning = true;
                setInterval(function() {
                    var bodyText = document.body.innerText || "";
                    var last50Lines = bodyText.split('\n').slice(-50).join('\n');
                    var rules = $rulesJson;
                    for (var i = 0; i < rules.length; i++) {
                        var matches = last50Lines.match(new RegExp(rules[i].key, "g"));
                        if ((matches ? matches.length : 0) >= rules[i].num) {
                            window.AndroidMonitor.postMessage(rules[i].msg); break;
                        }
                    }
                }, $CHECK_INTERVAL_MS);
            }
        """.trimIndent()
        webView?.evaluateJavascript(jsCode, null)
    }

    private fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        for (i in tabs.indices) tabs[i].visibility = View.GONE
        tabs[index].visibility = View.VISIBLE
        currentTabIndex = index
        layoutHome.visibility = View.GONE; webviewContainer.visibility = View.VISIBLE; bottomBar.visibility = View.VISIBLE
        btnSwitch.text = "切换(${tabs.size})"
    }
    private fun closeCurrentTab() {
        if (currentTabIndex == -1) return
        webviewContainer.removeView(tabs[currentTabIndex]); tabs[currentTabIndex].destroy(); tabs.removeAt(currentTabIndex)
        if (tabs.isEmpty()) { currentTabIndex = -1; showHomeScreen() } else { switchToTab(if (currentTabIndex - 1 >= 0) currentTabIndex - 1 else 0) }
    }
    private fun showHomeScreen() { webviewContainer.visibility = View.GONE; bottomBar.visibility = View.GONE; layoutHome.visibility = View.VISIBLE }
    private fun showSwitchTabDialog() {
        if (tabs.isEmpty()) return
        val titles = Array(tabs.size) { i -> "${i+1}. ${tabs[i].tag ?: "页面"}" }
        AlertDialog.Builder(this).setTitle("切换页面").setItems(titles) { _, which -> switchToTab(which) }.show()
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(MONITOR_CHANNEL_ID, "Monitor", NotificationManager.IMPORTANCE_HIGH))
        }
    }
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
    }
    private fun sendNotification(title: String, message: String) {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, MONITOR_CHANNEL_ID).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(title).setContentText(message).setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pendingIntent).setAutoCancel(true)
        try { NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), builder.build()) } catch (e: Exception) {}
    }
    override fun onBackPressed() {
        if (currentTabIndex != -1 && tabs[currentTabIndex].canGoBack()) tabs[currentTabIndex].goBack()
        else if (webviewContainer.visibility == View.VISIBLE) showHomeScreen()
        else moveTaskToBack(true)
    }
}
