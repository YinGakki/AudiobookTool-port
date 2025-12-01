package com.ncorti.kotlin.template.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alias = intent?.getStringExtra("ALIAS") ?: "Unknown"
        val url = intent?.getStringExtra("URL") ?: "Running..."

        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // 创建一个持续显示的通知，模仿 Termux
        val notification = NotificationCompat.Builder(this, "keep_alive_channel")
            .setContentTitle("Audiobook Tool 运行中")
            .setContentText("$alias ($url)")
            .setSmallIcon(android.R.drawable.stat_sys_download) // 使用系统下载图标，假装在忙
            .setContentIntent(pendingIntent)
            .setOngoing(true) // 禁止用户划掉
            .build()

        // 启动前台服务，这是保活的关键
        startForeground(1, notification)

        return START_STICKY // 如果被系统杀掉，尝试重启
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "keep_alive_channel",
                "Background Service",
                NotificationManager.IMPORTANCE_LOW // 低优先级，不发出声音，默默显示
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
