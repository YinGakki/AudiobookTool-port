package com.ncorti.kotlin.template.app

import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson // 如果报错，见下方依赖说明
import com.google.gson.reflect.TypeToken

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var etPort: EditText
    private lateinit var btnGo: Button
    private lateinit var listView: ListView
    private lateinit var layoutInput: LinearLayout
    private lateinit var webView: WebView
    
    private var historyList = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 初始化控件
        etUrl = findViewById(R.id.etUrl)
        etPort = findViewById(R.id.etPort)
        btnGo = findViewById(R.id.btnGo)
        listView = findViewById(R.id.listViewHistory)
        layoutInput = findViewById(R.id.layoutInput)
        webView = findViewById(R.id.webView)

        // 2. 配置 WebView
        setupWebView()

        // 3. 加载历史记录
        loadHistory()

        // 4. 设置列表适配器
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, historyList)
        listView.adapter = adapter

        // 5. 点击列表项：自动填入并跳转
        listView.setOnItemClickListener { _, _, position, _ ->
            val fullUrl = historyList[position]
            loadUrlIntoWebView(fullUrl)
        }

        // 6. 长按列表项：删除
        listView.setOnItemLongClickListener { _, _, position, _ ->
            historyList.removeAt(position)
            adapter.notifyDataSetChanged()
            saveHistory()
            true
        }

        // 7. 点击进入按钮
        btnGo.setOnClickListener {
            val url = etUrl.text.toString().trim()
            var port = etPort.text.toString().trim()
            
            if (url.isEmpty()) {
                Toast.makeText(this, "请输入网址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 简单的地址拼接逻辑
            var finalUrl = url
            if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
                finalUrl = "http://$finalUrl"
            }
            if (port.isNotEmpty()) {
                // 如果url里没写端口，才拼端口
                if (!finalUrl.substringAfterLast(":").all { it.isDigit() }) { 
                     finalUrl = "$finalUrl:$port"
                }
            }

            // 保存到历史并跳转
            addToHistory(finalUrl)
            loadUrlIntoWebView(finalUrl)
        }
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true // 很多现代网页需要这个
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        
        // 关键：防止跳转到系统浏览器
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                view?.loadUrl(url ?: "")
                return true
            }
        }
    }

    private fun loadUrlIntoWebView(url: String) {
        // 隐藏输入框和列表，显示WebView
        layoutInput.visibility = View.GONE
        listView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        
        webView.loadUrl(url)
    }

    private fun addToHistory(url: String) {
        if (historyList.contains(url)) {
            historyList.remove(url) // 移到最前面
        }
        historyList.add(0, url)
        adapter.notifyDataSetChanged()
        saveHistory()
    }

    // 使用 SharedPreferences 保存历史
    private fun saveHistory() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val editor = sharedPref.edit()
        // 这里偷懒用简单的逗号分隔保存，如果你不懂Gson库，可以用这个简单逻辑
        // 实际上推荐导入 Gson 库，但在最简单教程里，我们手写一个简单的转换
        val set = HashSet<String>(historyList)
        editor.putStringSet("HISTORY_KEY", set)
        editor.apply()
    }

    private fun loadHistory() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val set = sharedPref.getStringSet("HISTORY_KEY", null)
        historyList.clear()
        if (set != null) {
            historyList.addAll(set)
        }
    }

    // 处理后退键：如果是网页回退则回退网页，否则回到输入界面，再按才退出
    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                // 退出网页模式，回到输入模式
                webView.visibility = View.GONE
                webView.loadUrl("about:blank") // 停止加载，节省资源，或者保留也行
                layoutInput.visibility = View.VISIBLE
                listView.visibility = View.VISIBLE
            }
        } else {
            // 将 App 移至后台而不是关闭 (实现后台运行)
            moveTaskToBack(true) 
        }
    }
}