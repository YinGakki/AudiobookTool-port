package com.ncorti.kotlin.template.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*

class MainActivity : Activity() {

    // --- 界面控件变量 ---
    private lateinit var layoutHome: LinearLayout
    private lateinit var etUrl: EditText
    private lateinit var etPort: EditText
    private lateinit var btnGo: Button
    private lateinit var listViewHistory: ListView
    
    // 浏览器区域控件
    private lateinit var webviewContainer: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var btnHome: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnSwitch: Button
    private lateinit var btnClose: Button

    // --- 数据变量 ---
    private var historyList = ArrayList<String>()
    private lateinit var historyAdapter: ArrayAdapter<String>
    
    // 多标签页管理列表
    private val tabs = ArrayList<WebView>() 
    private var currentTabIndex = -1 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 初始化控件
        initViews()

        // 2. 加载和配置历史记录
        loadHistory()
        setupHistoryList()

        // 3. 设置按钮监听事件
        setupListeners()
    }

    // --- 初始化部分 ---
    private fun initViews() {
        layoutHome = findViewById(R.id.layoutHome)
        etUrl = findViewById(R.id.etUrl)
        etPort = findViewById(R.id.etPort)
        btnGo = findViewById(R.id.btnGo)
        listViewHistory = findViewById(R.id.listViewHistory)
        
        webviewContainer = findViewById(R.id.webviewContainer)
        bottomBar = findViewById(R.id.bottomBar)
        btnHome = findViewById(R.id.btnHome)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnClose = findViewById(R.id.btnClose)
    }

    private fun setupListeners() {
        // "开始访问" 按钮
        btnGo.setOnClickListener {
            val url = etUrl.text.toString().trim()
            val port = etPort.text.toString().trim()
            
            if (url.isEmpty()) {
                Toast.makeText(this, "请输入网址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var finalUrl = url
            if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
                finalUrl = "http://$finalUrl"
            }
            if (port.isNotEmpty()) {
                // 如果URL末尾没有端口号，才拼接端口
                if (!finalUrl.substringAfterLast(":").all { it.isDigit() }) { 
                     finalUrl = "$finalUrl:$port"
                }
            }

            addToHistory(finalUrl)
            createNewTab(finalUrl) // 创建新标签页并打开
        }

        // 底部栏：主页
        btnHome.setOnClickListener {
            showHomeScreen()
        }

        // 底部栏：刷新
        btnRefresh.setOnClickListener {
            if (currentTabIndex >= 0 && currentTabIndex < tabs.size) {
                tabs[currentTabIndex].reload()
            }
        }

        // 底部栏：关闭当前
        btnClose.setOnClickListener {
            closeCurrentTab()
        }

        // 底部栏：切换标签
        btnSwitch.setOnClickListener {
            showSwitchTabDialog()
        }
    }

    // --- 多标签页管理逻辑 ---

    private fun createNewTab(url: String) {
        // 1. 动态创建一个 WebView
        val newWebView = WebView(this)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        newWebView.layoutParams = params
        
        // 2. 配置 WebView (支持JS、弹窗登录等)
        setupWebViewSettings(newWebView)

        // 3. 添加到容器和列表
        tabs.add(newWebView)
        webviewContainer.addView(newWebView)
        
        // 4. 加载网址
        newWebView.loadUrl(url)

        // 5. 切换到这个新页面
        switchToTab(tabs.size - 1)
    }

    private fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return

        // 隐藏所有 WebView
        for (i in tabs.indices) {
            tabs[i].visibility = View.GONE
        }

        // 显示选中的 WebView
        tabs[index].visibility = View.VISIBLE
        currentTabIndex = index

        // 切换界面状态到“浏览器模式”
        layoutHome.visibility = View.GONE
        webviewContainer.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        
        updateTabButtonText()
    }

    private fun closeCurrentTab() {
        if (currentTabIndex == -1) return

        // 从界面移除
        webviewContainer.removeView(tabs[currentTabIndex])
        tabs[currentTabIndex].destroy() // 销毁防内存泄漏
        tabs.removeAt(currentTabIndex)

        if (tabs.isEmpty()) {
            // 如果没页面了，回主页
            currentTabIndex = -1
            showHomeScreen()
        } else {
            // 否则显示前一个页面
            val newIndex = if (currentTabIndex - 1 >= 0) currentTabIndex - 1 else 0
            switchToTab(newIndex)
        }
        updateTabButtonText()
    }

    private fun showHomeScreen() {
        // 只是隐藏浏览器层，显示输入层，不销毁页面
        webviewContainer.visibility = View.GONE
        bottomBar.visibility = View.GONE
        layoutHome.visibility = View.VISIBLE
    }

    private fun showSwitchTabDialog() {
        if (tabs.isEmpty()) return

        // 获取所有页面的标题
        val titles = Array(tabs.size) { i ->
            val title = tabs[i].title ?: "加载中..."
            val url = tabs[i].url ?: ""
            "${i + 1}. $title\n$url"
        }

        AlertDialog.Builder(this)
            .setTitle("切换页面")
            .setItems(titles) { _, which ->
                switchToTab(which)
            }
            .show()
    }

    private fun updateTabButtonText() {
        btnSwitch.text = "切换(${tabs.size})"
    }

    // --- WebView 配置 (含登录认证支持) ---

    private fun setupWebViewSettings(webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.databaseEnabled = true
        
        // 开启 Cookie
        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                view?.loadUrl(url ?: "")
                return true
            }

            // 处理 401 Authentication (输入账号密码弹窗)
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                val layout = LinearLayout(this@MainActivity)
                layout.orientation = LinearLayout.VERTICAL
                layout.setPadding(50, 40, 50, 10)

                val etUser = EditText(this@MainActivity)
                etUser.hint = "用户名"
                layout.addView(etUser)

                val etPass = EditText(this@MainActivity)
                etPass.hint = "密码"
                etPass.inputType = 129 // textPassword
                layout.addView(etPass)

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("需要身份验证")
                    .setView(layout)
                    .setCancelable(false)
                    .setPositiveButton("登录") { _, _ ->
                        handler?.proceed(etUser.text.toString(), etPass.text.toString())
                    }
                    .setNegativeButton("取消") { _, _ ->
                        handler?.cancel()
                    }
                    .show()
            }
        }
    }

    // --- 历史记录列表逻辑 ---

    private fun setupHistoryList() {
        historyAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, historyList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.textSize = 14f // 设置字体大小
                return view
            }
        }
        listViewHistory.adapter = historyAdapter

        // 点击历史记录
        listViewHistory.setOnItemClickListener { _, _, position, _ ->
            createNewTab(historyList[position])
        }

        // 长按删除
        listViewHistory.setOnItemLongClickListener { _, _, position, _ ->
            AlertDialog.Builder(this)
                .setTitle("删除记录")
                .setMessage("确定删除 ${historyList[position]} 吗？")
                .setPositiveButton("删除") { _, _ ->
                    historyList.removeAt(position)
                    historyAdapter.notifyDataSetChanged()
                    saveHistory()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
    }

    private fun addToHistory(url: String) {
        if (historyList.contains(url)) historyList.remove(url)
        historyList.add(0, url)
        historyAdapter.notifyDataSetChanged()
        saveHistory()
    }

    private fun saveHistory() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val editor = sharedPref.edit()
        val set = HashSet<String>(historyList)
        editor.putStringSet("HISTORY_KEY_SET", set)
        editor.apply()
    }

    private fun loadHistory() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val set = sharedPref.getStringSet("HISTORY_KEY_SET", null)
        historyList.clear()
        if (set != null) historyList.addAll(set)
    }

    // --- 返回键处理 ---
    override fun onBackPressed() {
        // 1. 如果当前页面能后退，就网页后退
        if (currentTabIndex != -1 && tabs[currentTabIndex].canGoBack()) {
            tabs[currentTabIndex].goBack()
        } 
        // 2. 否则如果浏览器显示着，就回到主页 (保留标签页后台运行)
        else if (webviewContainer.visibility == View.VISIBLE) {
            showHomeScreen()
        } 
        // 3. 已经在主页了，将 App 挂起至后台
        else {
            moveTaskToBack(true)
        }
    }
}
