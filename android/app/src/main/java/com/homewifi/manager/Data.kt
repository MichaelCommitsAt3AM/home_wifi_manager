package com.homewifi.manager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import retrofit2.http.Query as ApiQuery
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class Device(
    val mac: String, val ip: String? = null, val hostname: String = "", val alias: String = "",
    val trusted: Boolean = false, val protected: Boolean = false, val online: Boolean = false,
    val blocked: Boolean = false, val restriction_scope: String = "", val connection_type: String = "unknown",
    val port: String = "", val device_type: String = "", val first_seen: String = "",
    val last_seen: String? = null, val source: String = "router"
) { val name: String get() = alias.ifBlank { hostname.ifBlank { mac } } }
data class Event(val id: Long, val at: String, val kind: String, val mac: String? = null, val detail: String = "")
data class RouterInfo(val model: String = "", val firmware: String = "", val hardware: String = "",
    val cpu: String = "", val memory: String = "", val block_supported: Boolean = false,
    val scheduling_supported: Boolean = false)
data class Dashboard(val router_online: Boolean? = null, val last_sync: String? = null,
    val error: String? = null, val total: Int = 0, val connected: Int = 0, val unknown: Int = 0,
    val blocked: Int = 0, val monitoring: Boolean = false, val stale: Boolean = true,
    val router: RouterInfo = RouterInfo(), val events: List<Event> = emptyList(), val adguard: AdGuardStatus = AdGuardStatus(),
    val router_access_paused: Boolean = false, val connection: ConnectionHealth = ConnectionHealth())
data class RouterAccessStatus(val router_access_paused: Boolean = false, val router_logout_verified: Boolean = false,
    val router_access_paused_at: String? = null, val router_access_error: String? = null)
data class RouterRelease(val confirmed: Boolean = true)
data class AdGuardStatus(val configured: Boolean = false, val online: Boolean? = null,
    val running: Boolean = false, val protection_enabled: Boolean = false, val filtering_enabled: Boolean = false,
    val dns_reachable: Boolean = false, val normal_resolution: Boolean = false, val blocking_observed: Boolean? = null,
    val authentication_required: Boolean = false, val version: String = "", val queries: Long = 0,
    val blocked_queries: Long = 0, val blocked_percent: Double = 0.0, val avg_processing_ms: Double = 0.0,
    val enabled_filters: Int = 0, val rules_count: Long = 0, val observed_clients: Int = 0,
    val checked_at: String? = null, val last_filter_update: String = "", val error: String? = null)
data class Preferences(val poll_seconds: Int = 60, val notification_categories: List<String> = listOf("unknown_device", "router_offline", "router_restored", "schedule_executed", "schedule_failed", "management_failed"),
    val writes_enabled: Boolean = false, val retention_days: Int = 90, val ntfy_configured: Boolean = false,
    val version: String = "1.0.0", val router_access_paused: Boolean = false,
    val router_logout_verified: Boolean = false, val router_access_error: String? = null)
data class Schedule(val id: Int, val mac: String, val action: String, val timezone: String,
    val next_run: String, val local_time: String, val weekdays: String, val enabled: Boolean, val status: String)
data class ScheduleDraft(val mac: String, val action: String, val timezone: String,
    val run_at: String? = null, val local_time: String = "", val weekdays: List<Int> = emptyList())
data class DevicePatch(val alias: String? = null, val trusted: Boolean? = null, val protected: Boolean? = null)
data class AccessChange(val blocked: Boolean, val confirmed: Boolean = true, val minutes: Int? = null)
data class Outage(val kind: String, val started_at: String, val ended_at: String? = null, val end_reason: String? = null)
data class SpeedTestResult(val state: String = "idle", val started_at: String? = null, val finished_at: String? = null,
    val download_mbps: Double? = null, val upload_mbps: Double? = null, val latency_ms: Double? = null, val error: String? = null)
data class ConnectionHealth(val state: String = "checking", val stale: Boolean = true, val checked_at: String? = null,
    val router_reachable: Boolean? = null, val internet_reachable: Boolean? = null, val dns_working: Boolean? = null,
    val tls_connect_ms: Double? = null, val outages: List<Outage> = emptyList(), val speed_test: SpeedTestResult = SpeedTestResult())
data class ProtectionChange(val enabled: Boolean, val minutes: Int = 5, val confirmed: Boolean = true)
data class DomainException(val domain: String, val allowed: Boolean, val confirmed: Boolean = true)
data class DomainExceptions(val domains: List<String> = emptyList())
data class FilterRefresh(val updated: Int = 0)

interface WifiApi {
    @GET("api/connection") suspend fun connection(): ConnectionHealth
    @POST("api/connection/check") suspend fun checkConnection(): ConnectionHealth
    @POST("api/connection/speedtest") suspend fun speedTest(@Body body: RouterRelease = RouterRelease()): SpeedTestResult
    @POST("api/adguard/protection") suspend fun protection(@Body body: ProtectionChange): AdGuardStatus
    @POST("api/adguard/filters/refresh") suspend fun refreshFilters(@Body body: RouterRelease = RouterRelease()): FilterRefresh
    @GET("api/adguard/exceptions") suspend fun exceptions(): DomainExceptions
    @POST("api/adguard/exceptions") suspend fun exception(@Body body: DomainException): DomainExceptions
    @POST("api/router/access/release") suspend fun releaseRouterAccess(@Body body: RouterRelease = RouterRelease()): RouterAccessStatus
    @POST("api/router/access/resume") suspend fun resumeRouterAccess(): RouterAccessStatus
    @GET("api/adguard") suspend fun adguard(): AdGuardStatus
    @GET("api/dashboard") suspend fun dashboard(): Dashboard
    @GET("api/devices") suspend fun devices(): List<Device>
    @GET("api/activity") suspend fun events(@ApiQuery("address") mac: String? = null, @ApiQuery("before") before: Long? = null, @ApiQuery("after") after: Long? = null, @ApiQuery("limit") limit: Int = 100): List<Event>
    @PATCH("api/devices/{mac}") suspend fun patch(@Path("mac") mac: String, @Body patch: DevicePatch): Device
    @POST("api/devices/{mac}/access") suspend fun access(@Path("mac") mac: String, @Body change: AccessChange): Device
    @POST("api/sync") suspend fun sync(): Dashboard
    @GET("api/settings") suspend fun settings(): Preferences
    @PUT("api/settings") suspend fun settings(@Body settings: Preferences): Preferences
    @GET("api/schedules") suspend fun schedules(@ApiQuery("address") mac: String? = null): List<Schedule>
    @POST("api/schedules") suspend fun schedule(@Body draft: ScheduleDraft): Schedule
    @DELETE("api/schedules/{id}") suspend fun cancel(@Path("id") id: Int): Map<String, Boolean>
}

interface ConnectionSettings {
    val url: String
    val token: String
    var theme: String
    var notifications: Boolean
    var cursor: Long
    var dynamicColors: Boolean
    fun save(address: String, token: String)
    fun clear()
}

@Singleton
class SecureConfig @Inject constructor(@ApplicationContext context: Context) : ConnectionSettings {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val key: SecretKey get() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("wifi-api", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("wifi-api", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override var url: String
        get() = prefs.getString("url", "")!!
        private set(value) { prefs.edit().putString("url", value).apply() }
    override var theme: String
        get() = prefs.getString("theme", "system")!!
        set(value) { prefs.edit().putString("theme", value).apply() }
    override var dynamicColors: Boolean
        get() = prefs.getBoolean("dynamic_colors", false)
        set(value) { prefs.edit().putBoolean("dynamic_colors", value).apply() }
    override var notifications: Boolean
        get() = prefs.getBoolean("notifications", false)
        set(value) { prefs.edit().putBoolean("notifications", value).apply() }
    override var cursor: Long
        get() = prefs.getLong("cursor", -1)
        set(value) { prefs.edit().putLong("cursor", value).apply() }
    override val token: String get() { return try {
        val encrypted = prefs.getString("token", null) ?: return ""
        val iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Exception) { "" } }
    override fun save(address: String, token: String) {
        val uri = java.net.URI(address)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Enter an HTTPS backend address" }
        require(token.length >= 32) { "Enter the server API token (at least 32 characters)" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        prefs.edit().putString("url", address.trimEnd('/') + "/")
            .putString("token", Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).putLong("cursor", -1).apply()
    }
    override fun clear() { prefs.edit().remove("token").remove("iv").putLong("cursor", -1).apply() }
}

@Entity(tableName = "cached_devices")
data class CachedDevice(@PrimaryKey val mac: String, val json: String)
@Dao interface DeviceDao {
    @Query("SELECT * FROM cached_devices") fun observe(): Flow<List<CachedDevice>>
    @Query("DELETE FROM cached_devices") suspend fun clear()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(devices: List<CachedDevice>)
    @Transaction suspend fun replace(devices: List<CachedDevice>) { clear(); insert(devices) }
}
@Database(entities = [CachedDevice::class], version = 1, exportSchema = false)
abstract class WifiDatabase : RoomDatabase() { abstract fun devices(): DeviceDao }

interface ApiSource { fun create(): WifiApi }
@Singleton
class ApiFactory @Inject constructor(private val config: ConnectionSettings) : ApiSource {
    override fun create(): WifiApi {
        // Capture a consistent endpoint/token pair; never attach router credentials.
        val token = config.token
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build()) }.build()
        return Retrofit.Builder().baseUrl(config.url).client(client).addConverterFactory(GsonConverterFactory.create()).build().create(WifiApi::class.java)
    }
}

interface RouterRepository {
    val cached: Flow<List<CachedDevice>>
    fun decode(rows: List<CachedDevice>): List<Device>
    suspend fun load(): Triple<Dashboard, List<Device>, Preferences>
    fun api(): WifiApi
    suspend fun clearCache()
}
@Singleton
class Repository @Inject constructor(private val factory: ApiSource, private val dao: DeviceDao) : RouterRepository {
    private val gson = Gson()
    override val cached = dao.observe()
    override fun decode(rows: List<CachedDevice>) = rows.mapNotNull { runCatching { gson.fromJson(it.json, Device::class.java) }.getOrNull() }
    override suspend fun load(): Triple<Dashboard, List<Device>, Preferences> {
        val api = factory.create()
        val dashboard = api.dashboard()
        val devices = api.devices()
        val settings = api.settings()
        dao.replace(devices.map { CachedDevice(it.mac, gson.toJson(it)) })
        return Triple(dashboard, devices, settings)
    }
    override fun api() = factory.create()
    override suspend fun clearCache() = dao.clear()
}

@Module @InstallIn(SingletonComponent::class)
object DataModule {
    @Provides fun config(config: SecureConfig): ConnectionSettings = config
    @Provides fun source(factory: ApiFactory): ApiSource = factory
    @Provides fun repository(repository: Repository): RouterRepository = repository
    @Provides @Singleton fun database(@ApplicationContext context: Context) = Room.databaseBuilder(context, WifiDatabase::class.java, "wifi-cache.db").build()
    @Provides fun dao(db: WifiDatabase) = db.devices()
}
