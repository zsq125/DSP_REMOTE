package com.deepseek.dshremote.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowService

/**
 * R-3：前台服务 / 通知 / 网络回调的**运行时**证据。
 *
 * 本机无 WHPX/Hyper-V ⇒ 模拟器不可用，"5s 内调用 startForeground()" 这类时序断言
 * 光读代码证明不了。Robolectric 在 JVM 上模拟 Android 框架，
 * ShadowService 可观测 startForeground 的实际调用、id 与通知内容。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ConnectionServiceRuntimeTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** 启动服务并让主线程 looper 跑完（模拟系统真的派发了 onStartCommand）。 */
    private fun startService(): ConnectionService {
        val svc = Robolectric.setupService(ConnectionService::class.java)
        svc.onStartCommand(Intent(context, ConnectionService::class.java), 0, 1)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return svc
    }

    @Test fun `onStartCommand 之后 startForeground 被调用`() {
        val svc = startService()
        val shadow: ShadowService = Shadows.shadowOf(svc)
        assertTrue("startForeground 必须被调用（规范要求 5s 内）", shadow.isLastForegroundNotificationAttached)
    }

    @Test fun `前台通知 id 为 NOTIF_ID`() {
        val svc = startService()
        val shadow = Shadows.shadowOf(svc)
        assertEquals(ConnectionService.NOTIF_ID, shadow.lastForegroundNotificationId)
    }

    @Test fun `通知 channel 已创建且为高优先级`() {
        startService()
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch: NotificationChannel? = nm.getNotificationChannel(ConnectionService.CHANNEL_ID)
        assertNotNull("通知 channel 必须创建", ch)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch!!.importance)
    }

    @Test fun `通知 flag 包含 ongoing 不可清除`() {
        val svc = startService()
        val shadow = Shadows.shadowOf(svc)
        val n = shadow.lastForegroundNotification
        assertNotNull("前台通知必须存在", n)
        assertTrue("通知必须 ongoing（setOngoing(true)）", n!!.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test fun `通知优先级字段为 PRIORITY_HIGH`() {
        val svc = startService()
        val n = Shadows.shadowOf(svc).lastForegroundNotification
        assertNotNull(n)
        assertEquals(android.app.Notification.PRIORITY_HIGH, n!!.priority)
    }

    @Test fun `onDestroy 之后释放实例`() {
        val svc = startService()
        svc.onDestroy()
        assertEquals(null, ConnectionService.instance)
    }

    @Test fun `ConnectionService 在 manifest 中注册`() {
        val i = Intent(context, ConnectionService::class.java)
        val resolved = context.packageManager.resolveService(i, 0)
        assertNotNull("ConnectionService 必须在 manifest 中注册", resolved)
    }

    @Test fun `电池优化 Intent 可正确构造`() {
        val pkg = context.packageName
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$pkg")
        }
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, i.action)
        assertEquals("package:$pkg", i.data.toString())
    }

    @Test fun `应用未开启备份 allowBackup=false`() {
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(
            "allowBackup 必须为 false（防备份泄露 token 密文）",
            0,
            appInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
    }

    @Test fun `网络回调已注册`() {
        startService()
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val shadowCm = Shadows.shadowOf(cm)
        // ShadowConnectivityManager 4.13 暴露 networkCallbacks；任一非空即证明已注册
        val callbacks = shadowCm.networkCallbacks
        assertNotNull("ShadowConnectivityManager 应可用", callbacks)
        assertTrue(
            "ConnectivityManager.NetworkCallback 必须注册（网络切换触发重连）",
            callbacks.isNotEmpty(),
        )
    }

    @Test fun `5s 兜底常量与实现一致`() {
        startService()
        assertEquals("规范要求 5s 内 startForeground", 5_000L, ConnectionService.FOREGROUND_DEADLINE_MS)
        // 真机上 5s 时限由系统强制；Robolectric 无法真实计时，
        // 因此这里锁定"startForeground 在 onStartCommand 内同步调用"这一可观测事实。
        val shadow = Shadows.shadowOf(ConnectionService.instance ?: return)
        assertTrue(shadow.isLastForegroundNotificationAttached)
    }

    @Test fun `服务可重复启动而不崩溃`() {
        val svc = Robolectric.setupService(ConnectionService::class.java)
        val i = Intent(context, ConnectionService::class.java)
        svc.onStartCommand(i, 0, 1)
        svc.onStartCommand(i, 0, 2)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(Shadows.shadowOf(svc).isLastForegroundNotificationAttached)
    }
}
