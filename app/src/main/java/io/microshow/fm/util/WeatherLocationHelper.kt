package io.microshow.fm.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 实时定位与天气辅助类（高德地图 Web 服务版，专为机车与车载大屏定制）
 * - 5 秒高频准确定位：每 5 秒刷新一次定位，实时捕获机车行驶轨迹与位置变化
 * - 优先硬件 GPS：无 GPS 信号时（隧道、地库）自动平滑回退至基站/网络定位与 IP 兜底
 * - 智能逆地理更新：位移超过 150 米或跨区时即时刷新县市名称
 * - 逆地理编码与实时天气全部改用高德地图 API：
 *   · 逆地理：https://restapi.amap.com/v3/geocode/regeo
 *   · 天气：  https://restapi.amap.com/v3/weather/weatherInfo
 *   · IP 兜底定位仍用 ip-api.com 取经纬度，再交给高德逆地理解析成行政区划
 */
class WeatherLocationHelper(
    private val context: Context,
    private val onUpdate: (location: String, weather: String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastLocationText = "正在定位..."
    private var lastWeatherText = "天气获取中..."

    /** 最近一次逆地理拿到的行政区代码，高德天气接口按 adcode 查询 */
    private var lastAdcode = ""

    private var isRunning = false
    private var isGpsFixReceived = false
    private var lastGpsFixTime = 0L

    // 记录上一次逆地理与天气的经纬度与时间
    private var lastGeocodedLat = 0.0
    private var lastGeocodedLon = 0.0
    private var lastGeocodeTime = 0L
    private var lastWeatherFetchTime = 0L

    private var activeGpsListener: LocationListener? = null
    private var activeNetworkListener: LocationListener? = null

    // 5 秒高频定时轮询，确保在各类特殊车载 ROM 下均能准时刷新
    private val periodicPollRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                checkLocationUpdates()
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val TAG = "WeatherLocationHelper"

        /** 高德开放平台 Web 服务 Key（可在高德控制台随时重置） */
        private const val AMAP_KEY = "31f95c634777d3cf154bae9748c40d86"

        private const val AMAP_REGEO_URL = "https://restapi.amap.com/v3/geocode/regeo"
        private const val AMAP_WEATHER_URL = "https://restapi.amap.com/v3/weather/weatherInfo"
        private const val IP_LOCATION_URL = "http://ip-api.com/json/?lang=zh-CN"

        private const val POLL_INTERVAL_MS = 5000L         // 5 秒刷新一次定位
        private const val GPS_SIGNAL_TIMEOUT_MS = 8000L    // 8 秒无 GPS 信号视为失去 GPS
        private const val WEATHER_REFRESH_INTERVAL_MS = 15 * 60 * 1000L // 天气 15 分钟刷新一次
        private const val MIN_DISTANCE_METERS_FOR_GEOCODE = 150f        // 位移超 150 米重新解析区县
    }

    /** 高德逆地理解析结果：展示名 + 行政区代码 */
    private data class GeoInfo(val displayName: String, val adcode: String)

    /**
     * 启动/恢复高频定位（MainActivity.onResume 调用）
     */
    fun start() {
        if (isRunning) return
        isRunning = true

        // 1. 先进行一次毫秒级 IP 定位兜底，避免首次进入时短暂白屏
        if (lastGeocodedLat == 0.0) {
            fetchIpLocationAndWeather()
        }

        // 2. 注册系统定位监听并启动 5 秒轮询刷新
        registerLocationListeners()
        mainHandler.removeCallbacks(periodicPollRunnable)
        mainHandler.postDelayed(periodicPollRunnable, POLL_INTERVAL_MS)
    }

    /**
     * 暂停定位刷新（MainActivity.onPause 调用，避免熄火或切后台耗电）
     */
    fun pause() {
        isRunning = false
        mainHandler.removeCallbacks(periodicPollRunnable)
        unregisterLocationListeners()
    }

    /**
     * 完全停止（MainActivity.onDestroy 调用）
     */
    fun stop() {
        pause()
    }

    @SuppressLint("MissingPermission")
    private fun registerLocationListeners() {
        if (!hasLocationPermission()) {
            Log.d(TAG, "未授予系统定位权限，使用 IP 兜底定位")
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return

        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // 1. 优先注册 GPS 定位监听（5 秒间隔，0 米阈值）
        val isGpsEnabled = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }

        if (isGpsEnabled && hasFine) {
            try {
                // 先读取最后已知 GPS 位置
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    onNewLocation(it, isGps = true)
                }

                val gpsListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        onNewLocation(location, isGps = true)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                activeGpsListener = gpsListener
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    POLL_INTERVAL_MS,
                    0f,
                    gpsListener,
                    Looper.getMainLooper()
                )
            } catch (e: Exception) {
                Log.w(TAG, "注册 GPS 监听失败: ${e.message}")
            }
        }

        // 2. 注册网络定位监听（基站/WiFi，5 秒间隔）作为双轨保障
        val isNetEnabled = try {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }

        if (isNetEnabled) {
            try {
                val netListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        // 仅当最近 8 秒无有效 GPS 信号时才采用网络定位
                        val now = SystemClock.elapsedRealtime()
                        if (!isGpsFixReceived || (now - lastGpsFixTime > GPS_SIGNAL_TIMEOUT_MS)) {
                            onNewLocation(location, isGps = false)
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                activeNetworkListener = netListener
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    POLL_INTERVAL_MS,
                    0f,
                    netListener,
                    Looper.getMainLooper()
                )
            } catch (e: Exception) {
                Log.w(TAG, "注册网络定位监听失败: ${e.message}")
            }
        }
    }

    private fun unregisterLocationListeners() {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return

        activeGpsListener?.let {
            try { locationManager.removeUpdates(it) } catch (e: Exception) { e.printStackTrace() }
            activeGpsListener = null
        }
        activeNetworkListener?.let {
            try { locationManager.removeUpdates(it) } catch (e: Exception) { e.printStackTrace() }
            activeNetworkListener = null
        }
    }

    /**
     * 5 秒周期性检测与主动拉取最新位置（应对某些车机 ROM requestLocationUpdates 不准时回调的情况）
     */
    @SuppressLint("MissingPermission")
    private fun checkLocationUpdates() {
        if (!hasLocationPermission()) return

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return

        val now = SystemClock.elapsedRealtime()

        // 1. 优先检查 GPS 最后已知位置
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            try {
                val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (gpsLoc != null) {
                    onNewLocation(gpsLoc, isGps = true)
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. 若 8 秒内未收到 GPS 信号，回退检查网络或被动定位
        if (!isGpsFixReceived || (now - lastGpsFixTime > GPS_SIGNAL_TIMEOUT_MS)) {
            try {
                val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (netLoc != null) {
                    onNewLocation(netLoc, isGps = false)
                    return
                }
                val passiveLoc = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                if (passiveLoc != null) {
                    onNewLocation(passiveLoc, isGps = false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 接收到有效定位数据（GPS 或网络）
     */
    private fun onNewLocation(location: Location, isGps: Boolean) {
        if (isGps) {
            isGpsFixReceived = true
            lastGpsFixTime = SystemClock.elapsedRealtime()
        }

        val lat = location.latitude
        val lon = location.longitude

        val now = System.currentTimeMillis()
        val shouldGeocode = if (lastGeocodedLat == 0.0 && lastGeocodedLon == 0.0) {
            true
        } else {
            val dist = FloatArray(1)
            Location.distanceBetween(lastGeocodedLat, lastGeocodedLon, lat, lon, dist)
            // 位移超过 150 米（车辆行驶移动）或距上次逆地理超过 60 秒，触发更新
            dist[0] >= MIN_DISTANCE_METERS_FOR_GEOCODE || (now - lastGeocodeTime > 60_000L)
        }

        if (shouldGeocode) {
            lastGeocodedLat = lat
            lastGeocodedLon = lon
            lastGeocodeTime = now

            thread {
                val geo = reverseGeocodeAmap(lat, lon)
                if (geo != null && geo.displayName.isNotBlank()) {
                    lastLocationText = geo.displayName
                    lastAdcode = geo.adcode
                    postUpdate()
                }
            }
        }

        // 检查天气刷新（首次或超过 15 分钟）
        if (now - lastWeatherFetchTime > WEATHER_REFRESH_INTERVAL_MS) {
            lastWeatherFetchTime = now
            thread {
                // 优先复用刚解析出的 adcode；若逆地理线程还没回填，则这里自行解析一次
                var adcode = lastAdcode
                if (adcode.isBlank()) {
                    adcode = reverseGeocodeAmap(lat, lon)?.adcode ?: ""
                }
                fetchWeather(adcode)
            }
        }
    }

    /**
     * 高德逆地理编码：经纬度 → 区县名称 + adcode。
     * 注意高德对直辖市/县级市的 city 字段可能返回空数组 []，需用 optText 兼容。
     */
    private fun reverseGeocodeAmap(lat: Double, lon: Double): GeoInfo? {
        try {
            val urlStr = "%s?key=%s&location=%.6f,%.6f&extensions=base&output=json"
                .format(Locale.US, AMAP_REGEO_URL, AMAP_KEY, lon, lat)
            val body = httpGet(urlStr) ?: return null
            val json = JSONObject(body)
            if (json.optString("status") != "1") {
                Log.w(TAG, "高德逆地理失败: ${json.optString("info")} / ${json.optString("infocode")}")
                return null
            }
            val regeo = json.optJSONObject("regeocode") ?: return null
            val comp = regeo.optJSONObject("addressComponent") ?: return null

            val province = optText(comp, "province")
            val city = optText(comp, "city")
            val district = optText(comp, "district")
            val adcode = optText(comp, "adcode")

            val formatted = when {
                city.isNotEmpty() && district.isNotEmpty() && city != district -> "$city $district"
                district.isNotEmpty() -> district
                city.isNotEmpty() -> city
                province.isNotEmpty() -> province
                else -> optText(regeo, "formatted_address")
            }
            return GeoInfo(formatted, adcode)
        } catch (e: Exception) {
            Log.w(TAG, "高德逆地理请求异常: ${e.message}")
            return null
        }
    }

    /**
     * IP 定位兜底（ip-api.com 取经纬度），再交给高德逆地理解析区县并查天气。
     */
    private fun fetchIpLocationAndWeather() {
        thread {
            try {
                val body = httpGet(IP_LOCATION_URL) ?: return@thread
                val json = JSONObject(body)
                if (json.optString("status") != "success") return@thread

                val lat = json.optDouble("lat", 0.0)
                val lon = json.optDouble("lon", 0.0)
                if (lat == 0.0 || lon == 0.0) return@thread

                val geo = reverseGeocodeAmap(lat, lon) ?: return@thread
                if (lastLocationText == "正在定位..." && geo.displayName.isNotEmpty()) {
                    lastLocationText = geo.displayName
                    lastAdcode = geo.adcode
                    postUpdate()
                }
                if (lastWeatherText == "天气获取中..." && geo.adcode.isNotEmpty()) {
                    fetchWeather(geo.adcode)
                }
            } catch (e: Exception) {
                Log.w(TAG, "IP 定位请求异常: ${e.message}")
            }
        }
    }

    /**
     * 调用高德天气查询 API，按 adcode 获取实时天气。
     * 返回形如：{"status":"1","lives":[{"weather":"阴","temperature":"7",...}]}
     */
    private fun fetchWeather(adcode: String) {
        if (adcode.isBlank()) return
        try {
            val urlStr = "%s?key=%s&city=%s&extensions=base&output=json"
                .format(Locale.US, AMAP_WEATHER_URL, AMAP_KEY, adcode)
            val body = httpGet(urlStr) ?: return
            val json = JSONObject(body)
            if (json.optString("status") != "1") {
                Log.w(TAG, "高德天气查询失败: ${json.optString("info")} / ${json.optString("infocode")}")
                return
            }
            val lives = json.optJSONArray("lives") ?: return
            if (lives.length() == 0) return
            val live = lives.optJSONObject(0) ?: return

            val desc = optText(live, "weather")
            val temp = optText(live, "temperature")
            lastWeatherText = when {
                desc.isNotEmpty() && temp.isNotEmpty() -> "$desc $temp°C"
                desc.isNotEmpty() -> desc
                else -> return
            }
            postUpdate()
        } catch (e: Exception) {
            Log.w(TAG, "天气查询异常: ${e.message}")
        }
    }

    /** 统一的 GET 请求，带超时与 UA */
    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) RadioFM-AMap")
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP 请求异常: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        return null
    }

    /** 高德部分字段在取不到值时返回空数组 [] 而不是空字符串，这里统一处理 */
    private fun optText(json: JSONObject, key: String): String {
        val value = json.opt(key) ?: return ""
        return when (value) {
            is String -> value
            is JSONArray -> if (value.length() > 0) value.optString(0) else ""
            else -> ""
        }
    }

    private fun postUpdate() {
        mainHandler.post {
            onUpdate(lastLocationText, lastWeatherText)
        }
    }
}
