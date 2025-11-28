package com.ncorti.kotlin.template.app // ⚠️这一行非常重要，必须和你的文件路径一致，不要改！

import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
// 注意：这里已经删除了 com.google.gson 的引用

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

        etUrl = findViewById(R.id.etUrl)
        etPort = findViewById(R.id.etPort)
        btnGo = findViewById(R.id.btnGo)
        listView = findViewById(R.id.listViewHistory)
        layoutInput = findViewById(R.id.layoutInput)
        webView = findViewById(R.id.webView)

        setupWebView()
        loadHistory() // 加载历史

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, historyList)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val fullUrl = historyList[position]
            loadUrlIntoWebView(fullUrl)
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            historyList.removeAt(position)
            adapter.notifyDataSetChanged()
            saveHistory()
            true
        }

        btnGo.setOnClickListener {
            val url = etUrl.text.toString().trim()
            val port = etPort.text.toString().trim()
            
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var finalUrl = url
            if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
                finalUrl = "http://$finalUrl"
            }
            if (port.isNotEmpty()) {
                 // 简单的检查，如果url里没有端口才加
                if (!finalUrl.substringAfterLast(":").all { it.isDigit() }) { 
                     finalUrl = "$finalUrl:$port"
                }
            }

            addToHistory(finalUrl)
            loadUrlIntoWebView(finalUrl)
        }
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                view?.loadUrl(url ?: "")
                return true
            }
        }
    }

    private fun loadUrlIntoWebView(url: String) {
        layoutInput.visibility = View.GONE
        listView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    private fun addToHistory(url: String) {
        if (historyList.contains(url)) {
            historyList.remove(url)
        }
        historyList.add(0, url)
        adapter.notifyDataSetChanged()
        saveHistory()
    }

    // 核心修改：不使用Gson，改用安卓原生的 Set 存储
    private fun saveHistory() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val editor = sharedPref.edit()
        val set = HashSet<String>(historyList) // 将List转换为Set
        editor.putStringSet("HISTORY_KEY_SET", set)
        editor.apply()
    }

    // 核心修改：不使用Gson，改用安卓原生的 Set 读取
    private fun loadHistory() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val set = sharedPref.getStringSet("HISTORY_KEY_SET", null)
        historyList.clear()
        if (set != null) {
            historyList.addAll(set)
            // 因为Set是无序的，如果需要排序可以加一行 historyList.sort()，这里暂时保持简单
        }
    }

    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                webView.visibility = View.GONE
                webView.loadUrl("about:blank")
                layoutInput.visibility = View.VISIBLE
                listView.visibility = View.VISIBLE
            }
        } else {
            moveTaskToBack(true) 
        }
    }
}