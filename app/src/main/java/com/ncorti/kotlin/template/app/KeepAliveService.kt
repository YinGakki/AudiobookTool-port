package com.ncorti.kotlin.template.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class KeepAliveService : Service() {

    companion object {
        const val ACTION_UPDATE = "com.ncorti.kotlin.template.app.ACTION_UPDATE"
        const val ACTION_STOP = "com.ncorti.kotlin.template.app.ACTION_STOP"
        
        // 静态变量保存当前正在运行的任务状态：TabID -> 显示文本
        // LinkedHashMap 保持插入顺序
        val runningTasks = LinkedHashMap<String, String>()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            runningTasks.clear()
            return START_NOT_STICKY
        }

        // 获取传递过来的 Tab 信息
        val tabId = intent?.getStringExtra("TAB_ID")
        val tabText = intent?.getStringExtra("TAB_TEXT")
        val remove = intent?.getBooleanExtra("REMOVE", false) ?: false

        if (tabId != null) {
            if (remove) {
                runningTasks.remove(tabId)
            } else if (tabText != null) {
                runningTasks[tabId] = tabText
            }
        }

        updateNotification()

        return START_STICKY
    }

    private fun updateNotification() {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // 使用 InboxStyle 支持多行文本
        val inboxStyle = NotificationCompat.InboxStyle()
        val title = if (runningTasks.isEmpty()) "Audiobook Tool 待机中" else "Audiobook Tool 运行中"
        
        // 将所有任务添加到通知行
        runningTasks.values.forEach { line ->
            inboxStyle.addLine(line)
        }
        
        // 摘要文本
        inboxStyle.setSummaryText("监控任务: ${runningTasks.size}")

        val notificationBuilder = NotificationCompat.Builder(this, "keep_alive_channel")
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setStyle(inboxStyle) // 设置多行样式
            .setOnlyAlertOnce(true) // 更新内容时不重新弹窗/震动

        // 如果没有任务运行，只显示默认文本
        if (runningTasks.isEmpty()) {
            notificationBuilder.setContentText("暂无后台监控任务")
        } else {
            // 设置第一行文本作为setContentText以兼容旧设备
            notificationBuilder.setContentText(runningTasks.values.first())
        }

        startForeground(1, notificationBuilder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "keep_alive_channel",
                "Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
