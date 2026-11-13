package dev.pathfin.android.casting

import dev.pathfin.android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.pathfin.android.MainActivity
import dev.pathfin.android.data.ApiException
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.LocalNetworkPermission
import dev.pathfin.android.data.LocalNetworkPermissionDenied
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.PlaybackRequest
import dev.pathfin.android.domain.PlaybackResolver
import dev.pathfin.android.domain.Session
import dev.pathfin.android.domain.TimeUnits
import dev.pathfin.android.i18n.tr
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** 只保存进程内设备和状态；Intent/通知不携带会话、token 或源 URL。 */
object DlnaCasting {
    private val mutableState = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = mutableState.asStateFlow()
    private val discoveryId = AtomicLong()
    @Volatile private var discoveryJob: Job? = null
    @Volatile internal var service: DlnaCastService? = null

    suspend fun discover(context: Context) {
        val id = discoveryId.incrementAndGet()
        val ownJob = currentCoroutineContext().job
        discoveryJob?.takeUnless { it === ownJob }?.cancel()
        discoveryJob = ownJob
        mutableState.update { it.copy(discovering = true, error = null) }
        try {
            val devices = SsdpDiscovery().discover(context.applicationContext)
            if (id == discoveryId.get()) mutableState.update { it.copy(devices = devices, discovering = false) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (id == discoveryId.get()) mutableState.update { it.copy(discovering = false, error = castError(failure)) }
        } finally {
            if (id == discoveryId.get()) { discoveryJob = null; mutableState.update { it.copy(discovering = false) } }
        }
    }

    fun cancelDiscovery() {
        discoveryId.incrementAndGet()
        discoveryJob?.cancel(); discoveryJob = null
        mutableState.update { it.copy(discovering = false) }
    }

    fun start(context: Context, profileId: String, itemId: String, deviceId: String, sourceId: String? = null,
        startPositionTicks: Long = 0) {
        if (profileId.isBlank() || itemId.isBlank() || state.value.devices.none { it.id == deviceId }) {
            mutableState.update { it.copy(error = tr("所选设备或账号已失效，请重新发现设备")) }; return
        }
        if (!LocalNetworkPermission.isGranted(context)) {
            mutableState.update { it.copy(error = LocalNetworkPermission.DENIED_MESSAGE) }; return
        }
        val intent = intent(context, DlnaCastService.START).putExtra("profile", profileId).putExtra("item", itemId)
            .putExtra("device", deviceId).putExtra("source", sourceId).putExtra("position", startPositionTicks.coerceAtLeast(0))
        runCatching { ContextCompat.startForegroundService(context.applicationContext, intent) }.onFailure {
            mutableState.update { it.copy(status = CastStatus.ERROR, busy = false, error = tr("无法启动后台投屏，请回到应用后重新选择设备")) }
        }
    }

    fun play(context: Context) = command(context, DlnaCastService.PLAY)
    fun pause(context: Context) = command(context, DlnaCastService.PAUSE)
    fun seek(context: Context, positionTicks: Long) = command(context, DlnaCastService.SEEK, positionTicks)
    fun stop(context: Context) = command(context, DlnaCastService.STOP)

    /** 账号删除成功后调用。先撤销 lease，再等待独立 IO 清理，绝不让旧账号继续取流。 */
    suspend fun cancelProfile(context: Context, profileId: String) {
        val job = withContext(Dispatchers.Main.immediate) {
            service?.takeIf { state.value.profileId == profileId }?.endSession(tr("账号已移除，已撤销临时媒体地址"))
                ?: run { if (state.value.profileId == profileId) mutableState.update { CastState(devices = it.devices) }; null }
        }
        job?.join()
    }

    internal fun update(change: (CastState) -> CastState) { mutableState.update(change) }
    internal fun intent(context: Context, action: String): Intent = Intent(context.applicationContext, DlnaCastService::class.java).setAction(action)
    private fun command(context: Context, action: String, positionTicks: Long = 0) {
        if (service == null) return
        runCatching { context.applicationContext.startService(intent(context, action).putExtra("position", positionTicks.coerceAtLeast(0))) }
    }
}

/** connectedDevice 前台服务；只接受前台明确选择设备启动，不重启恢复旧的地址/凭据。 */
class DlnaCastService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controls = Mutex()
    @Volatile private var generation = 0L
    private var ending: Deferred<Boolean>? = null
    private var active: ActiveCast? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private data class ActiveCast(val profileId: String, val request: PlaybackRequest, val api: JellyfinApi,
        val apiClient: OkHttpClient, val av: AvTransportClient, val relay: CastRelay, val device: CastDevice,
        var reported: Boolean = false, var lastReportNanos: Long = 0,
        var positionTicks: Long = TimeUnits.msToTicks(request.startPositionMs)) {
        /** 上次校验时的服务器列表版本与结果；列表未变时轮询不再逐次解密。 */
        @Volatile var profileCheck: Pair<Long, Boolean>? = null
    }

    override fun onCreate() {
        super.onCreate()
        DlnaCasting.service = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, tr("DLNA 投屏"), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == START) {
            // 在任何网络/Keystore 操作之前满足前台服务时限。
            try { foreground(tr("正在连接 DLNA 设备")) }
            catch (_: Exception) { DlnaCasting.update { it.copy(status = CastStatus.ERROR, busy = false, error = tr("系统未允许启动投屏服务，请回到应用重试")) }; stopSelf(); return START_NOT_STICKY }
            begin(intent)
        } else if (intent?.action == STOP) {
            endSession()
        } else if (intent != null && active != null) {
            control(intent.action.orEmpty(), intent.getLongExtra("position", 0))
        } else if (active == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        val profile = intent.getStringExtra("profile").orEmpty()
        val itemId = intent.getStringExtra("item").orEmpty()
        val device = DlnaCasting.state.value.devices.firstOrNull { it.id == intent.getStringExtra("device") }
        if (profile.isBlank() || itemId.isBlank() || device?.network == null || !LocalNetworkPermission.isGranted(this)) {
            endSession(if (!LocalNetworkPermission.isGranted(this)) LocalNetworkPermission.DENIED_MESSAGE else tr("设备已失效，请重新发现后连接"))
            return
        }
        val retired = retire()
        val epoch = generation
        val source = intent.getStringExtra("source")
        val position = intent.getLongExtra("position", 0).coerceAtLeast(0)
        DlnaCasting.update { it.copy(status = CastStatus.CONNECTING, busy = true, device = device,
            profileId = profile, itemId = itemId, title = tr("正在读取媒体"), positionTicks = position, durationTicks = null, error = null, authExpired = false) }
        scope.launch {
            var prepared: ActiveCast? = null
            var installed = false
            try {
                retired.join()
                withContext(Dispatchers.IO) { prepared = prepare(profile, itemId, source, position, device) }
                val current = requireNotNull(prepared)
                currentCoroutineContext().ensureActive()
                if (generation != epoch) return@launch
                active = current; installed = true
                watchNetwork(current)
                DlnaCasting.update { it.copy(title = current.request.item.name, durationTicks = current.request.source.runTimeTicks?.takeIf { ticks -> ticks > 0 }
                    ?: current.request.item.runTimeTicks.takeIf { ticks -> ticks > 0 }) }
                withContext(Dispatchers.IO) {
                    checkProfile(current)
                    current.av.setUri(current.relay.url, current.request.item.name, current.relay.mimeType)
                    current.av.play()
                    val accepted = withTimeoutOrNull(12_000) {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            checkProfile(current)
                            if (current.av.transportState() == "PLAYING" && current.relay.bytesServed > 0) break
                            delay(350)
                        }
                        true
                    } == true
                    if (!accepted) throw CastException(tr("设备尚未开始读取媒体；请检查电视支持的格式及与手机的网络连通性"))
                    if (position > 0) {
                        try { current.av.seek(position) }
                        catch (failure: CastException) { if (generation == epoch) DlnaCasting.update { it.copy(error = failure.message) } }
                    }
                    val actualPosition = current.av.position()
                    current.positionTicks = actualPosition.positionTicks
                    if (generation == epoch) DlnaCasting.update { it.copy(positionTicks = actualPosition.positionTicks, durationTicks = actualPosition.durationTicks ?: it.durationTicks) }
                    current.reported = true
                    report(current, "start", current.positionTicks, false)
                }
                if (generation != epoch || active !== current) return@launch
                DlnaCasting.update { it.copy(status = CastStatus.PLAYING, busy = false) }
                foreground(tr("正在投屏至 %1\$s", device.name))
                poll(epoch, current)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == epoch) {
                    DlnaCasting.update { it.copy(authExpired = failure is ApiException && failure.statusCode == 401) }
                    endSession(castError(failure))
                }
            } finally {
                if (!installed) withContext(NonCancellable + Dispatchers.IO) { dispose(prepared) }
            }
        }
    }

    private suspend fun prepare(profileId: String, itemId: String, sourceId: String?, position: Long, device: CastDevice): ActiveCast {
        if (!LocalNetworkPermission.isGranted(this)) throw LocalNetworkPermissionDenied()
        val session = SessionStore(this).loadProfiles().firstOrNull { it.id == profileId }?.session
            ?: throw CastException(tr("此账号已移除，请重新登录后投屏"))
        val apiClient = OkHttpClient()
        var av: AvTransportClient? = null
        var relay: CastRelay? = null
        try {
            val api = JellyfinApi(apiClient)
            val item = api.item(session, itemId)
            if (item.isFolder || item.type !in listOf("Movie", "Episode", "MusicVideo", "Video", "Audio")) throw CastException(tr("请先选择可播放的影片或剧集"))
            val info = api.playbackInfo(session, itemId, sourceId, position)
            val selected = if (!sourceId.isNullOrBlank()) info.sources.firstOrNull { it.id == sourceId } else info.sources.firstOrNull()
            if (selected == null) throw CastException(tr("服务器未提供可投屏的媒体版本"))
            val stream = PlaybackResolver.resolve(session, item, info, selected.id, preferOriginal = true)
            val request = PlaybackRequest(item, info, selected, stream, TimeUnits.ticksToMs(position), session)
            // CastRelay 先验证 progressive 再开放端口；上游客户端不与 API 共享连接池。
            relay = CastRelay(device.localAddress, device.address, request)
            val deviceClient = OkHttpClient.Builder().socketFactory(requireNotNull(device.network).socketFactory).build()
            av = AvTransportClient(device, deviceClient)
            return ActiveCast(profileId, request, api, apiClient, av, relay, device)
        } catch (failure: Exception) {
            relay?.close(); av?.close(); closeClient(apiClient)
            throw failure
        }
    }

    private suspend fun poll(epoch: Long, cast: ActiveCast) {
        var failures = 0
        while (active === cast && generation == epoch) {
            delay(2_000)
            try {
                controls.withLock {
                    if (active !== cast || generation != epoch) return@withLock
                    val (transport, position) = withContext(Dispatchers.IO) { checkProfile(cast); cast.av.transportState() to cast.av.position() }
                    if (active !== cast || generation != epoch) return@withLock
                    failures = 0
                    if (transport in listOf("STOPPED", "NO_MEDIA_PRESENT")) { endSession(); return@withLock }
                    val status = when (transport) { "PLAYING" -> CastStatus.PLAYING; "PAUSED_PLAYBACK" -> CastStatus.PAUSED; else -> null }
                    if (status != null) {
                        cast.positionTicks = position.positionTicks
                        DlnaCasting.update { it.copy(status = status, positionTicks = position.positionTicks, durationTicks = position.durationTicks ?: it.durationTicks) }
                        foreground(if (status == CastStatus.PAUSED) tr("投屏已暂停") else tr("正在投屏至 %1\$s", cast.device.name))
                        if (System.nanoTime() - cast.lastReportNanos > 10_000_000_000L) withContext(Dispatchers.IO) {
                            report(cast, "progress", position.positionTicks, status == CastStatus.PAUSED)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is LocalNetworkPermissionDenied || failure is CastAccountRemoved || ++failures >= 3) {
                    if (active === cast && generation == epoch) endSession(castError(failure))
                }
            }
        }
    }

    private fun control(action: String, ticks: Long) {
        if (DlnaCasting.state.value.busy || action !in listOf(PLAY, PAUSE, SEEK)) return
        val cast = active ?: return
        val epoch = generation
        DlnaCasting.update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                controls.withLock {
                    if (active !== cast || generation != epoch) return@withLock
                    val (transport, position) = withContext(Dispatchers.IO) {
                        checkProfile(cast)
                        when (action) { PLAY -> cast.av.play(); PAUSE -> cast.av.pause(); SEEK -> cast.av.seek(ticks.coerceAtLeast(0)) }
                        var transport = cast.av.transportState()
                        if (action != SEEK) {
                            val expected = if (action == PLAY) "PLAYING" else "PAUSED_PLAYBACK"
                            val reached = withTimeoutOrNull(5_000) {
                                while (transport != expected) { delay(250); transport = cast.av.transportState() }
                                true
                            } == true
                            if (!reached) throw CastException(tr("设备尚未确认操作，请稍后重试"))
                        }
                        transport to cast.av.position()
                    }
                    cast.positionTicks = position.positionTicks
                    if (active === cast && generation == epoch) DlnaCasting.update { it.copy(busy = false,
                        status = if (transport == "PAUSED_PLAYBACK") CastStatus.PAUSED else if (transport == "PLAYING") CastStatus.PLAYING else it.status,
                        positionTicks = position.positionTicks, durationTicks = position.durationTicks ?: it.durationTicks) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (active === cast && generation == epoch) {
                    if (failure is LocalNetworkPermissionDenied || failure is CastAccountRemoved) endSession(castError(failure))
                    else DlnaCasting.update { it.copy(busy = false, error = castError(failure)) }
                }
            }
        }
    }

    /** Main立即撤销会话；独立IO等待旧作业，允许从当前会话Job内调用。 */
    private fun retire(): Deferred<Boolean> {
        generation++
        val old = active; active = null
        old?.relay?.revoke(); unregisterNetwork()
        val jobs = scope.coroutineContext.job.children.toList().onEach { it.cancel() }
        val previous = ending
        return cleanup.async {
            jobs.forEach { it.join() }
            previous?.join()
            if (old == null) previous?.await() ?: true else dispose(old)
        }.also { ending = it }
    }

    internal fun endSession(reason: String? = null): Job {
        val retired = retire()
        val epoch = generation
        DlnaCasting.update { it.copy(status = if (reason == null) CastStatus.STOPPED else CastStatus.ERROR, busy = true, error = reason) }
        return cleanup.launch {
            val stopped = retired.await()
            withContext(Dispatchers.Main.immediate) {
                if (generation == epoch) {
                    DlnaCasting.update { it.copy(busy = false, error = reason ?: if (!stopped) tr("临时媒体地址已撤销；设备未确认停止，请在电视端检查状态") else null) }
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        }
    }

    private suspend fun dispose(cast: ActiveCast?): Boolean {
        if (cast == null) return true
        cast.relay.revoke()
        try {
            // 电视确认与服务器会话各自结束；任一端失败不能跳过另一端的清理。
            val stopped = runCatching { withTimeoutOrNull(3_000) { cast.av.stop(); true } == true }.getOrDefault(false)
            runCatching { if (cast.reported && profileExists(cast)) report(cast, "stop", cast.positionTicks, false) }
            return stopped
        } finally { runCatching { cast.relay.close() }; runCatching { cast.av.close() }; closeClient(cast.apiClient) }
    }

    private fun profileExists(cast: ActiveCast): Boolean {
        val revision = SessionStore.profilesRevision
        cast.profileCheck?.takeIf { it.first == revision }?.let { return it.second }
        return SessionStore(this).loadProfiles().any {
            it.id == cast.profileId && it.session.userId == cast.request.session.userId && it.session.server == cast.request.session.server &&
                it.session.accessToken == cast.request.session.accessToken
        }.also { cast.profileCheck = revision to it }
    }

    private fun checkProfile(cast: ActiveCast) {
        if (!LocalNetworkPermission.isGranted(this)) throw LocalNetworkPermissionDenied()
        if (!profileExists(cast)) throw CastAccountRemoved()
    }

    private suspend fun report(cast: ActiveCast, event: String, ticks: Long, paused: Boolean) {
        try {
            withTimeoutOrNull(3_000) { cast.api.reportPlayback(cast.request.session, event, cast.request, TimeUnits.ticksToMs(ticks), paused) }
            cast.lastReportNanos = System.nanoTime()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Jellyfin 上报故障不能冒充电视控制结果，也不能中断真实播放。 */ }
    }

    private fun watchNetwork(cast: ActiveCast) {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { if (network == cast.device.network) scope.launch { if (active === cast) endSession(tr("局域网连接已断开，临时媒体地址已撤销")) } }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (network == cast.device.network && properties.linkAddresses.none { it.address.hostAddress == cast.device.localAddress })
                    scope.launch { if (active === cast) endSession(tr("本机局域网地址已改变，请重新发现设备")) }
            }
        }
        connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        networkCallback = callback
    }

    private fun unregisterNetwork() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun foreground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun notification(text: String): Notification {
        val state = DlnaCasting.state.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_pathfin)
            .setContentTitle(state.title.takeIf { state.active }.orEmpty().ifBlank { "Pathfin DLNA" })
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        if (state.status in listOf(CastStatus.PLAYING, CastStatus.PAUSED)) {
            val paused = state.status == CastStatus.PAUSED
            builder.addAction(if (paused) R.drawable.ic_action_play else R.drawable.ic_action_pause,
                if (paused) tr("播放") else tr("暂停"), action(if (paused) PLAY else PAUSE, 1))
        }
        builder.addAction(R.drawable.ic_stat_close, tr("停止投屏"), action(STOP, 2))
        return builder.build()
    }

    private fun action(value: String, code: Int): PendingIntent = PendingIntent.getService(this, code,
        DlnaCasting.intent(this, value), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    override fun onTaskRemoved(rootIntent: Intent?) { /* 前台通知继续提供真实暂停/停止；非 sticky，不恢复被系统杀死的会话。 */ }

    override fun onDestroy() {
        val retired = retire()
        if (DlnaCasting.service === this) DlnaCasting.service = null
        scope.cancel()
        cleanup.launch { retired.join(); cleanup.cancel() }
        DlnaCasting.update { if (it.active) it.copy(status = CastStatus.ERROR, busy = false, error = tr("投屏服务已停止，临时媒体地址已撤销")) else it.copy(busy = false) }
        super.onDestroy()
    }

    companion object {
        internal const val START = "dev.pathfin.cast.START"
        internal const val PLAY = "dev.pathfin.cast.PLAY"
        internal const val PAUSE = "dev.pathfin.cast.PAUSE"
        internal const val SEEK = "dev.pathfin.cast.SEEK"
        internal const val STOP = "dev.pathfin.cast.STOP"
        private const val CHANNEL = "pathfin-dlna"
        private const val NOTIFICATION_ID = 8301
        private fun closeClient(client: OkHttpClient) {
            runCatching { client.dispatcher.cancelAll() }
            runCatching { client.connectionPool.evictAll() }
            runCatching { client.dispatcher.executorService.shutdown() }
        }
    }
}

private class CastAccountRemoved : java.io.IOException()

private fun castError(failure: Exception): String = when (failure) {
    is CastAccountRemoved -> tr("此账号已移除，临时媒体地址已撤销")
    is LocalNetworkPermissionDenied -> LocalNetworkPermission.DENIED_MESSAGE
    is CastException -> failure.message ?: tr("投屏失败，请检查设备状态后重试")
    is ApiException -> when (failure.statusCode) { 401 -> tr("登录已失效，请重新登录"); 403 -> tr("当前账号无权播放此媒体"); else -> tr("无法读取服务器播放信息，请稍后重试") }
    is IllegalArgumentException -> tr("此媒体没有可用的直接播放源，请更换片源")
    else -> tr("无法连接 DLNA 设备，请检查局域网及设备支持的媒体格式")
}
