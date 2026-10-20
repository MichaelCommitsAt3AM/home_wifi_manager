package com.homewifi.manager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

const val RELEASE_REPOSITORY = "MichaelCommitsAt3AM/home_wifi_manager"
private const val RELEASE_API = "https://api.github.com/repos/$RELEASE_REPOSITORY/releases/latest"
const val MAX_UPDATE_BYTES = 100L * 1024 * 1024

data class UpdateRelease(val versionCode: Int = 0, val versionName: String = "", val apkUrl: String = "",
    val sha256: String = "", val size: Long = 0, val minSdk: Int = 26)
internal data class ReleaseAsset(val name: String = "", val browser_download_url: String = "")
internal data class GitHubRelease(val tag_name: String = "", val draft: Boolean = false, val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList())
data class UpdateState(val release: UpdateRelease? = null, val checking: Boolean = false,
    val stage: String = "available", val progress: Int = 0, val dismissed: Boolean = false,
    val message: String? = null)

/** Validate metadata before any APK is fetched. Release URLs stay inside this repository. */
fun validateUpdate(release: UpdateRelease, tag: String, currentVersionCode: Int, sdk: Int): Boolean {
    require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Unsupported release tag" }
    require(release.versionName == tag.removePrefix("v")) { "Release version mismatch" }
    require(release.versionCode > 0 && release.minSdk >= 26) { "Invalid release metadata" }
    require(release.size in 1..MAX_UPDATE_BYTES && release.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid APK integrity metadata" }
    val uri = java.net.URI(release.apkUrl)
    require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.query == null && uri.fragment == null &&
        uri.rawPath == "/$RELEASE_REPOSITORY/releases/download/$tag/HomeWiFi-$tag.apk") { "Unexpected APK source" }
    return release.versionCode > currentVersionCode && release.minSdk <= sdk
}

fun updateDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

@Singleton
class AppUpdater @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val downloadLock = Mutex()
    private val preferences = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private val folder = File(context.filesDir, "updates")
    private val apk = File(folder, "update.apk")
    private var lastAttempt = 0L
    private val gson = Gson()
    // Separate client: the home-server bearer token never reaches GitHub or its CDN.
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            require(url.scheme == "https" && url.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            chain.proceed(chain.request())
        }.build()

    fun check(force: Boolean = false) { scope.launch { lock.withLock {
        if(mutable.value.stage == "downloading") {
            if(force) mutable.update { it.copy(dismissed = false) }
            return@withLock
        }
        val now = System.currentTimeMillis()
        if(now-lastAttempt < 60_000) {
            if(force) mutable.update { it.copy(message = "Please wait a minute between update checks.") }
            return@withLock
        }
        val last = preferences.getLong("checked-at", 0)
        if(now-last < (if(force) 60_000 else 6*60*60_000L)) {
            if(force) mutable.update { it.copy(message = "Please wait a minute between update checks.") }
            return@withLock
        }
        lastAttempt = now
        mutable.update { it.copy(checking = true, message = null) }
        try {
            val request = Request.Builder().url(RELEASE_API).header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "HomeWiFi/${BuildConfig.VERSION_NAME}").build()
            val release = client.newCall(request).execute().use { response ->
                if(response.code == 404) null else {
                    check(response.isSuccessful) { "Release check failed" }
                    gson.fromJson(readLimited(response.body!!.byteStream(), 256_000), GitHubRelease::class.java)
                }
            }
            if(release == null) {
                mutable.update { it.copy(checking = false, message = "No app releases published yet.") }
            } else if(!release.draft && !release.prerelease) {
                val metadata = release.assets.singleOrNull { it.name == "update.json" } ?: error("Release metadata missing")
                val expected = "https://github.com/$RELEASE_REPOSITORY/releases/download/${release.tag_name}/update.json"
                require(metadata.browser_download_url == expected)
                val update = client.newCall(Request.Builder().url(expected).build()).execute().use { response ->
                    check(response.isSuccessful)
                    gson.fromJson(readLimited(response.body!!.byteStream(), 16_000), UpdateRelease::class.java)
                }
                val available = validateUpdate(update, release.tag_name, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT)
                val apkAsset = release.assets.singleOrNull { it.name == "HomeWiFi-${release.tag_name}.apk" }
                require(apkAsset?.browser_download_url == update.apkUrl)
                val dismissed = !force && preferences.getInt("dismissed-version", 0) == update.versionCode
                var ready = false
                if(available && apk.exists()) {
                    ready = runCatching {
                        require(apk.length() == update.size && fileDigest(apk).equals(update.sha256, true))
                        verifyApk(apk, update)
                        true
                    }.getOrDefault(false)
                }
                if(!ready) apk.delete()
                mutable.value = UpdateState(release = if(available) update else null, dismissed = dismissed,
                    stage = if(ready) "ready" else "available", progress = if(ready) 100 else 0,
                    message = if(ready) "Download verified. Ready to install." else if(available) "Version ${update.versionName} is available."
                        else if(update.versionCode > BuildConfig.VERSION_CODE && update.minSdk > Build.VERSION.SDK_INT) "The latest release requires a newer Android version." else "You’re up to date.")
            } else mutable.update { it.copy(checking = false, message = "No stable app release is available.") }
            preferences.edit().putLong("checked-at", now).apply()
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { mutable.update { it.copy(checking = false, message = "Could not check GitHub for updates. Try again later.") } }
    } } }

    fun dismiss() {
        mutable.value.release?.let { preferences.edit().putInt("dismissed-version", it.versionCode).apply() }
        mutable.update { it.copy(dismissed = true) }
    }

    fun download() { scope.launch {
        if(!downloadLock.tryLock()) return@launch
        val release = lock.withLock {
            mutable.value.release?.also {
                mutable.update { it.copy(stage = "downloading", progress = 0, message = null, dismissed = false) }
            }
        }
        if(release == null) { downloadLock.unlock(); return@launch }
        val partial = File(folder, "update.part")
        try {
            folder.mkdirs()
            partial.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                check(response.isSuccessful)
                val body = response.body ?: error("Missing APK")
                require(body.contentLength() == -1L || body.contentLength() == release.size)
                body.byteStream().use { source -> partial.outputStream().use { sink ->
                    val buffer = ByteArray(64*1024)
                    var total = 0L
                    while(true) {
                        val count = source.read(buffer)
                        if(count < 0) break
                        total += count
                        require(total <= release.size && total <= MAX_UPDATE_BYTES)
                        sink.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        val progress = (total*100/release.size).toInt()
                        if(progress != mutable.value.progress) mutable.update { it.copy(progress = progress) }
                    }
                    require(total == release.size)
                } }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) }.equals(release.sha256, true))
            verifyApk(partial, release)
            apk.delete()
            check(partial.renameTo(apk))
            mutable.update { it.copy(stage = "ready", progress = 100, message = "Download verified. Ready to install.") }
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) {
            partial.delete(); apk.delete()
            mutable.update { it.copy(stage = "error", message = "The update could not be downloaded or verified. Try again.") }
        } finally { downloadLock.unlock() }
    } }

    private fun fileDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(64*1024)
            while(true) { val count = source.read(buffer); if(count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, release: UpdateRelease) {
        val flags = if(Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val version = if(Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(archive.packageName == context.packageName && version == release.versionCode.toLong() && version > BuildConfig.VERSION_CODE)
        require(archive.versionName == release.versionName)
        require(archive.applicationInfo!!.minSdkVersion <= Build.VERSION.SDK_INT)
        fun signers(info: PackageInfo): Set<String> = (if(Build.VERSION.SDK_INT >= 28) info.signingInfo!!.apkContentsSigners else info.signatures!!)
            .map { updateDigest(it.toByteArray()) }.toSet()
        val trusted = signers(installed)
        require(trusted.isNotEmpty() && signers(archive) == trusted) { "App signing identity does not match" }
    }

    fun install() {
        val release = mutable.value.release ?: return
        if(mutable.value.stage != "ready") return
        try {
            verifyApk(apk, release)
            val intent = if(!context.packageManager.canRequestPackageInstalls()) {
                mutable.update { it.copy(message = "Allow updates from Home WiFi, then return and tap Install.") }
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            } else {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch(_: Exception) { mutable.update { it.copy(stage = "error", message = "Android could not open this update. Download it again.") } }
    }
}

internal fun readLimited(source: java.io.InputStream, maximum: Int): String = source.use {
    val sink = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while(true) {
        val count = it.read(buffer)
        if(count < 0) break
        require(sink.size()+count <= maximum)
        sink.write(buffer, 0, count)
    }
    sink.toString("UTF-8")
}

@HiltViewModel
class UpdateViewModel @Inject constructor(private val updater: AppUpdater) : ViewModel() {
    val state = updater.state
    private val mutablePreview = MutableStateFlow(false)
    val preview = mutablePreview.asStateFlow()
    init { updater.check() }
    fun preview(enabled: Boolean) { if(BuildConfig.DEBUG) mutablePreview.value = enabled }
    fun dismiss() { if(mutablePreview.value) mutablePreview.value = false else updater.dismiss() }
    fun check() = updater.check(true)
    fun checkAutomatically() = updater.check()
    fun download() = updater.download()
    fun install() = updater.install()
}
