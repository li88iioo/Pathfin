package dev.pathfin.android.update

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pathfin.android.BuildConfig
import dev.pathfin.android.i18n.tr
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Failed(val message: String) : UpdateState
    data class Available(val release: AppRelease, val error: String? = null) : UpdateState
    data class Downloading(val release: AppRelease, val bytes: Long) : UpdateState
    data class Ready(val release: AppRelease, val apk: File) : UpdateState
}

/** 更新链路独立于媒体会话：关于页和启动提示共用同一份状态，离开页面不打断下载。 */
class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val store by lazy { application.getSharedPreferences("pathfin_update", Context.MODE_PRIVATE) }
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private val mutablePrompt = MutableStateFlow(false)
    /** 是否弹出更新对话框；自动检查只在发现新版本时打扰用户。 */
    val prompt: StateFlow<Boolean> = mutablePrompt.asStateFlow()
    private var job: Job? = null

    /** 正式包每天最多静默检查一次；失败不提示，下次启动再试。 */
    fun checkAutomatically() {
        if (BuildConfig.DEBUG || state.value != UpdateState.Idle) return
        val elapsed = System.currentTimeMillis() - store.getLong("lastCheck", 0L)
        if (elapsed in 0 until TimeUnit.DAYS.toMillis(1)) return
        check(manual = false)
    }

    fun check() = check(manual = true)

    private fun check(manual: Boolean) {
        when (state.value) {
            UpdateState.Checking, is UpdateState.Downloading -> return
            is UpdateState.Available, is UpdateState.Ready -> { mutablePrompt.value = true; return }
            else -> Unit
        }
        job = viewModelScope.launch {
            mutableState.value = UpdateState.Checking
            val next = try {
                withContext(Dispatchers.IO) { UpdateFiles.cleanup(getApplication()) }
                val release = fetchLatestRelease()
                store.edit { putLong("lastCheck", System.currentTimeMillis()) }
                if (release != null && ReleaseParser.compareVersions(release.version, BuildConfig.VERSION_NAME) > 0)
                    UpdateState.Available(release) else UpdateState.UpToDate
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { UpdateState.Failed(failureMessage(error)) }
            mutableState.value = if (!manual && next !is UpdateState.Available) UpdateState.Idle else next
            if (next is UpdateState.Available) mutablePrompt.value = true
        }
    }

    fun download() {
        val release = (state.value as? UpdateState.Available)?.release ?: return
        job = viewModelScope.launch {
            mutableState.value = UpdateState.Downloading(release, 0)
            mutableState.value = try {
                val apk = downloadRelease(release, UpdateFiles.directory(getApplication())) { bytes ->
                    mutableState.value = UpdateState.Downloading(release, bytes)
                }
                withContext(Dispatchers.IO) { UpdateFiles.verify(getApplication(), apk) }
                UpdateState.Ready(release, apk)
            } catch (cancelled: CancellationException) {
                mutableState.value = UpdateState.Available(release)
                throw cancelled
            } catch (error: Exception) {
                UpdateFiles.directory(getApplication()).listFiles()?.forEach(File::delete)
                UpdateState.Available(release, failureMessage(error))
            }
            // 用户可能关掉对话框让它后台下载；下载完成后再弹出安装。
            if (state.value is UpdateState.Ready) mutablePrompt.value = true
        }
    }

    fun cancelDownload() {
        if (state.value is UpdateState.Downloading) job?.cancel()
    }

    /** 交给安装器前重新校验：文件可能在授权往返期间被清理或替换。 */
    suspend fun installIntent(): Intent? {
        val ready = state.value as? UpdateState.Ready ?: return null
        return try {
            withContext(Dispatchers.IO) { UpdateFiles.verify(getApplication(), ready.apk) }
            ready.apk.setLastModified(System.currentTimeMillis())
            UpdateFiles.installIntent(getApplication(), ready.apk)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ready.apk.delete()
            mutableState.value = UpdateState.Available(ready.release, failureMessage(error))
            null
        }
    }

    fun dismissPrompt() { mutablePrompt.value = false }

    private fun failureMessage(error: Exception) = when (error) {
        is UpdateFailure, is IllegalStateException -> error.message ?: tr("更新失败，请重试")
        is IOException -> tr("无法连接 GitHub，请检查网络后重试")
        else -> tr("更新失败，请重试")
    }
}
