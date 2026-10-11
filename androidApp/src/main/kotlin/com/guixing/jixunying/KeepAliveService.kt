package com.guixing.jixunying

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * 手机接微信时的前台服务（1.5.0「手机后台省电」）：通知栏常驻一条「AI集训营 在后台接微信」，系统就不会把应用杀掉，
 * 电脑关机时手机能接着答。类型是 remoteMessaging（官方说明：在设备之间转文字消息）。
 * 只在手机接微信（绑定了、开关开着）时开；在应用打开着的时候启动（后台不许启动前台服务），关掉开关就停。
 * 通知上的「关掉」= 把 设置 → 微信 →「这台手机接微信」关掉。
 */
class KeepAliveService : Service() {
    companion object {
        private const val CHANNEL = "keepalive"
        private const val ID = 1
        private const val ACTION_OFF = "com.guixing.jixunying.WEIXIN_OFF"
        @Volatile var running = false
            private set

        fun start(c: Context) {
            runCatching { c.startForegroundService(Intent(c, KeepAliveService::class.java)) }
        }

        fun stop(c: Context) {
            runCatching { c.stopService(Intent(c, KeepAliveService::class.java)) }
        }

        /** 通知上的第二行跟着微信助理的状态变（电脑在接 / 手机在接 / 连不上）。 */
        fun update(c: Context, status: String) {
            if (!running) return
            runCatching { c.getSystemService(NotificationManager::class.java).notify(ID, build(c, status)) }
        }

        private fun build(c: Context, status: String): Notification {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "后台接微信", NotificationManager.IMPORTANCE_LOW).apply {
                description = "手机接微信时常驻，防止系统把 AI集训营 关掉"
                setShowBadge(false)
            })
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val off = PendingIntent.getService(c, 1, Intent(c, KeepAliveService::class.java).setAction(ACTION_OFF), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("AI集训营 在后台接微信")
                .setContentText(status.ifBlank { "电脑在接时手机只待命；不用时点「关掉」省电" })
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_notify), "关掉", off).build())
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as JxyApp
        if (intent?.action == ACTION_OFF) {
            app.turnOffPhoneWeixin()
            stopSelf()
            return START_NOT_STICKY
        }
        val n = build(this, app.weixinStatus())
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING) else startForeground(ID, n)
            running = true
        } catch (e: Exception) {
            // 系统不让开（比如被杀后在后台重启）：不硬撑，下次打开应用再开
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
