package com.zarz.spotiflac

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import gobackend.Gobackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service to keep downloads running when app is in background.
 * This prevents Android from killing the download process or throttling network.
 * 
 * Note: Android 15+ (API 35+) has a 6-hour timeout for dataSync foreground services.
 * The service will be stopped automatically after 6 hours of cumulative runtime in 24 hours.
 */
class DownloadService : Service() {
    
    companion object {
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "SpotiFLAC:DownloadWakeLock"
        private const val WAKELOCK_RENEW_MS = 30 * 60 * 1000L
        
        const val ACTION_START = "com.zarz.spotiflac.action.START_DOWNLOAD"
        const val ACTION_STOP = "com.zarz.spotiflac.action.STOP_DOWNLOAD"
        const val ACTION_UPDATE_PROGRESS = "com.zarz.spotiflac.action.UPDATE_PROGRESS"
        const val ACTION_START_NATIVE_QUEUE = "com.zarz.spotiflac.action.START_NATIVE_QUEUE"
        const val ACTION_PAUSE_NATIVE_QUEUE = "com.zarz.spotiflac.action.PAUSE_NATIVE_QUEUE"
        const val ACTION_RESUME_NATIVE_QUEUE = "com.zarz.spotiflac.action.RESUME_NATIVE_QUEUE"
        const val ACTION_CANCEL_NATIVE_QUEUE = "com.zarz.spotiflac.action.CANCEL_NATIVE_QUEUE"
        
        const val EXTRA_TRACK_NAME = "track_name"
        const val EXTRA_ARTIST_NAME = "artist_name"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_QUEUE_COUNT = "queue_count"
        const val EXTRA_STATUS = "status"
        const val EXTRA_REQUESTS_JSON = "requests_json"
        const val EXTRA_SETTINGS_JSON = "settings_json"
        private const val NATIVE_WORKER_STATE_FILE = "native_download_worker_state.json"
        private const val NATIVE_WORKER_PROGRESS_FILE = "native_download_worker_progress.json"
        private const val NATIVE_REPLAYGAIN_JOURNAL_FILE = "native_replaygain_journal.json"
        private const val NATIVE_WORKER_CONTRACT_VERSION = NativeDownloadFinalizer.NATIVE_WORKER_CONTRACT_VERSION
        private const val NOTIFICATION_PERCENT_TOTAL = 10_000L
        private val NATIVE_WORKER_STATE_FILE_LOCK = Any()
        private val NATIVE_REPLAYGAIN_JOURNAL_FILE_LOCK = Any()
        
        private var isRunning = false
        
        fun isServiceRunning(): Boolean = isRunning
        
        fun start(context: Context, trackName: String = "", artistName: String = "", queueCount: Int = 0) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TRACK_NAME, trackName)
                putExtra(EXTRA_ARTIST_NAME, artistName)
                putExtra(EXTRA_QUEUE_COUNT, queueCount)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        fun updateProgress(context: Context, trackName: String, artistName: String, progress: Long, total: Long, queueCount: Int, status: String = "downloading") {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_UPDATE_PROGRESS
                putExtra(EXTRA_TRACK_NAME, trackName)
                putExtra(EXTRA_ARTIST_NAME, artistName)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_TOTAL, total)
                putExtra(EXTRA_QUEUE_COUNT, queueCount)
                putExtra(EXTRA_STATUS, status)
            }
            context.startService(intent)
        }

        fun startNativeQueue(context: Context, requestsJson: String, settingsJson: String = "") {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_NATIVE_QUEUE
                putExtra(EXTRA_REQUESTS_JSON, requestsJson)
                putExtra(EXTRA_SETTINGS_JSON, settingsJson)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseNativeQueue(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE_NATIVE_QUEUE
            }
            context.startService(intent)
        }

        fun resumeNativeQueue(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME_NATIVE_QUEUE
            }
            context.startService(intent)
        }

        fun cancelNativeQueue(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_NATIVE_QUEUE
            }
            context.startService(intent)
        }

        fun getNativeWorkerSnapshot(context: Context): String {
            synchronized(NATIVE_WORKER_STATE_FILE_LOCK) {
                val stateFile = File(context.filesDir, NATIVE_WORKER_STATE_FILE)
                if (!stateFile.exists()) {
                    return JSONObject()
                        .put("run_id", "")
                        .put("is_running", false)
                        .put("is_paused", false)
                        .put("total", 0)
                        .put("completed", 0)
                        .put("failed", 0)
                        .put("skipped", 0)
                        .put("items", JSONArray())
                        .toString()
                }
                val state = AtomicFile(stateFile).openRead().bufferedReader(Charsets.UTF_8).use {
                    it.readText()
                }.let { JSONObject(it) }
                val progressFile = File(context.filesDir, NATIVE_WORKER_PROGRESS_FILE)
                if (progressFile.exists()) {
                    try {
                        val progress = AtomicFile(progressFile).openRead().bufferedReader(Charsets.UTF_8).use {
                            it.readText()
                        }.let { JSONObject(it) }
                        if (progress.optString("run_id", "") == state.optString("run_id", "") &&
                            progress.optLong("snapshot_serial", 0L) > state.optLong("snapshot_serial", 0L)
                        ) {
                            mergeNativeWorkerProgressSnapshot(state, progress)
                        }
                    } catch (_: Exception) {
                    }
                }
                return state.toString()
            }
        }

        private fun mergeNativeWorkerProgressSnapshot(state: JSONObject, progress: JSONObject) {
            val dynamicKeys = listOf(
                "is_running",
                "is_paused",
                "total",
                "completed",
                "failed",
                "skipped",
                "current_item_id",
                "message",
                "updated_at",
                "snapshot_serial",
                "item_ids"
            )
            for (key in dynamicKeys) {
                if (progress.has(key)) {
                    state.put(key, progress.get(key))
                }
            }
            if (progress.has("item_delta")) {
                state.put("item_delta", progress.get("item_delta"))
            }
            state.put("snapshot_mode", "compact_with_delta")
        }
    }
    
    private data class NativeDownloadRequest(
        val itemId: String,
        val requestJson: String,
        val trackName: String,
        val artistName: String,
        val itemJson: String
    )

    private data class NativeWorkerItem(
        val itemId: String,
        val trackName: String,
        val artistName: String,
        val itemJson: String = "",
        var status: String = "queued",
        var progress: Double = 0.0,
        var bytesReceived: Long = 0L,
        var bytesTotal: Long = 0L,
        var error: String = "",
        var resultJson: JSONObject? = null
    )

    private data class NativeWorkerCounts(
        val total: Int,
        val completed: Int,
        val failed: Int,
        val skipped: Int
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nativeWorkerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTrackName = ""
    private var currentArtistName = ""
    private var currentStatus = "preparing"
    private var queueCount = 0
    private var lastProgress = 0L
    private var lastTotal = 0L
    private var nativeWorkerRunId = ""
    @Volatile private var nativeWorkerCurrentItemId = ""
    private val nativeWorkerItems = mutableListOf<NativeWorkerItem>()
    private val nativeReplayGainEntries = mutableListOf<JSONObject>()
    private val nativeReplayGainRequestAlbumKeys = mutableMapOf<String, String>()
    private val snapshotWriteLock = Any()
    private val snapshotWriteSerial = AtomicLong(0L)
    private var latestCommittedStateSnapshotSerial = 0L
    private var latestCommittedProgressSnapshotSerial = 0L
    @Volatile private var nativeWorkerPaused = false
    @Volatile private var nativeWorkerCancelRequested = false
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            flushNativeAlbumReplayGainJournalIfComplete()
            writeNativeWorkerSnapshot(
                isRunning = false,
                isPaused = false,
                currentItemId = "",
                message = "Service restart ignored",
                includeItems = true
            )
            stopForegroundService(cancelNativeWorker = false)
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                currentTrackName = intent.getStringExtra(EXTRA_TRACK_NAME) ?: ""
                currentArtistName = intent.getStringExtra(EXTRA_ARTIST_NAME) ?: ""
                currentStatus = "preparing"
                queueCount = intent.getIntExtra(EXTRA_QUEUE_COUNT, 0)
                lastProgress = 0L
                lastTotal = 0L
                startForegroundService()
            }
            ACTION_STOP -> {
                stopForegroundService()
            }
            ACTION_START_NATIVE_QUEUE -> {
                val requestsJson = intent.getStringExtra(EXTRA_REQUESTS_JSON) ?: "[]"
                val settingsJson = intent.getStringExtra(EXTRA_SETTINGS_JSON) ?: "{}"
                startNativeWorker(requestsJson, settingsJson)
            }
            ACTION_PAUSE_NATIVE_QUEUE -> {
                nativeWorkerPaused = true
                var itemIdToCancel = ""
                synchronized(nativeWorkerItems) {
                    val activeItem = nativeWorkerItems.firstOrNull {
                        it.status == "downloading" || it.status == "finalizing"
                    } ?: nativeWorkerItems.firstOrNull {
                        it.itemId == nativeWorkerCurrentItemId && it.status == "queued"
                    }
                    activeItem?.let {
                        it.status = "queued"
                        itemIdToCancel = it.itemId
                    }
                }
                if (itemIdToCancel.isBlank()) itemIdToCancel = nativeWorkerCurrentItemId
                if (itemIdToCancel.isNotBlank()) {
                    try {
                        Gobackend.cancelDownload(itemIdToCancel)
                    } catch (_: Exception) {
                    }
                }
                NativeDownloadFinalizer.cancelActiveWork()
                writeNativeWorkerSnapshotAsync(
                    isRunning = nativeWorkerJob?.isActive == true,
                    isPaused = true,
                    currentItemId = "",
                    message = "Paused",
                    includeItems = true
                )
            }
            ACTION_RESUME_NATIVE_QUEUE -> {
                nativeWorkerPaused = false
                writeNativeWorkerSnapshotAsync(
                    isRunning = nativeWorkerJob?.isActive == true,
                    isPaused = false,
                    currentItemId = "",
                    message = "Resumed",
                    includeItems = true
                )
            }
            ACTION_CANCEL_NATIVE_QUEUE -> {
                nativeWorkerCancelRequested = true
                synchronized(nativeWorkerItems) {
                    for (item in nativeWorkerItems) {
                        if (item.status == "queued" ||
                            item.status == "downloading" ||
                            item.status == "finalizing"
                        ) {
                            item.status = "skipped"
                            try {
                                Gobackend.cancelDownload(item.itemId)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                NativeDownloadFinalizer.cancelActiveWork()
                nativeWorkerJob?.cancel(CancellationException("Native queue cancelled"))
                writeNativeWorkerSnapshotAsync(
                    isRunning = false,
                    isPaused = false,
                    currentItemId = "",
                    message = "Cancelled",
                    includeItems = true
                )
            }
            ACTION_UPDATE_PROGRESS -> {
                currentTrackName = intent.getStringExtra(EXTRA_TRACK_NAME) ?: currentTrackName
                currentArtistName = intent.getStringExtra(EXTRA_ARTIST_NAME) ?: currentArtistName
                val progress = intent.getLongExtra(EXTRA_PROGRESS, 0)
                val total = intent.getLongExtra(EXTRA_TOTAL, 0)
                currentStatus = intent.getStringExtra(EXTRA_STATUS) ?: currentStatus
                queueCount = intent.getIntExtra(EXTRA_QUEUE_COUNT, queueCount)
                lastProgress = progress
                lastTotal = total
                updateNotification(progress, total)
            }
        }
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * Called when the foreground service timeout is reached (Android 15+, API 35+).
     * dataSync services have a 6-hour limit in a 24-hour period.
     * We must call stopSelf() within a few seconds to avoid a crash.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        android.util.Log.w("DownloadService", "Foreground service timeout reached (6 hours limit). Stopping service.")
        
        stopForegroundService()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Download Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun startForegroundService() {
        isRunning = true

        ensureWakeLock()

        val notification = buildNotification(0, 0)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startNativeWorker(requestsJson: String, settingsJson: String) {
        flushNativeAlbumReplayGainJournalIfComplete()
        nativeWorkerRunId = parseNativeWorkerRunId(settingsJson)
        val requests = try {
            parseNativeDownloadRequests(requestsJson)
        } catch (e: Exception) {
            writeNativeWorkerSnapshot(
                isRunning = false,
                isPaused = false,
                currentItemId = "",
                message = "Invalid native queue payload: ${e.message}",
                settingsJson = settingsJson,
                includeItems = true
            )
            stopForegroundService(cancelNativeWorker = false)
            return
        }
        nativeWorkerJob?.cancel(CancellationException("Native queue replaced"))
        nativeWorkerPaused = false
        nativeWorkerCancelRequested = false
        queueCount = requests.size
        synchronized(nativeReplayGainEntries) {
            nativeReplayGainEntries.clear()
        }
        synchronized(nativeReplayGainRequestAlbumKeys) {
            nativeReplayGainRequestAlbumKeys.clear()
            for (request in requests) {
                try {
                    val key = NativeDownloadFinalizer.replayGainAlbumKey(
                        request.requestJson,
                        request.itemJson
                    )
                    if (key.isNotBlank()) {
                        nativeReplayGainRequestAlbumKeys[request.itemId] = key
                    }
                } catch (_: Exception) {
                }
            }
        }
        synchronized(nativeWorkerItems) {
            nativeWorkerItems.clear()
            nativeWorkerItems.addAll(
                requests.map {
                    NativeWorkerItem(
                        itemId = it.itemId,
                        trackName = it.trackName,
                        artistName = it.artistName,
                        itemJson = it.itemJson
                    )
                }
            )
        }
        writeNativeReplayGainJournal()
        currentStatus = "preparing"
        currentTrackName = requests.firstOrNull()?.trackName ?: ""
        currentArtistName = requests.firstOrNull()?.artistName ?: ""
        lastProgress = 0L
        lastTotal = 0L
        startForegroundService()
        writeNativeWorkerSnapshot(
            isRunning = true,
            isPaused = false,
            currentItemId = "",
            message = "Starting",
            settingsJson = settingsJson,
            includeItems = true
        )

        nativeWorkerJob = serviceScope.launch {
            runNativeWorker(requests, settingsJson)
        }
    }

    private fun parseNativeWorkerRunId(settingsJson: String): String {
        return try {
            JSONObject(settingsJson).optString("run_id", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseNativeDownloadRequests(requestsJson: String): List<NativeDownloadRequest> {
        val array = JSONArray(requestsJson)
        val requests = ArrayList<NativeDownloadRequest>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val wrapperVersion = item.optInt("contract_version", -1)
            if (wrapperVersion != NATIVE_WORKER_CONTRACT_VERSION) {
                throw IllegalArgumentException(
                    "unsupported native worker item contract v$wrapperVersion at index $index"
                )
            }
            val itemId = item.optString("item_id").trim()
            val requestJson = item.optString("request_json").trim()
            if (itemId.isEmpty() || requestJson.isEmpty()) {
                continue
            }
            val request = JSONObject(requestJson)
            validateNativeDownloadRequest(itemId, request)
            val itemJson = item.optString("item_json").trim()
            requests.add(
                NativeDownloadRequest(
                    itemId = itemId,
                    requestJson = requestJson,
                    trackName = item.optString("track_name"),
                    artistName = item.optString("artist_name"),
                    itemJson = itemJson
                )
            )
        }
        return requests
    }

    private fun validateNativeDownloadRequest(itemId: String, request: JSONObject) {
        val requestVersion = request.optInt("contract_version", -1)
        if (requestVersion != NATIVE_WORKER_CONTRACT_VERSION) {
            throw IllegalArgumentException(
                "unsupported native worker request contract v$requestVersion for $itemId"
            )
        }

        val requestItemId = request.optString("item_id", "").trim()
        if (requestItemId != itemId) {
            throw IllegalArgumentException("native worker item id mismatch for $itemId")
        }

        val required = listOf("service", "track_name", "quality", "storage_mode")
        val missing = required.filter { request.optString(it, "").trim().isEmpty() }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException(
                "native worker request for $itemId missing fields: ${missing.joinToString()}"
            )
        }
    }

    private suspend fun runNativeWorker(requests: List<NativeDownloadRequest>, settingsJson: String) {
        var completed = 0
        var failed = 0
        try {
            var requestIndex = 0
            while (requestIndex < requests.size) {
                val request = requests[requestIndex]
                while (nativeWorkerPaused && !nativeWorkerCancelRequested) {
                    writeNativeWorkerSnapshot(
                        isRunning = true,
                        isPaused = true,
                        currentItemId = request.itemId,
                        message = "Paused",
                        settingsJson = settingsJson,
                        includeItems = true
                    )
                    delay(500)
                }
                if (nativeWorkerCancelRequested) {
                    break
                }

                var retryCurrentRequest = false
                nativeWorkerCurrentItemId = request.itemId
                currentTrackName = request.trackName
                currentArtistName = request.artistName
                currentStatus = "downloading"
                lastProgress = 0L
                lastTotal = 0L
                updateNotification(0, 0)
                updateNativeWorkerItem(request.itemId) {
                    it.status = "downloading"
                    it.progress = 0.0
                    it.bytesReceived = 0L
                    it.bytesTotal = 0L
                    it.error = ""
                    it.resultJson = null
                }
                writeNativeWorkerSnapshot(
                    isRunning = true,
                    isPaused = false,
                    currentItemId = request.itemId,
                    message = "Downloading",
                    settingsJson = settingsJson,
                    includeItems = true
                )

                var progressJob: Job? = null
                try {
                    Gobackend.initItemProgress(request.itemId)
                    progressJob = serviceScope.launch {
                        while (true) {
                            updateNativeWorkerItemProgress(request.itemId)
                            writeNativeWorkerSnapshot(
                                isRunning = true,
                                isPaused = false,
                                currentItemId = request.itemId,
                                message = "Downloading",
                                settingsJson = settingsJson
                            )
                            delay(1000)
                        }
                    }
                    val response = SafDownloadHandler.handle(this, request.requestJson) { json ->
                        Gobackend.downloadByStrategy(json)
                    }
                    progressJob.cancel()
                    progressJob = null
                    var result = JSONObject(response)
                    if (result.optBoolean("success", false)) {
                        currentStatus = "finalizing"
                        updateNativeWorkerItem(request.itemId) {
                            it.status = "finalizing"
                            it.progress = 0.95
                            it.error = ""
                        }
                        writeNativeWorkerSnapshot(
                            isRunning = true,
                            isPaused = false,
                            currentItemId = request.itemId,
                            message = "Finalizing",
                            settingsJson = settingsJson
                        )
                        result = NativeDownloadFinalizer.finalize(
                            this,
                            request.itemId,
                            request.requestJson,
                            request.itemJson,
                            result
                        ) {
                            nativeWorkerCancelRequested ||
                                nativeWorkerPaused ||
                                nativeWorkerJob?.isActive == false
                        }
                    }
                    if (result.optBoolean("success", false)) {
                        result.optJSONObject("replaygain")?.let { replayGain ->
                            synchronized(nativeReplayGainEntries) {
                                nativeReplayGainEntries.add(JSONObject(replayGain.toString()))
                            }
                        }
                        completed++
                        updateNativeWorkerItem(request.itemId) {
                            it.status = "completed"
                            it.progress = 1.0
                            it.error = ""
                            it.resultJson = result
                        }
                        writeNativeReplayGainJournal()
                        writeNativeAlbumReplayGainIfComplete()
                    } else {
                        val errorType = result.optString("error_type")
                        if (errorType == "cancelled" && nativeWorkerPaused && !nativeWorkerCancelRequested) {
                            updateNativeWorkerItem(request.itemId) {
                                it.status = "queued"
                                it.progress = 0.0
                                it.bytesReceived = 0L
                                it.bytesTotal = 0L
                                it.error = ""
                                it.resultJson = null
                            }
                            writeNativeWorkerSnapshot(
                                isRunning = true,
                                isPaused = true,
                                currentItemId = request.itemId,
                                message = "Paused",
                                settingsJson = settingsJson,
                                includeItems = true
                            )
                            retryCurrentRequest = true
                        } else {
                            failed++
                            updateNativeWorkerItem(request.itemId) {
                                it.status = if (errorType == "cancelled") {
                                    "skipped"
                                } else {
                                    "failed"
                                }
                                it.error = result.optString("error")
                                it.resultJson = result
                            }
                            writeNativeReplayGainJournal()
                        }
                    }
                    if (!retryCurrentRequest) {
                        writeNativeWorkerSnapshot(
                            isRunning = true,
                            isPaused = false,
                            currentItemId = request.itemId,
                            message = if (result.optBoolean("success", false)) "Completed" else "Failed",
                            lastResult = result,
                            settingsJson = settingsJson,
                            includeItems = true
                        )
                    }
                } catch (e: CancellationException) {
                    if (nativeWorkerCancelRequested) {
                        updateNativeWorkerItem(request.itemId) {
                            it.status = "skipped"
                            it.error = "Cancelled"
                        }
                    }
                    throw e
                } catch (e: Exception) {
                    failed++
                    updateNativeWorkerItem(request.itemId) {
                        it.status = "failed"
                        it.error = e.message ?: "Native download failed"
                    }
                    writeNativeReplayGainJournal()
                    writeNativeWorkerSnapshot(
                        isRunning = true,
                        isPaused = false,
                        currentItemId = request.itemId,
                        message = e.message ?: "Native download failed",
                        settingsJson = settingsJson,
                        includeItems = true
                    )
                } finally {
                    progressJob?.cancel()
                    updateNativeWorkerItemProgress(request.itemId)
                    try {
                        Gobackend.clearItemProgress(request.itemId)
                    } catch (_: Exception) {
                    }
                }
                if (!retryCurrentRequest) {
                    if (nativeWorkerCurrentItemId == request.itemId) {
                        nativeWorkerCurrentItemId = ""
                    }
                    requestIndex++
                }
            }
        } finally {
            if (!nativeWorkerCancelRequested) {
                flushNativeAlbumReplayGainJournalIfComplete()
            }
            currentStatus = "finalizing"
            writeNativeWorkerSnapshot(
                isRunning = false,
                isPaused = false,
                currentItemId = "",
                message = if (nativeWorkerCancelRequested) "Cancelled" else "Finished",
                settingsJson = settingsJson,
                includeItems = true
            )
            stopForegroundService(cancelNativeWorker = false)
        }
    }

    private fun writeNativeAlbumReplayGainIfComplete(): Boolean {
        val entries = synchronized(nativeReplayGainEntries) {
            nativeReplayGainEntries.map { JSONObject(it.toString()) }
        }
        if (entries.size <= 1) return true

        val statuses = synchronized(nativeWorkerItems) {
            nativeWorkerItems.associate { it.itemId to it.status }
        }
        val requestKeys = synchronized(nativeReplayGainRequestAlbumKeys) {
            nativeReplayGainRequestAlbumKeys.toMap()
        }
        val eligible = buildEligibleNativeAlbumReplayGain(entries, statuses, requestKeys)
        if (eligible.length() <= 1) {
            return !hasPendingNativeAlbumReplayGainWork(statuses)
        }
        return writeNativeAlbumReplayGainEntries(eligible)
    }

    private fun buildEligibleNativeAlbumReplayGain(
        entries: List<JSONObject>,
        statuses: Map<String, String>,
        requestKeys: Map<String, String>
    ): JSONArray {
        val blockedKeys = mutableSetOf<String>()
        val expectedCompletedByKey = mutableMapOf<String, Int>()
        for ((itemId, key) in requestKeys) {
            when (statuses[itemId]) {
                "completed" -> expectedCompletedByKey[key] = (expectedCompletedByKey[key] ?: 0) + 1
                "failed", "skipped", "queued", "downloading", "finalizing" -> blockedKeys.add(key)
            }
        }

        val entriesByKey = entries.groupBy { it.optString("album_key", "") }
        val eligible = JSONArray()
        for ((key, group) in entriesByKey) {
            if (key.isBlank() || blockedKeys.contains(key) || group.size <= 1) continue
            val expected = expectedCompletedByKey[key] ?: continue
            if (group.size != expected) continue
            for (entry in group) eligible.put(entry)
        }
        return eligible
    }

    private fun writeNativeAlbumReplayGainEntries(eligible: JSONArray): Boolean {
        if (eligible.length() <= 1) return true
        try {
            val result = JSONObject(NativeDownloadFinalizer.writeAlbumReplayGain(this, eligible.toString()))
            return result.optBoolean("success", false)
        } catch (e: Exception) {
            android.util.Log.w("DownloadService", "Native album ReplayGain failed: ${e.message}")
            return false
        }
    }

    private fun hasPendingNativeAlbumReplayGainWork(statuses: Map<String, String>): Boolean {
        return statuses.values.any {
            it == "queued" || it == "downloading" || it == "finalizing"
        }
    }

    private fun writeNativeReplayGainJournal() {
        val requestKeys = synchronized(nativeReplayGainRequestAlbumKeys) {
            nativeReplayGainRequestAlbumKeys.toMap()
        }
        if (requestKeys.isEmpty()) return

        val entries = synchronized(nativeReplayGainEntries) {
            nativeReplayGainEntries.map { JSONObject(it.toString()) }
        }
        val statuses = synchronized(nativeWorkerItems) {
            nativeWorkerItems.associate { it.itemId to it.status }
        }
        synchronized(NATIVE_REPLAYGAIN_JOURNAL_FILE_LOCK) {
            val file = AtomicFile(File(filesDir, NATIVE_REPLAYGAIN_JOURNAL_FILE))
            val existing = readNativeReplayGainJournalLocked(file)
            val mergedEntries = mergeNativeReplayGainJournalEntries(
                existing?.optJSONArray("entries"),
                entries,
            )
            val mergedRequestKeys = mergeJsonObjectStringMap(
                existing?.optJSONObject("request_album_keys"),
                requestKeys,
            )
            val mergedStatuses = mergeJsonObjectStringMap(
                existing?.optJSONObject("statuses"),
                statuses,
            )
            val root = JSONObject()
                .put("run_id", nativeWorkerRunId)
                .put("updated_at", System.currentTimeMillis())
                .put("entries", mergedEntries)
                .put("request_album_keys", JSONObject(mergedRequestKeys))
                .put("statuses", JSONObject(mergedStatuses))

            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(root.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
                stream = null
            } catch (e: Exception) {
                android.util.Log.w("DownloadService", "Failed to write native ReplayGain journal: ${e.message}")
            } finally {
                if (stream != null) {
                    file.failWrite(stream)
                }
            }
        }
    }

    private fun readNativeReplayGainJournalLocked(file: AtomicFile): JSONObject? {
        return try {
            if (!file.baseFile.exists()) return null
            val text = file.openRead().bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
            JSONObject(text)
        } catch (e: Exception) {
            android.util.Log.w("DownloadService", "Failed to merge native ReplayGain journal: ${e.message}")
            null
        }
    }

    private fun mergeNativeReplayGainJournalEntries(
        existingEntries: JSONArray?,
        currentEntries: List<JSONObject>
    ): JSONArray {
        val byKey = linkedMapOf<String, JSONObject>()

        fun add(entry: JSONObject) {
            val trackId = entry.optString("track_id", "")
            val path = entry.optString("file_path", "")
            val key = if (trackId.isNotBlank()) {
                "track:$trackId"
            } else {
                "path:$path"
            }
            if (key != "path:") {
                byKey[key] = JSONObject(entry.toString())
            }
        }

        if (existingEntries != null) {
            for (index in 0 until existingEntries.length()) {
                existingEntries.optJSONObject(index)?.let(::add)
            }
        }
        for (entry in currentEntries) add(entry)

        return JSONArray().apply {
            for (entry in byKey.values) put(entry)
        }
    }

    private fun mergeJsonObjectStringMap(
        existing: JSONObject?,
        current: Map<String, String>
    ): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        if (existing != null) {
            for (key in existing.keys()) {
                merged[key] = existing.optString(key, "")
            }
        }
        for ((key, value) in current) {
            merged[key] = value
        }
        return merged
    }

    private fun clearNativeReplayGainJournal() {
        synchronized(NATIVE_REPLAYGAIN_JOURNAL_FILE_LOCK) {
            try {
                AtomicFile(File(filesDir, NATIVE_REPLAYGAIN_JOURNAL_FILE)).delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun flushNativeAlbumReplayGainJournalIfComplete() {
        val root = synchronized(NATIVE_REPLAYGAIN_JOURNAL_FILE_LOCK) {
            try {
                val file = File(filesDir, NATIVE_REPLAYGAIN_JOURNAL_FILE)
                if (!file.exists()) return
                val text = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use {
                    it.readText()
                }
                JSONObject(text)
            } catch (e: Exception) {
                android.util.Log.w("DownloadService", "Failed to read native ReplayGain journal: ${e.message}")
                return
            }
        }

        val entriesArray = root.optJSONArray("entries") ?: return
        val entries = mutableListOf<JSONObject>()
        for (index in 0 until entriesArray.length()) {
            entriesArray.optJSONObject(index)?.let { entries.add(JSONObject(it.toString())) }
        }
        val statusesJson = root.optJSONObject("statuses") ?: JSONObject()
        val statuses = mutableMapOf<String, String>()
        for (key in statusesJson.keys()) {
            statuses[key] = statusesJson.optString(key, "")
        }
        val requestKeysJson = root.optJSONObject("request_album_keys") ?: JSONObject()
        val requestKeys = mutableMapOf<String, String>()
        for (key in requestKeysJson.keys()) {
            requestKeys[key] = requestKeysJson.optString(key, "")
        }

        val eligible = buildEligibleNativeAlbumReplayGain(entries, statuses, requestKeys)
        if (eligible.length() <= 1 && hasPendingNativeAlbumReplayGainWork(statuses)) {
            return
        }
        if (writeNativeAlbumReplayGainEntries(eligible)) {
            clearNativeReplayGainJournal()
        }
    }

    private fun writeNativeWorkerSnapshot(
        isRunning: Boolean,
        isPaused: Boolean,
        currentItemId: String,
        message: String,
        lastResult: JSONObject? = null,
        settingsJson: String = "",
        includeItems: Boolean = false,
        snapshotSerial: Long = snapshotWriteSerial.incrementAndGet()
    ) {
        try {
            synchronized(snapshotWriteLock) {
                if (includeItems) {
                    if (snapshotSerial < latestCommittedStateSnapshotSerial) return
                } else {
                    if (snapshotSerial < latestCommittedProgressSnapshotSerial) return
                }

                val counts = nativeWorkerCounts()
                val snapshot = JSONObject()
                    .put("contract_version", NATIVE_WORKER_CONTRACT_VERSION)
                    .put("run_id", nativeWorkerRunId.ifBlank { readNativeWorkerRunIdFromSnapshotFile() })
                    .put("is_running", isRunning)
                    .put("is_paused", isPaused)
                    .put("total", counts.total)
                    .put("completed", counts.completed)
                    .put("failed", counts.failed)
                    .put("skipped", counts.skipped)
                    .put("current_item_id", currentItemId)
                    .put("message", message)
                    .put("updated_at", System.currentTimeMillis())
                    .put("snapshot_serial", snapshotSerial)
                    .put("item_ids", nativeWorkerItemIds())
                    .put("snapshot_mode", if (includeItems) "compact_items" else "delta")
                if (includeItems) {
                    snapshot.put("items", nativeWorkerItemsSnapshot(includeStatic = false))
                } else {
                    nativeWorkerItemSnapshot(currentItemId, includeStatic = false)?.let {
                        snapshot.put("item_delta", it)
                    }
                }
                if (settingsJson.isNotBlank() && includeItems) {
                    snapshot.put("settings_json", settingsJson)
                }
                if (lastResult != null) {
                    snapshot.put("last_result", lastResult)
                }

                synchronized(NATIVE_WORKER_STATE_FILE_LOCK) {
                    val targetFileName = if (includeItems) {
                        NATIVE_WORKER_STATE_FILE
                    } else {
                        NATIVE_WORKER_PROGRESS_FILE
                    }
                    val file = AtomicFile(File(filesDir, targetFileName))
                    var stream: java.io.FileOutputStream? = null
                    try {
                        stream = file.startWrite()
                        stream.write(snapshot.toString().toByteArray(Charsets.UTF_8))
                        file.finishWrite(stream)
                        stream = null
                        if (includeItems) {
                            latestCommittedStateSnapshotSerial = snapshotSerial
                        } else {
                            latestCommittedProgressSnapshotSerial = snapshotSerial
                        }
                    } finally {
                        if (stream != null) {
                            file.failWrite(stream)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("DownloadService", "Failed to write native worker snapshot: ${e.message}")
        }
    }

    private fun writeNativeWorkerSnapshotAsync(
        isRunning: Boolean,
        isPaused: Boolean,
        currentItemId: String,
        message: String,
        lastResult: JSONObject? = null,
        settingsJson: String = "",
        includeItems: Boolean = false
    ) {
        val snapshotSerial = snapshotWriteSerial.incrementAndGet()
        serviceScope.launch {
            writeNativeWorkerSnapshot(
                isRunning = isRunning,
                isPaused = isPaused,
                currentItemId = currentItemId,
                message = message,
                lastResult = lastResult,
                settingsJson = settingsJson,
                includeItems = includeItems,
                snapshotSerial = snapshotSerial
            )
        }
    }

    private fun readNativeWorkerRunIdFromSnapshotFile(): String {
        return try {
            synchronized(NATIVE_WORKER_STATE_FILE_LOCK) {
                val file = File(filesDir, NATIVE_WORKER_STATE_FILE)
                if (!file.exists()) {
                    ""
                } else {
                    val text = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use {
                        it.readText()
                    }
                    JSONObject(text).optString("run_id", "")
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun updateNativeWorkerItem(itemId: String, updater: (NativeWorkerItem) -> Unit) {
        synchronized(nativeWorkerItems) {
            nativeWorkerItems.firstOrNull { it.itemId == itemId }?.let(updater)
        }
    }

    private fun updateNativeWorkerItemProgress(itemId: String) {
        try {
            val raw = Gobackend.getAllDownloadProgress()
            val root = JSONObject(raw)
            val items = root.optJSONObject("items") ?: return
            val progress = items.optJSONObject(itemId) ?: return
            val bytesReceived = progress.optLong("bytes_received", 0L)
            val bytesTotal = progress.optLong("bytes_total", 0L)
            val progressValue = if (bytesTotal > 0L) {
                bytesReceived.toDouble() / bytesTotal.toDouble()
            } else {
                progress.optDouble("progress", 0.0)
            }.coerceIn(0.0, 1.0)
            updateNativeWorkerItem(itemId) {
                it.progress = progressValue
                it.bytesReceived = bytesReceived
                it.bytesTotal = bytesTotal
            }
            if (bytesTotal > 0L) {
                lastProgress = bytesReceived
                lastTotal = bytesTotal
                updateNotification(bytesReceived, bytesTotal)
            } else if (progressValue > 0.0) {
                val percentProgress = (progressValue * NOTIFICATION_PERCENT_TOTAL).toLong()
                    .coerceIn(0L, NOTIFICATION_PERCENT_TOTAL)
                lastProgress = percentProgress
                lastTotal = NOTIFICATION_PERCENT_TOTAL
                updateNotification(percentProgress, NOTIFICATION_PERCENT_TOTAL)
            } else {
                lastProgress = 0L
                lastTotal = 0L
                updateNotification(0L, 0L)
            }
        } catch (_: Exception) {
        }
    }

    private fun nativeWorkerCounts(): NativeWorkerCounts {
        var total = 0
        var completed = 0
        var failed = 0
        var skipped = 0
        synchronized(nativeWorkerItems) {
            total = nativeWorkerItems.size
            for (item in nativeWorkerItems) {
                when (item.status) {
                    "completed" -> completed++
                    "failed" -> failed++
                    "skipped" -> skipped++
                }
            }
        }
        return NativeWorkerCounts(
            total = total,
            completed = completed,
            failed = failed,
            skipped = skipped
        )
    }

    private fun nativeWorkerItemSnapshot(itemId: String, includeStatic: Boolean): JSONObject? {
        if (itemId.isBlank()) return null
        synchronized(nativeWorkerItems) {
            val item = nativeWorkerItems.firstOrNull { it.itemId == itemId } ?: return null
            return nativeWorkerItemSnapshotLocked(item, includeStatic)
        }
    }

    private fun nativeWorkerItemIds(): JSONArray {
        val array = JSONArray()
        synchronized(nativeWorkerItems) {
            for (item in nativeWorkerItems) {
                array.put(item.itemId)
            }
        }
        return array
    }

    private fun nativeWorkerItemsSnapshot(includeStatic: Boolean): JSONArray {
        val array = JSONArray()
        synchronized(nativeWorkerItems) {
            for (item in nativeWorkerItems) {
                array.put(nativeWorkerItemSnapshotLocked(item, includeStatic))
            }
        }
        return array
    }

    private fun nativeWorkerItemSnapshotLocked(item: NativeWorkerItem, includeStatic: Boolean): JSONObject {
        val json = JSONObject()
            .put("item_id", item.itemId)
            .put("status", item.status)
            .put("progress", item.progress)
            .put("bytes_received", item.bytesReceived)
            .put("bytes_total", item.bytesTotal)
        if (includeStatic) {
            json.put("track_name", item.trackName)
                .put("artist_name", item.artistName)
                .put("item_json", item.itemJson)
        }
        if (item.error.isNotBlank()) {
            json.put("error", item.error)
        }
        item.resultJson?.let { json.put("result", it) }
        return json
    }

    @Synchronized
    private fun ensureWakeLock() {
        val existingWakeLock = wakeLock
        if (existingWakeLock?.isHeld == true) {
            existingWakeLock.acquire(WAKELOCK_RENEW_MS)
            return
        }
        if (existingWakeLock != null) {
            wakeLock = null
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        ).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_RENEW_MS)
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        val existingWakeLock = wakeLock
        wakeLock = null
        if (existingWakeLock?.isHeld == true) {
            try {
                existingWakeLock.release()
            } catch (e: RuntimeException) {
                android.util.Log.w("DownloadService", "WakeLock release failed: ${e.message}")
            }
        }
    }

    @Synchronized
    private fun stopForegroundService(cancelNativeWorker: Boolean = true) {
        if (cancelNativeWorker) {
            nativeWorkerCancelRequested = true
            NativeDownloadFinalizer.cancelActiveWork()
            nativeWorkerJob?.cancel(CancellationException("Download service stopped"))
            nativeWorkerPaused = false
        }
        if (cancelNativeWorker && hasNativeWorkerState()) {
            writeNativeWorkerSnapshot(
                isRunning = false,
                isPaused = false,
                currentItemId = "",
                message = "Service stopped",
                includeItems = true
            )
        }
        nativeWorkerJob = null
        isRunning = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasNativeWorkerState(): Boolean {
        if (nativeWorkerRunId.isNotBlank()) return true
        synchronized(nativeWorkerItems) {
            return nativeWorkerItems.isNotEmpty()
        }
    }
    
    private fun updateNotification(progress: Long, total: Long) {
        if (!isRunning) return
        ensureWakeLock()
        
        val notification = buildNotification(progress, total)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun buildNotification(progress: Long, total: Long): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val title = if (queueCount > 1) {
            "Downloading $queueCount tracks"
        } else if (currentTrackName.isNotEmpty()) {
            currentTrackName
        } else {
            "Downloading..."
        }
        
        val text = if (currentStatus == "finalizing") {
            if (currentArtistName.isNotEmpty()) currentArtistName else "Embedding metadata..."
        } else if (currentStatus == "preparing" && total <= 0) {
            "Preparing download..."
        } else if (currentArtistName.isNotEmpty() && queueCount <= 1) {
            currentArtistName
        } else if (total == NOTIFICATION_PERCENT_TOTAL) {
            val progressPercent = (progress * 100 / total).toInt()
            "$progressPercent%"
        } else if (total > 0) {
            val progressPercent = (progress * 100 / total).toInt()
            val progressMB = progress / (1024.0 * 1024.0)
            val totalMB = total / (1024.0 * 1024.0)
            String.format("%.1f / %.1f MB (%d%%)", progressMB, totalMB, progressPercent)
        } else {
            "Downloading..."
        }
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        
        if ((currentStatus == "preparing" || currentStatus == "downloading") && total <= 0) {
            builder.setProgress(0, 0, true)
        } else if (total > 0) {
            builder.setProgress(100, (progress * 100 / total).toInt(), false)
        } else {
            builder.setProgress(0, 0, false)
        }
        
        return builder.build()
    }
    
    override fun onDestroy() {
        nativeWorkerCancelRequested = true
        NativeDownloadFinalizer.cancelActiveWork()
        nativeWorkerJob?.cancel(CancellationException("Download service destroyed"))
        if (hasNativeWorkerState()) {
            writeNativeWorkerSnapshot(
                isRunning = false,
                isPaused = false,
                currentItemId = "",
                message = "Service destroyed",
                includeItems = true
            )
        }
        serviceScope.cancel()
        isRunning = false
        releaseWakeLock()
        super.onDestroy()
    }
}
