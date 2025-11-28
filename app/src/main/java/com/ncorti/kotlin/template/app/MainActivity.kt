package com.ncorti.kotlin.template.app // 保持原本的包名不动

// 1. 修改导入部分：去掉 androidx.appcompat...，换成 android.app.Activity
import android.app.Activity 
import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.WebSettings // 补全可能缺失的引用
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
// 注意：这里不要 import androidx.appcompat.app.AppCompatActivity

// 2. 修改继承关系：从 AppCompatActivity 改为 Activity
class MainActivity : Activity() { 

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

        // 下面的逻辑代码完全不用变
        etUrl = findViewById(R.id.etUrl)
        etPort = findViewById(R.id.etPort)
        btnGo = findViewById(R.id.btnGo)
        listView = findViewById(R.id.listViewHistory)
        layoutInput = findViewById(R.id.layoutInput)
        webView = findViewById(R.id.webView)

        setupWebView()
        loadHistory()

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
        if (set != null) {
            historyList.addAll(set)
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