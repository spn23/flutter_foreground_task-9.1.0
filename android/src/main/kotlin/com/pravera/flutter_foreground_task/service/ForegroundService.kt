package com.pravera.flutter_foreground_task.service

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.*
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pravera.flutter_foreground_task.FlutterForegroundTaskLifecycleListener
import com.pravera.flutter_foreground_task.RequestCode
import com.pravera.flutter_foreground_task.models.*
import com.pravera.flutter_foreground_task.utils.ForegroundServiceUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ForegroundService : Service() {
    companion object {
        private val TAG = ForegroundService::class.java.simpleName

        private const val ACTION_NOTIFICATION_PRESSED = "onNotificationPressed"
        private const val ACTION_NOTIFICATION_DISMISSED = "onNotificationDismissed"
        private const val ACTION_NOTIFICATION_BUTTON_PRESSED = "onNotificationButtonPressed"
        private const val ACTION_RECEIVE_DATA = "onReceiveData"
        private const val INTENT_DATA_NAME = "intentData"

        private val _isRunningServiceState = MutableStateFlow(false)
        val isRunningServiceState = _isRunningServiceState.asStateFlow()

        private var task: ForegroundTask? = null
        private var taskLifecycleListeners = ForegroundTaskLifecycleListeners()

        fun addTaskLifecycleListener(listener: FlutterForegroundTaskLifecycleListener) {
            taskLifecycleListeners.addListener(listener)
        }

        fun removeTaskLifecycleListener(listener: FlutterForegroundTaskLifecycleListener) {
            taskLifecycleListeners.removeListener(listener)
        }

        fun handleNotificationContentIntent(intent: Intent?) {
            if (intent == null) return
            try {
                val isLaunchIntent = (intent.action == Intent.ACTION_MAIN) &&
                        (intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true)
                if (!isLaunchIntent) return

                val data = intent.getStringExtra(INTENT_DATA_NAME)
                if (data == ACTION_NOTIFICATION_PRESSED) {
                    task?.invokeMethod(data, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, e.message, e)
            }
        }

        fun sendData(data: Any?) {
            if (isRunningServiceState.value) {
                task?.invokeMethod(ACTION_RECEIVE_DATA, data)
            }
        }
    }

    private lateinit var foregroundServiceStatus: ForegroundServiceStatus
    private lateinit var foregroundServiceTypes: ForegroundServiceTypes
    private lateinit var foregroundTaskOptions: ForegroundTaskOptions
    private lateinit var foregroundTaskData: ForegroundTaskData
    private lateinit var notificationOptions: NotificationOptions
    private lateinit var notificationContent: NotificationContent
    private var prevForegroundTaskOptions: ForegroundTaskOptions? = null
    private var prevForegroundTaskData: ForegroundTaskData? = null
    private var prevNotificationOptions: NotificationOptions? = null
    private var prevNotificationContent: NotificationContent? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var isTimeout: Boolean = false

    private var broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            try {
                if (intent.`package` != packageName) return
                val action = intent.action ?: return
                val data = intent.getStringExtra(INTENT_DATA_NAME)
                task?.invokeMethod(action, data)
            } catch (e: Exception) {
                Log.e(TAG, e.message, e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerBroadcastReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isTimeout = false
        loadDataFromPreferences()
        var action = foregroundServiceStatus.action
        val isSetStopWithTaskFlag = ForegroundServiceUtils.isSetStopWithTaskFlag(this)

        if (action == ForegroundServiceAction.API_STOP) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        try {
            if (intent == null) {
                ForegroundServiceStatus.setData(this, ForegroundServiceAction.RESTART)
                foregroundServiceStatus = ForegroundServiceStatus.getData(this)
                action = foregroundServiceStatus.action
            }

            when (action) {
                ForegroundServiceAction.API_START,
                ForegroundServiceAction.API_RESTART -> {
                    startForegroundService()
                    createForegroundTask()
                }
                ForegroundServiceAction.API_UPDATE -> {
                    updateNotification()
                    val prevCallbackHandle = prevForegroundTaskData?.callbackHandle
                    val currCallbackHandle = foregroundTaskData.callbackHandle
                    if (prevCallbackHandle != currCallbackHandle) {
                        createForegroundTask()
                    } else {
                        val prevEventAction = prevForegroundTaskOptions?.eventAction
                        val currEventAction = foregroundTaskOptions.eventAction
                        if (prevEventAction != currEventAction) {
                            updateForegroundTask()
                        }
                    }
                }
                ForegroundServiceAction.REBOOT,
                ForegroundServiceAction.RESTART -> {
                    startForegroundService()
                    createForegroundTask()
                    Log.d(TAG, "The service has been restarted by Android OS.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, e.message, e)
            stopForegroundService()
        }

        return if (isSetStopWithTaskFlag) START_NOT_STICKY else START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        val isTimeout = this.isTimeout
        destroyForegroundTask(isTimeout)
        stopForegroundService()
        unregisterBroadcastReceiver()

        var isCorrectlyStopped = false
        if (::foregroundServiceStatus.isInitialized) {
            isCorrectlyStopped = foregroundServiceStatus.isCorrectlyStopped()
        }
        if (!isCorrectlyStopped && !ForegroundServiceUtils.isSetStopWithTaskFlag(this)) {
            Log.e(TAG, "The service will be restarted after 5 seconds because it wasn't properly stopped.")
            RestartReceiver.setRestartAlarm(this, 5000)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (ForegroundServiceUtils.isSetStopWithTaskFlag(this)) {
            stopSelf()
        } else {
            RestartReceiver.setRestartAlarm(this, 1000)
        }
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        isTimeout = true
        stopForegroundService()
        Log.e(TAG, "The service(id: $startId) timed out and was terminated by the system.")
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        isTimeout = true
        stopForegroundService()
        Log.e(TAG, "The service(id: $startId) timed out and was terminated by the system.")
    }

    private fun loadDataFromPreferences() {
        foregroundServiceStatus = ForegroundServiceStatus.getData(applicationContext)
        foregroundServiceTypes = ForegroundServiceTypes.getData(applicationContext)
        if (::foregroundTaskOptions.isInitialized) prevForegroundTaskOptions = foregroundTaskOptions
        foregroundTaskOptions = ForegroundTaskOptions.getData(applicationContext)
        if (::foregroundTaskData.isInitialized) prevForegroundTaskData = foregroundTaskData
        foregroundTaskData = ForegroundTaskData.getData(applicationContext)
        if (::notificationOptions.isInitialized) prevNotificationOptions = notificationOptions
        notificationOptions = NotificationOptions.getData(applicationContext)
        if (::notificationContent.isInitialized) prevNotificationContent = notificationContent
        notificationContent = NotificationContent.getData(applicationContext)
    }

    private fun registerBroadcastReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(ACTION_NOTIFICATION_BUTTON_PRESSED)
            addAction(ACTION_NOTIFICATION_PRESSED)
            addAction(ACTION_NOTIFICATION_DISMISSED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, intentFilter)
        }
    }

    private fun unregisterBroadcastReceiver() = unregisterReceiver(broadcastReceiver)

    @SuppressLint("WrongConstant", "SuspiciousIndentation")
    private fun startForegroundService() {
        RestartReceiver.cancelRestartAlarm(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createNotificationChannel()
        val serviceId = notificationOptions.serviceId
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(serviceId, notification, foregroundServiceTypes.value)
        } else {
            startForeground(serviceId, notification)
        }
        releaseLockMode()
        acquireLockMode()
        _isRunningServiceState.update { true }
    }

    private fun stopForegroundService() {
        RestartReceiver.cancelRestartAlarm(this)
        releaseLockMode()
        stopForeground(true)
        stopSelf()
        _isRunningServiceState.update { false }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channelId = notificationOptions.channelId
        val channelName = notificationOptions.channelName
        val channelDesc = notificationOptions.channelDescription

        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH // High importance
            ).apply {
                description = channelDesc
                enableVibration(notificationOptions.enableVibration)
                if (!notificationOptions.playSound) setSound(null, null)
                setShowBadge(notificationOptions.showBadge)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val icon = notificationContent.icon
        val iconResId = getIconResId(icon)
        val iconBackgroundColor = icon?.backgroundColorRgb?.let(::getRgbColor)
        val contentIntent = getContentIntent()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = Notification.Builder(this, notificationOptions.channelId)
            builder.setOngoing(true)
            builder.setAutoCancel(false)
            builder.setCategory(Notification.CATEGORY_SERVICE) // ✅ Category
            builder.setShowWhen(notificationOptions.showWhen)
            builder.setSmallIcon(iconResId)
            builder.setContentIntent(contentIntent)
            builder.setContentTitle(notificationContent.title)
            builder.setContentText(notificationContent.text)
            builder.style = Notification.BigTextStyle()
            builder.setVisibility(notificationOptions.visibility)
            builder.setOnlyAlertOnce(notificationOptions.onlyAlertOnce)
            if (iconBackgroundColor != null) builder.setColor(iconBackgroundColor)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            // Do NOT set deleteIntent
            return builder.build().apply {
                flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            }
        } else {
            val builder = NotificationCompat.Builder(this, notificationOptions.channelId)
            builder.setOngoing(true)
            builder.setAutoCancel(false)
            builder.setCategory(NotificationCompat.CATEGORY_SERVICE) // ✅ Category
            builder.setShowWhen(notificationOptions.showWhen)
            builder.setSmallIcon(iconResId)
            builder.setContentIntent(contentIntent)
            builder.setContentTitle(notificationContent.title)
            builder.setContentText(notificationContent.text)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(notificationContent.text))
            builder.setVisibility(notificationOptions.visibility)
            builder.setOnlyAlertOnce(notificationOptions.onlyAlertOnce)
            if (iconBackgroundColor != null) builder.color = iconBackgroundColor
            if (!notificationOptions.enableVibration) builder.setVibrate(longArrayOf(0L))
            if (!notificationOptions.playSound) builder.setSound(null)
            builder.priority = notificationOptions.priority
            return builder.build().apply {
                flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            }
        }
    }

    private fun acquireLockMode() {
        if (foregroundTaskOptions.allowWakeLock && (wakeLock == null || wakeLock?.isHeld == false)) {
            wakeLock = (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ForegroundService:WakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }

        if (foregroundTaskOptions.allowWifiLock && (wifiLock == null || wifiLock?.isHeld == false)) {
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).run {
                createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ForegroundService:WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun releaseLockMode() {
        wakeLock?.takeIf { it.isHeld }?.release()?.also { wakeLock = null }
        wifiLock?.takeIf { it.isHeld }?.release()?.also { wifiLock = null }
    }

    private fun createForegroundTask() {
        destroyForegroundTask()
        task = ForegroundTask(
            context = this,
            serviceStatus = foregroundServiceStatus,
            taskData = foregroundTaskData,
            taskEventAction = foregroundTaskOptions.eventAction,
            taskLifecycleListener = taskLifecycleListeners
        )
    }

    private fun updateForegroundTask() {
        task?.update(taskEventAction = foregroundTaskOptions.eventAction)
    }

    private fun destroyForegroundTask(isTimeout: Boolean = false) {
        task?.destroy(isTimeout)
        task = null
    }

    private fun getIconResId(icon: NotificationIcon?): Int {
        try {
            val pm = applicationContext.packageManager
            val packageName = applicationContext.packageName
            val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            return icon?.metaDataName?.let { appInfo.metaData?.getInt(it) } ?: appInfo.icon
        } catch (e: Exception) {
            Log.e(TAG, "getIconResId($icon)", e)
            return 0
        }
    }

    private fun getContentIntent(): PendingIntent {
        val pm = applicationContext.packageManager
        val packageName = applicationContext.packageName
        val intent = pm.getLaunchIntentForPackage(packageName)?.apply {
            putExtra("intentData", ACTION_NOTIFICATION_PRESSED)
            notificationContent.initialRoute?.let { putExtra("route", it) }
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, RequestCode.NOTIFICATION_PRESSED, intent, flags)
    }

    private fun getRgbColor(rgb: String): Int? {
        val rgbSet = rgb.split(",")
        return if (rgbSet.size == 3) Color.rgb(rgbSet[0].toInt(), rgbSet[1].toInt(), rgbSet[2].toInt()) else null
    }

    private fun getTextSpan(text: String, color: Int?): Spannable = if (color != null) SpannableString(text).apply {
        setSpan(ForegroundColorSpan(color), 0, length, 0)
    } else SpannableString(text)
}
