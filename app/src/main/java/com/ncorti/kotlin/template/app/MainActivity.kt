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

    // 控件引用
    private lateinit var layoutHome: LinearLayout
    private lateinit var etUrl: EditText
    private lateinit var etPort: EditText
    private lateinit var btnGo: Button
    private lateinit var listViewHistory: ListView
    
    // 浏览器相关控件
    private lateinit var webviewContainer: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var btnHome: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnSwitch: Button
    private lateinit var btnClose: Button

    // 数据相关
    private var historyList = ArrayList<String>()
    private lateinit var historyAdapter: ArrayAdapter<String>
    
    // 多标签页管理
    private val tabs = ArrayList<WebView>() // 存放所有打开的网页
    private var currentTabIndex = -1 // 当前显示的网页索引

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 初始化所有控件
        initViews()

        // 2. 加载历史记录
        loadHistory()
        setupHistoryList()

        // 3. 按钮点击事件
        setupListeners()
    }

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
                if (!finalUrl.substringAfterLast(":").all { it.isDigit() }) { 
                     finalUrl = "$finalUrl:$port"
                }
            }

            addToHistory(finalUrl)
            createNewTab(finalUrl) // 创建新标签页
        }

        // 底部：主页按钮
        btnHome.setOnClickListener {
            showHomeScreen()
        }

        // 底部：刷新按钮
        btnRefresh.setOnClickListener {
            if (currentTabIndex >= 0 && currentTabIndex < tabs.size) {
                tabs[currentTabIndex].reload()
            }
        }

        // 底部：关闭当前按钮
        btnClose.setOnClickListener {
            closeCurrentTab()
        }

        // 底部：切换标签按钮
        btnSwitch.setOnClickListener {
            showSwitchTabDialog()
        }
    }

    // --- 核心逻辑：多标签页管理 ---

    private fun createNewTab(url: String) {
        // 1. 创建一个新的 WebView
        val newWebView = WebView(this)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        newWebView.layoutParams = params
        
        // 2. 配置 WebView 设置
        setupWebViewSettings(newWebView)

        // 3. 添加到列表和容器
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

        // 更新界面状态
        layoutHome.visibility = View.GONE
        webviewContainer.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        
        updateTabButtonText()
    }

    private fun closeCurrentTab() {
        if (currentTabIndex == -1) return

        // 移除 View 和 列表项
        webviewContainer.removeView(tabs[currentTabIndex])
        tabs[currentTabIndex].destroy() // 销毁防止内存泄漏
        tabs.removeAt(currentTabIndex)

        if (tabs.isEmpty()) {
            // 如果没标签了，回主页
            currentTabIndex = -1
            showHomeScreen()
        } else {
            // 否则显示前一个标签
            val newIndex = if (currentTabIndex - 1 >= 0) currentTabIndex - 1 else 0
            switchToTab(newIndex)
        }
        updateTabButtonText()
    }

    private fun showHomeScreen() {
        // 隐藏浏览器层，显示输入层
        webviewContainer.visibility = View.GONE
        bottomBar.visibility = View.GONE
        layoutHome.visibility = View.VISIBLE
        // 注意：不销毁 tabs，只是隐藏，想回去可以点列表（这里暂未实现从主页回特定Tab，简单起见主页只用于开新Tab）
    }

    private fun showSwitchTabDialog() {
        if (tabs.isEmpty()) return

        // 获取所有页面的标题作为列表
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
        btnSwitch.text = "切换标签(${tabs.size})"
    }

    // --- 浏览器配置 ---

    private fun setupWebViewSettings(webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.databaseEnabled = true
        
        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                view?.loadUrl(url ?: "")
                return true
            }

            // 处理 401 登录弹窗
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                val layout = LinearLayout(this@MainActivity)
                layout.orientation = LinearLayout.VERTICAL
                layout.setPadding(50, 40, 50, 10)

                val etUser = EditText(this@MainActivity)
                etUser.hint = "用户名"
                layout.addView(etUser)

                val etPass =
