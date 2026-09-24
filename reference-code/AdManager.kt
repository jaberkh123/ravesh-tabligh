package com.sudoku.shahr.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ────────────────────────────────────────────────────────────────────────────
 *  مدیریت تبلیغات — اتصال به شبکهٔ تبلیغ «تبلیغ» (Tabligh Ad Network)
 * ────────────────────────────────────────────────────────────────────────────
 *
 *  طبق مستند رسمی اتصال (tabligh_icon.md):
 *   • سرور: https://golestanjaber.ir  →  GET /api/v2/api.php?app=<slug>&only=icons
 *   • پاسخ JSON شامل گروه آیکون‌های تبلیغاتی (icons[]) است.
 *   • چرخش آیکون‌ها سمتِ سرور و زمان‌محور است؛ اپ فقط هر دوره دوباره درخواست می‌دهد.
 *   • تپ روی هر آیکون باید فقط «click_url» همان آیکون را باز کند (شمارندهٔ آمار
 *     + ریدایرکت خودکار به صفحهٔ دانلود). هرگز download_url مستقیم باز نمی‌شود.
 *   • خطاها و نبودِ اینترنت → بخش تبلیغ مخفی می‌شود؛ هیچ‌وقت کرش یا قفل نمی‌کند.
 */

/** تنظیمات اتصال به سرور تبلیغات — اسلاگ فقط در همین یک نقطه تعریف می‌شود. */
object AdsConfig {
    const val BASE_URL = "https://golestanjaber.ir"

    /** slug همین اپ در پنل تبلیغات (اپ‌ها → tab تبلیغ). قبل از انتشار در پنل ثبت شود. */
    const val APP_SLUG = "shahr-sudoku"

    /** فقط اگر در پنل برای این اپ کلید تعریف شده باشد مقدار بدهید؛ در غیر این صورت خالی بماند. */
    const val API_KEY: String = ""
}

/** یک آیتم آیکون تبلیغاتی — معادل هر عضو آرایهٔ icons[] در پاسخ API. */
data class IconAd(
    val slot: Int,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val clickUrl: String? = null,
    val downloadUrl: String? = null,
    val clicks: Int? = null,
)

/** وضعیت UI تبلیغات؛ در هر حالت خطا/خالی → Hidden (بخش تبلیغ رندر نمی‌شود). */
sealed interface AdsUiState {
    data object Loading : AdsUiState
    data class Ready(
        val icons: List<IconAd>,
        /** نسبت تصویر آیکون‌ها از سرور؛ "1:1" یا "9:3" */
        val iconsRatio: String?,
        /** ثانیهٔ پیشنهادی تا درخواست بعدی (بر اساس next_slot_in سرور) */
        val refreshAfterSeconds: Int,
    ) : AdsUiState

    data object Hidden : AdsUiState
}

object AdManager {

    /**
     * کلید اصلی فعال‌سازی تبلیغات.
     * true = اپ به API تبلیغات وصل می‌شود و بنر آیکونی صفحهٔ خانه را نمایش می‌دهد.
     * false = هیچ درخواستی زده نمی‌شود و فقط جایگاه‌های خالی (AdSlot) دیده می‌شوند.
     */
    const val ADS_ENABLED = true

    /** وضعیت جاری که کامپوزبل AdIconsBanner از آن استفاده می‌کند. */
    var state by mutableStateOf<AdsUiState>(AdsUiState.Loading)
        private set

    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null
    private var initialized = false
    private var lastFetchAtMs = 0L

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * از MainActivity صدا زده می‌شود؛ حلقهٔ دریافت تبلیغ را راه می‌اندازد.
     * (امضای متد برای سازگاری با کد فعلی دست‌نخورده مانده است.)
     */
    fun init(context: Context, lifecycleScope: CoroutineScope) {
        if (!ADS_ENABLED || initialized) return
        initialized = true
        scope = lifecycleScope
        startLoop()
    }

    /** نوسازی دستی — حلقهٔ فعلی را قطع و از نو شروع می‌کند (بلافاصله یک fetch تازه). */
    fun triggerManualRefresh(context: Context, lifecycleScope: CoroutineScope) {
        if (!ADS_ENABLED) return
        if (!initialized) {
            init(context, lifecycleScope)
            return
        }
        loopJob?.cancel()
        startLoop()
    }

    /**
     * با هر بازگشت به foreground صدا زده می‌شود (از onResume اکتیویتی).
     * فقط وقتی بنر Hidden است فوراً دوباره fetch می‌کنیم (مثلاً سرور تازه آپدیت شده
     * یا آیکون‌های جدید در پنل فعال شده‌اند)؛ با فاصلهٔ حداقل ۶۰ ثانیه بین fetchها.
     * حالت Ready دست نمی‌خورد تا قانون «حداکثر یک fetch در دوره» حفظ شود.
     */
    fun onAppForegrounded() {
        if (!ADS_ENABLED || !initialized) return
        if (state !is AdsUiState.Hidden) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFetchAtMs < 60_000L) return
        loopJob?.cancel()
        startLoop()
    }

    // ── حلقهٔ دریافت با زمان‌بندیِ سمت سرور ──────────────────────────────────

    private fun startLoop() {
        val s = scope ?: return
        loopJob = s.launch {
            while (isActive) {
                val resp = fetchIcons()

                state = when {
                    resp == null               -> AdsUiState.Hidden          // خطای شبکه/پارس
                    resp.error != null         -> AdsUiState.Hidden          // خطای API (§ خطاهای مستند)
                    resp.icons.isEmpty()       -> AdsUiState.Hidden          // دسته هست ولی آیکونی نیست
                    else                       -> AdsUiState.Ready(resp.icons, resp.iconsRatio, resp.refreshAfterSeconds)
                }

                lastFetchAtMs = android.os.SystemClock.elapsedRealtime()

                // سیاست تلاش مجدد طبق مستند:
                //  • خطای ترنسپورت → ۵ دقیقه بعد
                //  • خطای API → ۱۵ دقیقه بعد
                //  • موفق و خالی (دسته بدون آیکون فعال) → ۲ دقیقه بعد؛ چون دورهٔ
                //    next_slot_in سرور فقط وقتی معنا دارد که چیزی برای چرخش هست.
                //  • موفق و پر → طبق next_slot_in سرور (حداقل ۶۰ ثانیه؛ حداکثر یک fetch در دوره)
                val waitSec = when {
                    resp == null             -> 5L * 60L
                    resp.error != null       -> 15L * 60L
                    resp.icons.isEmpty()     -> 2L * 60L
                    else                     -> maxOf(60L, resp.refreshAfterSeconds.toLong())
                }
                delay(waitSec * 1000L)
            }
        }
    }

    // ── نتیجهٔ پارس‌شدهٔ API ─────────────────────────────────────────────────

    private class TablighIconsResult(
        val error: String?,
        val icons: List<IconAd>,
        val iconsRatio: String?,
        val refreshAfterSeconds: Int,
    )

    /**
     * GET  {BASE_URL}/api/v2/api.php?app=<slug>&only=icons
     * پاسخ سبک فقط-آیکون؛ در خطا همیشه فیلد error داخل JSON هست (حتی با HTTP 200).
     * هر خطای ترنسپورت/پارس → null.
     */
    private suspend fun fetchIcons(): TablighIconsResult? = withContext(Dispatchers.IO) {
        try {
            var url = "${AdsConfig.BASE_URL}/api/v2/api.php?app=${urlEncode(AdsConfig.APP_SLUG)}"
            url += "&only=icons"
            AdsConfig.API_KEY.takeIf { it.isNotBlank() }?.let { url += "&key=${urlEncode(it)}" }

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .build()

            val body = http.newCall(request).execute().use { resp ->
                resp.body?.string() ?: return@withContext null
            }
            parseIcons(body)
        } catch (e: Exception) {
            Log.d(TAG, "fetch failed: ${e.message}")
            null
        }
    }

    /** پارس پاسخ JSON؛ بر اساس فیلد error تصمیم می‌گیریم (نه کد HTTP). */
    private fun parseIcons(body: String): TablighIconsResult? {
        return try {
            val obj = JSONObject(body)

            val error = obj.optStringOrNull("error")
            if (error != null) {
                Log.d(TAG, "api error: $error")
                return TablighIconsResult(error = error, icons = emptyList(), iconsRatio = null, refreshAfterSeconds = 0)
            }

            // نسبت تصویر: نسخهٔ جدید سرور «icons_ratio» می‌دهد؛ نسخهٔ قدیمی «ratio».
            val ratio = obj.optStringOrNull("icons_ratio") ?: obj.optStringOrNull("ratio")

            // زمان‌بندی چرخش: نسخهٔ جدید فیلدهای icons_* را می‌دهد؛ قدیمی next_slot_in/period_seconds.
            val nextSlotIn = obj.optIntOrNull("icons_next_slot_in")
                ?: obj.optIntOrNull("next_slot_in")
            val period = obj.optIntOrNull("icons_period_seconds")
                ?: obj.optIntOrNull("period_seconds")

            val icons = mutableListOf<IconAd>()
            val arr = obj.optJSONArray("icons")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val clickUrl = o.optStringOrNull("click_url")
                    // آیتم بدون تصویر یا بدون لینک کلیک برای نمایش بی‌معنی است؛ رد می‌شود.
                    val imageUrl = o.optStringOrNull("image_url") ?: continue
                    icons.add(
                        IconAd(
                            slot = o.optIntOrNull("slot") ?: (i + 1),
                            title = o.optStringOrNull("title"),
                            description = o.optStringOrNull("description"),
                            imageUrl = imageUrl,
                            clickUrl = clickUrl,
                            downloadUrl = o.optStringOrNull("download_url"),
                            clicks = o.optIntOrNull("clicks"),
                        )
                    )
                }
            } else {
                // سازگاری با سرور قدیمی (فرمت تک‌بنری): آرایهٔ icons[] وجود ندارد ولی
                // فیلدهای تکی سطح بالا همان آیتم فعلی‌اند — طبق مستند، آن‌ها را به‌عنوان
                // «یک آیتم» نمایش می‌دهیم.
                val imageUrl = obj.optStringOrNull("image_url")
                val clickUrl = obj.optStringOrNull("click_url")
                if (imageUrl != null && clickUrl != null) {
                    icons.add(
                        IconAd(
                            slot = obj.optIntOrNull("slot") ?: 1,
                            title = obj.optStringOrNull("title"),
                            description = obj.optStringOrNull("description"),
                            imageUrl = imageUrl,
                            clickUrl = clickUrl,
                            downloadUrl = obj.optStringOrNull("download_url")
                                ?: obj.optStringOrNull("target_url"),
                            clicks = obj.optIntOrNull("clicks"),
                        )
                    )
                }
            }

            val refresh = nextSlotIn?.takeIf { it > 0 }
                ?: period?.takeIf { it > 0 }
                ?: 7200
            TablighIconsResult(error = null, icons = icons, iconsRatio = ratio, refreshAfterSeconds = refresh)
        } catch (e: Exception) {
            Log.d(TAG, "parse failed: ${e.message}")
            null
        }
    }

    // ── کلیک روی آیکون ───────────────────────────────────────────────────────

    /**
     * قانون طلایی ۱ و ۲ مستند: تپ کاربر فقط و فقط «click_url» همان آیکون را
     * با یک مرورگر واقعی باز می‌کند تا شمارندهٔ کلیک سرور ثبت شود.
     */
    fun openAd(context: Context, clickUrl: String?) {
        val url = clickUrl?.takeIf { it.startsWith("http") } ?: return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ── ابزارها ──────────────────────────────────────────────────────────────

    private const val TAG = "AdManager"

    private fun urlEncode(v: String): String =
        java.net.URLEncoder.encode(v, "UTF-8")

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return try { getString(key) } catch (e: Exception) { null }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return try { getInt(key) } catch (e: Exception) { null }
    }
}
