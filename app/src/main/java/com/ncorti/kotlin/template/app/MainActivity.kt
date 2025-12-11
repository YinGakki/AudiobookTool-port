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

// --- 数据模型 ---
data class MonitorRule(var keyword: String, var threshold: Int, var alertMessage: String)
        
// 标签页配置对象
data class TabConfig(
    val id: String = UUID.randomUUID().toString(),
    var alias: String,
    var url: String,
    var rules: MutableList<MonitorRule> = mutableListOf(), // 每个标签独立的规则
    var isNotifyActive: Boolean = false, // 是否显示在通知栏
    var isPinned: Boolean = false, // 是否固定标签页
    var checkInterval: Long = 30000L, // 个性化检查间隔
    var appName: String = alias // 关联的应用名称
)

class MainActivity : Activity() {
    

    // 默认规则模板
    private val DEFAULT_RULES = listOf(
        MonitorRule("Error", 3, "严重错误"),
        MonitorRule("Timeout", 3, "网络超时"),
        MonitorRule("Exception", 3, "程序异常"),
        MonitorRule("失败", 3, "操作失败报警")
    )

    private val CHECK_INTERVAL_MS = 30000L 
    private val NOTIFY_COOLDOWN_MS = 60000L 
    private var lastNotifyTime = 0L
    private val MONITOR_CHANNEL_ID = "monitor_channel"

    // --- UI 变量 ---
    private lateinit var etAlias: EditText
    private lateinit var etUrl: EditText
    // private lateinit var etPort: EditText // 已移除
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
    private lateinit var btnTabSettings: Button
    private lateinit var btnToggleNotify: Button
    private lateinit var btnPinTab: Button 

    // --- 状态管理 ---
    private var historyList = ArrayList<String>() 
    private lateinit var historyAdapter: ArrayAdapter<String>
    
    // 核心: WebView 和 Config 的映射
    private val tabs = ArrayList<WebView>() 
    private val tabConfigs = HashMap<WebView, TabConfig>() // 关联 WebView -> Config
    private var currentTabIndex = -1 

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
        // etPort 已被移除，不再初始化
        btnGo = findViewById(R.id.btnGo)
        listViewHistory = findViewById(R.id.listViewHistory)
        layoutHome = findViewById(R.id.layoutHome)
        webviewContainer = findViewById(R.id.webviewContainer)
        bottomBar = findViewById(R.id.bottomBar)
        
        btnHome = findViewById(R.id.btnHome)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnClose = findViewById(R.id.btnClose)
        
        // 尝试获取新按钮，如果 XML 中没有添加，则动态创建以免崩溃（建议在XML中添加）
        val existingBtnSettings = findViewById<Button>(R.id.btnTabSettings)
        if (existingBtnSettings == null) {
            btnTabSettings = Button(this).apply { text = "规则"; id = View.generateViewId() }
            btnToggleNotify = Button(this).apply { text = "保活:关"; id = View.generateViewId() }
            btnPinTab = Button(this).apply { text = "固定"; id = View.generateViewId() }
            // 简单插入到布局中，防止空指针
            if (bottomBar.childCount >= 2) {
                bottomBar.addView(btnTabSettings, 1)
                bottomBar.addView(btnToggleNotify, 2)
                bottomBar.addView(btnPinTab, 3)
            } else {
                bottomBar.addView(btnTabSettings)
                bottomBar.addView(btnToggleNotify)
                bottomBar.addView(btnPinTab)
            }
        } else {
            btnTabSettings = existingBtnSettings
            btnToggleNotify = findViewById(R.id.btnToggleNotify)
            btnPinTab = findViewById(R.id.btnPinTab)
        }
    }

    private fun setupListeners() {
        btnGo.setOnClickListener {
            val alias = etAlias.text.toString().trim().ifEmpty { "标签 ${tabs.size + 1}" }
            var url = etUrl.text.toString().trim()
            // 移除 etPort 逻辑，直接处理 URL
            
            if (url.isEmpty()) return@setOnClickListener
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"

            // 保存到历史 (String格式: Alias|URL)
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

        btnTabSettings.setOnClickListener { showMonitorSettingsDialog() }
        btnToggleNotify.setOnClickListener { toggleNotificationStatus() }
        btnPinTab.setOnClickListener { toggleTabPin() }
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
        
        // 如果是固定标签页，添加到列表前面，否则添加到列表后面
        val insertIndex = if (config.isPinned) {
            // 找到最后一个固定标签页的位置
            tabs.indexOfLast { tabConfigs[it]?.isPinned == true } + 1
        } else {
            tabs.size
        }
        
        tabs.add(insertIndex, newWebView)
        webviewContainer.addView(newWebView)
        newWebView.loadUrl(config.url)
        switchToTab(insertIndex)
    }

    private fun setupWebViewSettings(webView: WebView, config: TabConfig) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        
        // 注入接口，使用appName作为标签别名
        val tabAlias = if (config.appName.isNotEmpty()) config.appName else config.alias
        webView.addJavascriptInterface(WebAppInterface(this, tabAlias), "AndroidMonitor")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean { view?.loadUrl(url ?: ""); return true }
            
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                val savedCreds = getSavedCredentials(host ?: "")
                if (savedCreds != null) {
                    handler?.proceed(savedCreds.first, savedCreds.second)
                } else {
                    showAuthDialog(handler, host)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectMonitorScript(view, config.rules)
            }
        }
    }

    // --- Service 交互 ---
    private fun toggleNotificationStatus() {
        val config = getCurrentConfig() ?: return
        config.isNotifyActive = !config.isNotifyActive
        updateButtonState(config)
        updateService(config)
        
        // 发送状态通知
        val appName = if (config.appName.isNotEmpty()) config.appName else "监控"
        sendNotification("运行状态", "[$appName]正在运行")
    }
    
    private fun toggleTabPin() {
        if (currentTabIndex == -1) return
        val webView = tabs[currentTabIndex]
        val config = tabConfigs[webView]
        
        if (config != null) {
            config.isPinned = !config.isPinned
            
            if (config.isPinned) {
                // 将标签页移动到固定标签页区域的末尾
                tabs.removeAt(currentTabIndex)
                val insertIndex = tabs.indexOfLast { tab -> tabConfigs[tab]?.isPinned == true } + 1
                tabs.add(insertIndex, webView)
                currentTabIndex = insertIndex
                switchToTab(currentTabIndex)
            } else {
                // 如果取消固定，将标签页移动到非固定标签页区域的开头
                tabs.removeAt(currentTabIndex)
                val insertIndex = tabs.indexOfLast { tab -> tabConfigs[tab]?.isPinned == true } + 1
                tabs.add(insertIndex, webView)
                currentTabIndex = insertIndex
                switchToTab(currentTabIndex)
            }
            
            Toast.makeText(this, if (config.isPinned) "标签页已固定" else "标签页已取消固定", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateService(config: TabConfig) {
        val intent = Intent(this, KeepAliveService::class.java)
        intent.action = KeepAliveService.ACTION_UPDATE
        intent.putExtra("TAB_ID", config.id)
        
        if (config.isNotifyActive) {
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
        btnToggleNotify.setTextColor(if (config.isNotifyActive) 0xFF00FF00.toInt() else 0xFFFFFFFF.toInt())
        btnPinTab.text = if (config.isPinned) "取消固定" else "固定"
        btnPinTab.setTextColor(if (config.isPinned) 0xFFFF0000.toInt() else 0xFF000000.toInt())
    }

    // --- 监控规则设置 ---
    private fun showMonitorSettingsDialog() {
        val config = getCurrentConfig() ?: return
        val builder = AlertDialog.Builder(this)
        builder.setTitle("监控规则: ${config.alias}")
        
        // 创建自定义布局，包含监控间隔设置
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)
        
        // 添加应用名称设置
        val etAppName = EditText(this)
        etAppName.hint = "应用名称（用于通知）"
        etAppName.setText(config.appName)
        layout.addView(etAppName)
        
        // 添加检查间隔设置
        val etCheckInterval = EditText(this)
        etCheckInterval.hint = "检查间隔（毫秒，默认30000）"
        etCheckInterval.setText(config.checkInterval.toString())
        etCheckInterval.inputType = 2 // 数字输入
        layout.addView(etCheckInterval)
        
        // 添加规则列表
        val rulesListView = ListView(this)
        val rulesStr = config.rules.map { "${it.keyword} (阈值:${it.threshold})" }.toTypedArray()
        val rulesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rulesStr)
        rulesListView.adapter = rulesAdapter
        rulesListView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 300)
        
        // 添加规则点击删除功能
        rulesListView.setOnItemLongClickListener { _, _, position, _ ->
            AlertDialog.Builder(this)
                .setTitle("删除规则")
                .setMessage("确定要删除此规则吗？")
                .setPositiveButton("确定") { _, _ ->
                    config.rules.removeAt(position)
                    showMonitorSettingsDialog() // 刷新对话框
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
        
        layout.addView(rulesListView)
        
        builder.setView(layout)
        builder.setNeutralButton("添加自定义") { _, _ -> showAddRuleDialog(config) }
        builder.setPositiveButton("确定") { _, _ -> 
            // 保存应用名称
            config.appName = etAppName.text.toString().trim()
            
            // 保存检查间隔
            val intervalText = etCheckInterval.text.toString().trim()
            if (intervalText.isNotEmpty()) {
                config.checkInterval = intervalText.toLongOrNull() ?: CHECK_INTERVAL_MS
            }
            
            // 更新监控脚本
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
                    injectMonitorScript(getCurrentWebView(), config.rules)
                }
            }.show()
    }

    // --- JS 注入 ---
    private fun injectMonitorScript(webView: WebView?, rules: List<MonitorRule>) {
        if (webView == null) return
        val config = tabConfigs[webView] ?: return
        
        val rulesJson = rules.joinToString(prefix = "[", postfix = "]", separator = ",") { 
            "{key:'${it.keyword}', num:${it.threshold}, msg:'${it.alertMessage}'}" 
        }
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
                    }
                }
            }, ${config.checkInterval});
        """.trimIndent()
        webView.evaluateJavascript(jsCode, null)
    }

    inner class WebAppInterface(private val mContext: Context, private val tabAlias: String) {
        @JavascriptInterface
        fun postMessage(alertMessage: String) {
            Handler(Looper.getMainLooper()).post {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastNotifyTime > NOTIFY_COOLDOWN_MS) {
                    lastNotifyTime = currentTime
                    sendNotification("[$tabAlias] 报警", alertMessage)
                }
            }
        }
    }

    // --- Tab 管理 ---
    private fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        tabs.forEach { it.visibility = View.GONE }
        val selectedTab = tabs[index]
        selectedTab.visibility = View.VISIBLE
        currentTabIndex = index
        
        val config = tabConfigs[selectedTab]
        btnSwitch.text = "切换(${tabs.size})"
        if (config != null) updateButtonState(config)
        
        layoutHome.visibility = View.GONE; webviewContainer.visibility = View.VISIBLE; bottomBar.visibility = View.VISIBLE
        
        // 发送状态通知
        if (config != null && config.isNotifyActive) {
            val appName = if (config.appName.isNotEmpty()) config.appName else "监控"
            sendNotification("运行状态", "[$appName]正在运行")
        }
    }

    private fun closeCurrentTab() {
        if (currentTabIndex == -1) return
        val webView = tabs[currentTabIndex]
        val config = tabConfigs[webView]
        
        // 检查标签页是否被固定，如果固定则不允许关闭
        if (config?.isPinned == true) {
            Toast.makeText(this, "固定标签页无法关闭", Toast.LENGTH_SHORT).show()
            return
        }
        
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

    private fun showHomeScreen() { webviewContainer.visibility = View.GONE; bottomBar.visibility = View.GONE; layoutHome.visibility = View.VISIBLE }
    
    private fun showSwitchTabDialog() {
        if (tabs.isEmpty()) return
        
        // 创建自定义列表项布局
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, Array(tabs.size) { i -> "" }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = LinearLayout(context)
                view.orientation = LinearLayout.HORIZONTAL
                view.setPadding(10, 10, 10, 10)
                
                val tab = tabs[position]
                val config = tabConfigs[tab]
                
                // 创建标签页信息文本
                val textView = TextView(context)
                val pinnedMark = if (config?.isPinned == true) " 📌 " else " "
                val status = if (config?.isNotifyActive == true) " [ON]" else ""
                textView.text = "${position+1}.${pinnedMark}${config?.alias}$status"
                textView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textView.gravity = android.view.Gravity.CENTER_VERTICAL
                view.addView(textView)
                
                // 创建操作按钮容器
                val buttonContainer = LinearLayout(context)
                buttonContainer.orientation = LinearLayout.HORIZONTAL
                buttonContainer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                
                // 添加固定/取消固定按钮
                val pinButton = Button(context)
                pinButton.text = if (config?.isPinned == true) "取消固定" else "固定"
                pinButton.textSize = 12f
                pinButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                pinButton.setPadding(5, 2, 5, 2)
                pinButton.setOnClickListener { 
                    // 处理固定/取消固定操作
                    if (config != null) {
                        config.isPinned = !config.isPinned
                        
                        if (config.isPinned) {
                            // 将标签页移动到固定标签页区域的末尾
                            tabs.removeAt(position)
                            val insertIndex = tabs.indexOfLast { tab -> tabConfigs[tab]?.isPinned == true } + 1
                            tabs.add(insertIndex, tab)
                        } else {
                            // 如果取消固定，将标签页移动到非固定标签页区域的开头
                            tabs.removeAt(position)
                            val insertIndex = tabs.indexOfLast { tab -> tabConfigs[tab]?.isPinned == true } + 1
                            tabs.add(insertIndex, tab)
                        }
                        
                        // 刷新对话框
                        notifyDataSetChanged()
                        
                        // 如果是当前选中的标签页，更新按钮状态
                        if (position == currentTabIndex) {
                            updateButtonState(config)
                        }
                    }
                }
                buttonContainer.addView(pinButton)
                
                // 添加设置按钮
                val settingsButton = Button(context)
                settingsButton.text = "设置"
                settingsButton.textSize = 12f
                settingsButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                settingsButton.setPadding(5, 2, 5, 2)
                settingsButton.setOnClickListener { 
                    // 处理设置操作
                    dismissDialog()
                    switchToTab(position)
                    showMonitorSettingsDialog()
                }
                buttonContainer.addView(settingsButton)
                
                // 添加关闭按钮
                val closeButton = Button(context)
                closeButton.text = "关闭"
                closeButton.textSize = 12f
                closeButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                closeButton.setPadding(5, 2, 5, 2)
                closeButton.setBackgroundColor(0xFFFF3B30.toInt())
                closeButton.setTextColor(0xFFFFFFFF.toInt())
                closeButton.setOnClickListener { 
                    // 处理关闭操作
                    if (config?.isPinned == true) {
                        Toast.makeText(context, "固定标签页无法关闭", Toast.LENGTH_SHORT).show()
                    } else {
                        tabs.removeAt(position)
                        webviewContainer.removeView(tab)
                        tab.destroy()
                        tabConfigs.remove(tab)
                        
                        // 刷新对话框
                        notifyDataSetChanged()
                        
                        // 更新当前选中的标签页
                        if (position < currentTabIndex) {
                            currentTabIndex--
                        } else if (position == currentTabIndex) {
                            if (tabs.isEmpty()) {
                                currentTabIndex = -1
                                showHomeScreen()
                                dismissDialog()
                            } else {
                                val newIndex = if (currentTabIndex - 1 >= 0) currentTabIndex - 1 else 0
                                switchToTab(newIndex)
                                dismissDialog()
                            }
                        }
                        
                        if (tabs.isEmpty()) {
                            dismissDialog()
                        }
                    }
                }
                buttonContainer.addView(closeButton)
                
                view.addView(buttonContainer)
                
                return view
            }
            
            // 隐藏对话框的辅助方法
            private fun dismissDialog() {
                val dialog = parent.parent as? AlertDialog
                dialog?.dismiss()
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle("切换页面")
            .setAdapter(adapter) { _, which -> switchToTab(which) }
            .show()
    }

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

    // --- 历史记录 ---
    private fun setupHistoryList() {
        historyAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, historyList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val raw = getItem(position) ?: ""
                if (raw.contains("|")) {
                    val parts = raw.split("|")
                    view.text = "${parts[0]}\n${parts[1]}"
                } else {
                    view.text = raw
                }
                return view
            }
        }
        listViewHistory.adapter = historyAdapter
        listViewHistory.setOnItemClickListener { _, _, position, _ ->
            val item = historyList[position]
            val parts = if(item.contains("|")) item.split("|") else listOf("未命名", item)
            
            // 修正：点击历史记录时，也创建一个新的 TabConfig，而不传递 String
            val alias = parts[0]
            val url = if (parts.size > 1) parts[1] else parts[0]
            
            val newConfig = TabConfig(alias = alias, url = url)
            newConfig.rules.addAll(DEFAULT_RULES.map { it.copy() })
            createNewTab(newConfig)
        }
        listViewHistory.setOnItemLongClickListener { _, _, position, _ ->
            historyList.removeAt(position); historyAdapter.notifyDataSetChanged(); saveHistory(); true
        }
    }

    // --- 杂项 ---
    private fun saveCredentials(host: String, user: String, pass: String) { getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).edit().putString(host, "$user:$pass").apply() }
    private fun getSavedCredentials(host: String): Pair<String, String>? {
        val s = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).getString(host, null) ?: return null
        val p = s.split(":"); return if(p.size==2) Pair(p[0], p[1]) else null
    }
    private fun addToHistory(item: String) { if(historyList.contains(item)) historyList.remove(item); historyList.add(0, item); historyAdapter.notifyDataSetChanged(); saveHistory() }
    private fun saveHistory() { getPreferences(Context.MODE_PRIVATE).edit().putStringSet("HISTORY_V2", HashSet(historyList)).apply() }
    private fun loadHistory() { val set = getPreferences(Context.MODE_PRIVATE).getStringSet("HISTORY_V2", null); historyList.clear(); if(set!=null) historyList.addAll(set) }
    
    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(MONITOR_CHANNEL_ID, "Monitor", NotificationManager.IMPORTANCE_HIGH)) }
    private fun checkNotificationPermission() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101) }
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
