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
import java.util.UUID

class MainActivity : Activity() {

    // --- 数据模型 ---
    data class MonitorRule(var keyword: String, var threshold: Int, var alertMessage: String)
    
    // 标签页配置对象
    data class TabConfig(
        val id: String = UUID.randomUUID().toString(),
        var alias: String,
        var url: String,
        var rules: MutableList<MonitorRule> = mutableListOf(), // 每个标签独立的规则
        var isNotifyActive: Boolean = false // 是否显示在通知栏
    )

    // 默认规则模板
    private val DEFAULT_RULES = listOf(
        MonitorRule("Error", 3, "严重错误"),
        MonitorRule("Timeout", 3, "网络超时")
    )

    private val CHECK_INTERVAL_MS = 30000L 
    private val NOTIFY_COOLDOWN_MS = 60000L 
    private var lastNotifyTime = 0L
    private val MONITOR_CHANNEL_ID = "monitor_channel"

    // --- UI 变量 ---
    private lateinit var etAlias: EditText; private lateinit var etUrl: EditText
    private lateinit var btnGo: Button
    private lateinit var listViewHistory: ListView
    private lateinit var layoutHome: LinearLayout
    private lateinit var webviewContainer: FrameLayout
    private lateinit var bottomBar: LinearLayout
    
    // 底部栏按钮
    private lateinit var btnHome: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnSwitch: Button
    private lateinit var btnClose: Button
    private lateinit var btnTabSettings: Button // 新增: 监控设置
    private lateinit var btnToggleNotify: Button // 新增: 通知开关

    // --- 状态管理 ---
    private var historyList = ArrayList<String>() 
    private lateinit var historyAdapter: ArrayAdapter<String>
    
    // 核心: WebView 和 Config 的映射
    private val tabs = ArrayList<WebView>() 
    private val tabConfigs = HashMap<WebView, TabConfig>() // 关联 WebView -> Config
    private var currentTabIndex = -1 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // 确保布局文件包含新按钮，或者代码动态添加
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
        // etPort ... (如果你布局里还有就留着)
        btnGo = findViewById(R.id.btnGo)
        listViewHistory = findViewById(R.id.listViewHistory)
        layoutHome = findViewById(R.id.layoutHome)
        webviewContainer = findViewById(R.id.webviewContainer)
        bottomBar = findViewById(R.id.bottomBar)
        
        btnHome = findViewById(R.id.btnHome)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnClose = findViewById(R.id.btnClose)
        
        // 注意：你需要在 activity_main.xml 的 bottomBar 里添加这两个按钮
        // 如果不想改 XML，可以用代码动态添加，如下：
        if (findViewById<Button>(R.id.btnTabSettings) == null) {
            // 简单的动态添加按钮逻辑 (仅作演示，建议去改 XML)
            btnTabSettings = Button(this).apply { text = "规则"; id = View.generateViewId() }
            btnToggleNotify = Button(this).apply { text = "保活:关"; id = View.generateViewId() }
            bottomBar.addView(btnTabSettings, 1) // 插在中间
            bottomBar.addView(btnToggleNotify, 2)
        } else {
            btnTabSettings = findViewById(R.id.btnTabSettings)
            btnToggleNotify = findViewById(R.id.btnToggleNotify)
        }
    }

    private fun setupListeners() {
        btnGo.setOnClickListener {
            val alias = etAlias.text.toString().trim().ifEmpty { "标签 ${tabs.size + 1}" }
            var url = etUrl.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            if (!url.startsWith("http")) url = "http://$url"

            addToHistory("$alias|$url")
            
            // 创建新配置，复制默认规则
            val newConfig = TabConfig(alias = alias, url = url)
            newConfig.rules.addAll(DEFAULT_RULES.map { it.copy() }) // 深度复制
            
            createNewTab(newConfig)
        }

        btnHome.setOnClickListener { showHomeScreen() }
        btnRefresh.setOnClickListener { getCurrentWebView()?.reload() }
        btnClose.setOnClickListener { closeCurrentTab() }
        btnSwitch.setOnClickListener { showSwitchTabDialog() }

        // --- 新功能: 监控设置 ---
        btnTabSettings.setOnClickListener {
            showMonitorSettingsDialog()
        }

        // --- 新功能: 切换通知保活状态 ---
        btnToggleNotify.setOnClickListener {
            toggleNotificationStatus()
        }
    }

    private fun getCurrentWebView(): WebView? = if (currentTabIndex >= 0) tabs[currentTabIndex] else null
    private fun getCurrentConfig(): TabConfig? = getCurrentWebView()?.let { tabConfigs[it] }

    // --- 核心逻辑: 创建标签页 ---
    private fun createNewTab(config: TabConfig) {
        val newWebView = WebView(this)
        newWebView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        
        // 存储配置
        tabConfigs[newWebView] = config
        
        setupWebViewSettings(newWebView, config)
        tabs.add(newWebView)
        webviewContainer.addView(newWebView)
        newWebView.loadUrl(config.url)
        switchToTab(tabs.size - 1)
    }

    private fun setupWebViewSettings(webView: WebView, config: TabConfig) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        
        // 注入接口，传递 config.alias 以便报警时知道是哪个标签
        webView.addJavascriptInterface(WebAppInterface(this, config.alias), "AndroidMonitor")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean { view?.loadUrl(url ?: ""); return true }
            
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                val savedCreds = getSavedCredentials(host ?: "")
                if (savedCreds != null) {
                    handler?.proceed(savedCreds.first, savedCreds.second)
                } else {
                    // 简化的登录弹窗
                    showAuthDialog(handler, host)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 注入脚本时使用该标签页 独立的规则
                injectMonitorScript(view, config.rules)
            }
        }
    }

    // --- 新功能: 切换当前标签页是否在通知栏显示 ---
    private fun toggleNotificationStatus() {
        val config = getCurrentConfig() ?: return
        config.isNotifyActive = !config.isNotifyActive
        
        updateButtonState(config)
        updateService(config)
    }

    // 更新 Service 中的状态
    private fun updateService(config: TabConfig) {
        val intent = Intent(this, KeepAliveService::class.java)
        intent.action = KeepAliveService.ACTION_UPDATE
        intent.putExtra("TAB_ID", config.id)
        
        if (config.isNotifyActive) {
            // 显示文本: "别名: 运行中"
            intent.putExtra("TAB_TEXT", "${config.alias}: 运行中")
            intent.putExtra("REMOVE", false)
        } else {
            intent.putExtra("REMOVE", true)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateButtonState(config: TabConfig) {
        btnToggleNotify.text = if (config.isNotifyActive) "保活:开" else "保活:关"
        // 可以加个颜色变化
        btnToggleNotify.setTextColor(if (config.isNotifyActive) 0xFF00FF00.toInt() else 0xFFFFFFFF.toInt())
    }

    // --- 新功能: 监控规则设置对话框 ---
    private fun showMonitorSettingsDialog() {
        val config = getCurrentConfig() ?: return
        
        val builder = AlertDialog.Builder(this)
        builder.setTitle("监控规则: ${config.alias}")
        
        // 简单列表显示当前规则
        val rulesStr = config.rules.map { "${it.keyword} (阈值:${it.threshold})" }.toTypedArray()
        
        builder.setMultiChoiceItems(rulesStr, null) { _, _, _ -> } // 仅展示，未实现复杂编辑
        
        // 添加新规则按钮逻辑 (简化版: 只是重置或添加一条测试规则)
        builder.setNeutralButton("添加自定义") { _, _ ->
            showAddRuleDialog(config)
        }
        
        builder.setPositiveButton("确定") { _, _ -> 
             // 重新注入脚本以应用新规则
             injectMonitorScript(getCurrentWebView(), config.rules)
             Toast.makeText(this, "规则已更新", Toast.LENGTH_SHORT).show()
        }
        builder.show()
    }

    private fun showAddRuleDialog(config: TabConfig) {
        val layout = LinearLayout(this); layout.orientation = LinearLayout.VERTICAL; layout.setPadding(50, 40, 50, 10)
        val etKey = EditText(this); etKey.hint = "关键词 (如: Error)"; layout.addView(etKey)
        val etCount = EditText(this); etCount.hint = "阈值 (如: 3)"; etCount.inputType = 2; layout.addView(etCount)
        val etMsg = EditText(this); etMsg.hint = "报警内容"; layout.addView(etMsg)

        AlertDialog.Builder(this).setTitle("添加规则").setView(layout)
            .setPositiveButton("添加") { _, _ ->
                val k = etKey.text.toString()
                val c = etCount.text.toString().toIntOrNull() ?: 1
                val m = etMsg.text.toString().ifEmpty { "发现 $k" }
                if (k.isNotEmpty()) {
                    config.rules.add(MonitorRule(k, c, m))
                    injectMonitorScript(getCurrentWebView(), config.rules) // 立即生效
                }
            }.show()
    }

    // --- JS 注入逻辑 (改为动态规则) ---
    private fun injectMonitorScript(webView: WebView?, rules: List<MonitorRule>) {
        if (webView == null) return
        
        // 将 List<MonitorRule> 转为 JSON 字符串
        val rulesJson = rules.joinToString(prefix = "[", postfix = "]", separator = ",") { 
            "{key:'${it.keyword}', num:${it.threshold}, msg:'${it.alertMessage}'}" 
        }
        
        // JS 代码: 支持动态更新规则 (清除旧定时器，设置新定时器)
        val jsCode = """
            if (window.monitorInterval) clearInterval(window.monitorInterval);
            
            var rules = $rulesJson;
            window.monitorInterval = setInterval(function() {
                var bodyText = document.body.innerText || "";
                var last50Lines = bodyText.split('\n').slice(-50).join('\n');
                
                for (var i = 0; i < rules.length; i++) {
                    var matches = last50Lines.match(new RegExp(rules[i].key, "g"));
                    if ((matches ? matches.length : 0) >= rules[i].num) {
                        window.AndroidMonitor.postMessage(rules[i].msg);
                        // 可以选择 break 避免一次发太多通知
                    }
                }
            }, $CHECK_INTERVAL_MS);
        """.trimIndent()
        
        webView.evaluateJavascript(jsCode, null)
    }

    // --- WebAppInterface: 接收报警 ---
    inner class WebAppInterface(private val mContext: Context, private val tabAlias: String) {
        @JavascriptInterface
        fun postMessage(alertMessage: String) {
            Handler(Looper.getMainLooper()).post {
                val currentTime = System.currentTimeMillis()
                // 简单防抖：每个标签页独立冷却，或者全局冷却
                if (currentTime - lastNotifyTime > NOTIFY_COOLDOWN_MS) {
                    lastNotifyTime = currentTime
                    sendNotification("[$tabAlias] 报警", alertMessage)
                }
            }
        }
    }

    // --- 标签页切换逻辑 ---
    private fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        
        // 隐藏所有
        tabs.forEach { it.visibility = View.GONE }
        
        // 显示选中的
        val selectedTab = tabs[index]
        selectedTab.visibility = View.VISIBLE
        currentTabIndex = index
        
        // 更新 UI 状态
        val config = tabConfigs[selectedTab]
        btnSwitch.text = "切换(${tabs.size})"
        if (config != null) {
            updateButtonState(config)
        }
        
        layoutHome.visibility = View.GONE; webviewContainer.visibility = View.VISIBLE; bottomBar.visibility = View.VISIBLE
    }

    private fun closeCurrentTab() {
        if (currentTabIndex == -1) return
        val webView = tabs[currentTabIndex]
        val config = tabConfigs[webView]
        
        // 如果该标签正在通知栏显示，先移除
        if (config != null && config.isNotifyActive) {
            config.isNotifyActive = false
            updateService(config)
        }
        
        webviewContainer.removeView(webView)
        webView.destroy()
        tabs.removeAt(currentTabIndex)
        tabConfigs.remove(webView)
        
        if (tabs.isEmpty()) { currentTabIndex = -1; showHomeScreen() } 
        else { switchToTab(if (currentTabIndex - 1 >= 0) currentTabIndex - 1 else 0) }
    }

    // --- 辅助方法 (保持不变或微调) ---
    private fun showHomeScreen() { webviewContainer.visibility = View.GONE; bottomBar.visibility = View.GONE; layoutHome.visibility = View.VISIBLE }
    
    private fun showSwitchTabDialog() {
        if (tabs.isEmpty()) return
        val titles = Array(tabs.size) { i -> 
            val conf = tabConfigs[tabs[i]]
            val status = if (conf?.isNotifyActive == true) " [ON]" else ""
            "${i+1}. ${conf?.alias}$status" 
        }
        AlertDialog.Builder(this).setTitle("切换页面").setItems(titles) { _, which -> switchToTab(which) }.show()
    }
    
    // ... 其他 sendNotification, saveHistory, showAuthDialog, createNotificationChannel 等保持原样 ...
    
    // 补全缺失的函数以保证代码完整性
    private fun showAuthDialog(handler: HttpAuthHandler?, host: String?) {
         val layout = LinearLayout(this); layout.orientation = LinearLayout.VERTICAL; layout.setPadding(50, 40, 50, 10)
         val etUser = EditText(this); etUser.hint = "用户名"; layout.addView(etUser)
         val etPass = EditText(this); etPass.hint = "密码"; etPass.inputType = 129; layout.addView(etPass)
         AlertDialog.Builder(this).setTitle("登录验证").setView(layout).setCancelable(false)
             .setPositiveButton("登录") { _, _ ->
                 val user = etUser.text.toString(); val pass = etPass.text.toString()
                 saveCredentials(host ?: "", user, pass)
                 handler?.proceed(user, pass)
             }
             .setNegativeButton("取消") { _, _ -> handler?.cancel() }.show()
    }
    private fun saveCredentials(host: String, user: String, pass: String) { getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).edit().putString(host, "$user:$pass").apply() }
    private fun getSavedCredentials(host: String): Pair<String, String>? {
        val s = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).getString(host, null) ?: return null
        val p = s.split(":"); return if(p.size==2) Pair(p[0], p[1]) else null
    }
    private fun addToHistory(item: String) { if(historyList.contains(item)) historyList.remove(item); historyList.add(0, item); historyAdapter.notifyDataSetChanged(); saveHistory() }
    private fun saveHistory() { getPreferences(Context.MODE_PRIVATE).edit().putStringSet("HISTORY_V2", HashSet(historyList)).apply() }
    private fun loadHistory() { val set = getPreferences(Context.MODE_PRIVATE).getStringSet("HISTORY_V2", null); historyList.clear(); if(set!=null) historyList.addAll(set) }
    private fun setupHistoryList() {
        historyAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, historyList)
        listViewHistory.adapter = historyAdapter
        listViewHistory.setOnItemClickListener { _, _, position, _ ->
            val item = historyList[position]
            val parts = if(item.contains("|")) item.split("|") else listOf("未命名", item)
            val newConfig = TabConfig(alias = parts[0], url = parts[1]); newConfig.rules.addAll(DEFAULT_RULES)
            createNewTab(newConfig)
        }
    }
    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(MONITOR_CHANNEL_ID, "Monitor", NotificationManager.IMPORTANCE_HIGH)) }
    private fun checkNotificationPermission() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101) }
    private fun sendNotification(title: String, message: String) {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, MONITOR_CHANNEL_ID).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(title).setContentText(message).setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pendingIntent).setAutoCancel(true)
        try { NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), builder.build()) } catch (e: Exception) {}
    }
}
