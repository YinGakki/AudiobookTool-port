package com.ncorti.kotlin.template.app // 保持原本包名

import android.app.Activity
import android.app.AlertDialog // 引入原生对话框
import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager // 引入Cookie管理器
import android.webkit.HttpAuthHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*

class MainActivity : Activity() {

    private lateinit var etUrl: EditText
    private lateinit var etPort: EditText
    private lateinit var btnGo: Button
    private lateinit var listView: ListView
    private lateinit var layoutInput: View
    private lateinit var webView: WebView
    private lateinit var tvHistoryLabel: TextView // 新增这个控件引用
    
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
        tvHistoryLabel = findViewById(R.id.tvHistoryLabel)

        setupWebView()
        loadHistory()

        // 稍微美化一下ListView的显示内容
        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, historyList) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setPadding(50, 40, 40, 40) // 增加列表项的内边距，看起来更舒服
                view.textSize = 16f
                return view
            }
        }
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            loadUrlIntoWebView(historyList[position])
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val urlToRemove = historyList[position]
            AlertDialog.Builder(this)
                .setTitle("Delete History")
                .setMessage("Remove $urlToRemove?")
                .setPositiveButton("Yes") { _, _ ->
                    historyList.removeAt(position)
                    adapter.notifyDataSetChanged()
                    saveHistory()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        btnGo.setOnClickListener {
            val url = etUrl.text.toString().trim()
            val port = etPort.text.toString().trim()
            
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter IP or Domain", Toast.LENGTH_SHORT).show()
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
        settings.databaseEnabled = true
        
        // 【关键修复】开启 Cookie，保证登录状态不丢失
        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                view?.loadUrl(url ?: "")
                return true
            }

            // 【关键修复】处理 HTTP Basic Auth (弹窗输入账号密码)
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                // 弹出一个原生的输入框
                val layout = LinearLayout(this@MainActivity)
                layout.orientation = LinearLayout.VERTICAL
                layout.setPadding(50, 40, 50, 10)

                val etUser = EditText(this@MainActivity)
                etUser.hint = "Username"
                layout.addView(etUser)

                val etPass = EditText(this@MainActivity)
                etPass.hint = "Password"
                etPass.inputType = 129 // textPassword
                layout.addView(etPass)

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Authentication Required")
                    .setView(layout)
                    .setCancelable(false)
                    .setPositiveButton("Login") { _, _ ->
                        val user = etUser.text.toString()
                        val pass = etPass.text.toString()
                        // 将账号密码传回给 WebView 进行验证
                        handler?.proceed(user, pass)
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        handler?.cancel()
                    }
                    .show()
            }
        }
    }

    private fun loadUrlIntoWebView(url: String) {
        layoutInput.visibility = View.GONE
        listView.visibility = View.GONE
        tvHistoryLabel.visibility = View.GONE // 隐藏“History”标题
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
                tvHistoryLabel.visibility = View.VISIBLE
            }
        } else {
            moveTaskToBack(true) 
        }
    }
}