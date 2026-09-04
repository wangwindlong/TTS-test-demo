package dev.wangyl.aecttsdemo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 麦克风型前台服务：实验期间保持 app 处于前台服务状态。
 *
 * ColorOS 隐私管控：非前台 app 使用麦克风 1 秒后被系统静音
 * (logcat: "App op 27 missing, silencing record")。
 * adb am start 拉起的 Activity 没有用户交互痕迹，很快被判定退后台，
 * 因此录音必须挂在一个 microphone 类型的前台服务下。
 */
class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "aec_keepalive"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "AEC 实验保活",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "录音实验期间保持麦克风可用" }
        )
        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AEC 实验进行中")
                .setContentText("保持麦克风前台访问（ColorOS 隐私管控要求）")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
}
