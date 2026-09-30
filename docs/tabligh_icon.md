# Tabligh Ad Network — AI-Agent Integration Guide

> **File:** `tabligh_icon.md` · **Audience:** AI coding agents (Cursor, Copilot, Cline, Claude Code, …)
> **Mission:** You are an AI agent asked to add advertising to an Android app (Kotlin + Jetpack Compose).
> This document is your **complete specification**. Follow it exactly; do not invent endpoints or fields.

**Ad server base URL:** `https://golestanjaber.ir`

---

## 0. Golden Rules (read first — violating these breaks ad stats)

| # | Rule | Why |
|---|------|-----|
| 1 | **Never open `download_url` directly.** For web destinations open `click_url`; for Bazaar destinations report the click first (`click_url` + `&via=app`, background GET) then open the Bazaar app via `bazaar://details?id=<package_name>` + `setPackage("com.farsitel.bazaar")`. | `click_url` is a redirect-tracker (`click.php`) that records the click. Opening the final link directly silently loses all statistics. `via=app` records the click without a redirect when the app itself opens Bazaar. |
| 2 | Web destinations: open `click_url` with an **Intent / Custom Tab** (a real browser), not with an in-app HTTP client. | The tracker filters bot User-Agents (`curl`, `wget`, `python`, `okhttp`-like, headless, empty UA). A raw HTTP GET from your app may be **redirected but not counted**. (`&via=app` requests are exempt — they are explicit click reports.) |
| 3 | Check the JSON `error` field, **not only the HTTP status code**. | Quirk: `no_category`, `no_ad`, `no_icon`, … return **HTTP 200** with an `error` field. |
| 4 | Re-fetch after `next_slot_in` seconds. Do **not** rotate ads locally by shuffling. | Rotation is **server-side and time-based**; the server already reorders `ads[]` so the current ad is first. |
| 5 | Respect the `ratio` field (`1:1` or `9:3`) when laying out images. | `1:1` = square banner/icon, `9:3` = wide banner. Wrong aspect ratio looks broken. |
| 6 | Treat `clicks` as **nullable and informational**. Do not gate UI on it. | The server may return `null` when the stats column is not installed. |
| 7 | Never log, hardcode, or commit the `api_key` (if the app has one). | The key travels in the query string; log leakage exposes it. |
| 8 | The app **must not crash or block** without network. Hide the ad container gracefully on failure. | Ads are optional decoration for the app's core function. |
| 9 | Fetch **at most once per rotation period** (or on app resume). Never poll in a tight loop. | The server is a small shared host; be polite. |
| 10 | Do **not** try to modify the server. This guide is **app-side only**. | Server (PHP + MySQL) is owned and already deployed by the app owner. |

---

## 1. System Overview

Tabligh is a small self-hosted ad network:

```
Android app ──GET──▶ https://golestanjaber.ir/api/v2/api.php?app=<slug>
                     │  JSON response (banners + icons)
                     ▼
User taps ad ──────▶ https://golestanjaber.ir/api/v2/click.php?ad=<id>&r=1_1
                     │  records click, then 302 redirect
                     ▼
Real destination ──▶ download_url (e.g. Play Store / website)
```

Concepts:

- **App** — one of the owner's Android apps, identified by a `slug` set by the owner in the admin panel. May optionally require an `api_key`.
- **Category** — a rotation group of ads. Two types: `banner` and `icon`. Each category has `period_seconds` (rotation period), `ratio` (`1:1` / `9:3`) and `max_ads` (0 = send all).
- **Ad** — one creative: `title`, `description`, `image_url`, `click_url` (destination stored server-side).
- **Rotation** — the server advances to the next ad every `period_seconds`. The response's `ads[]` array is ordered **current-first**.

---

## 2. API Contract — `GET /api/v2/api.php`

### 2.1 Request

```
GET https://golestanjaber.ir/api/v2/api.php?app=<slug>&only=all&key=<api_key>
```

| Parameter | Required | Values | Description |
|-----------|----------|--------|-------------|
| `app` | **yes** | slug string | App identifier. Get it from the owner or the admin panel. |
| `only` | no | `all` (default) · `banners` · `icons` | Lightweight partial responses for separate requests. |
| `key` | only if the app has an `api_key` | key string | If the app was configured with a key, requests without the correct `key` get `403 invalid_api_key`. |

- Method: **GET** only. Response: **JSON**, UTF-8. Headers include `Cache-Control: no-store`.
- URL-encode every parameter. Timeouts: connect 10 s, read 15 s (recommended).

### 2.2 Success response (HTTP 200)

```json
{
  "app": "example-app",
  "app_name": "Example App",

  "category": "بنرهای اصلی",
  "ratio": "1:1",
  "slot": 12,
  "title": "عنوان بنر فعلی",
  "description": "توضیح بنر فعلی",
  "image_url": "https://golestanjaber.ir/images/ad_20260921_101530_ab12cd34.png",
  "click_url": "https://golestanjaber.ir/api/v2/click.php?ad=12&r=1_1",
  "target_url": "https://example.com/download",
  "clicks": 1523,
  "period_seconds": 7200,
  "next_slot_in": 3600,
  "count": 5,
  "ads": [
    {
      "slot": 12,
      "title": "عنوان بنر فعلی",
      "description": "توضیح بنر فعلی",
      "image_url": "https://golestanjaber.ir/images/ad_20260921_101530_ab12cd34.png",
      "click_url": "https://golestanjaber.ir/api/v2/click.php?ad=12&r=1_1",
      "download_url": "https://example.com/download",
      "clicks": 1523
    }
  ],

  "icons_category": "آیکون‌های اپ",
  "icons_ratio": "1:1",
  "icons_period_seconds": 86400,
  "icons_next_slot_in": 4200,
  "icons_count": 8,
  "icons": [
    {
      "slot": 31,
      "title": "اپ خواهر",
      "description": "دانلود بازی سودوکو",
      "image_url": "https://golestanjaber.ir/images/ad_20260920_090000_fe34ba21.png",
      "click_url": "https://golestanjaber.ir/api/v2/click.php?ad=31&r=1_1",
      "download_url": "https://example.com/sudoku",
      "destination": "bazaar",
      "package_name": "com.example.sudoku",
      "clicks": 402
    }
  ]
}
```

### 2.3 Field reference

| Field | Type | Notes |
|-------|------|-------|
| `app`, `app_name` | string | Echoed slug and display name. |
| `category` | string | Name of the app's first active **banner** category. |
| `ratio` | string | `"1:1"` or `"9:3"` — aspect ratio of every image in this response. |
| `slot`, `title`, `description`, `image_url`, `click_url`, `target_url`, `clicks` | — | **Top-level single-ad fields = first item of `ads[]`** (backward compatibility for old apps). New code should read `ads[]`. |
| `period_seconds` | int | Rotation period of the banner category. |
| `next_slot_in` | int (seconds) | Time until the server rotates to the next ad. Schedule your refresh with this. |
| `count` | int | `ads.size`. |
| `ads[]` | array | The rotating banner group, **current ad first**, then the rest in rotation order. Show item 0 (classic single-banner mode) or all of them (group mode). |
| `ads[].slot` | int | Ad ID. Stable identifier — safe to use as a Compose `key()`. |
| `ads[].click_url` | URL | **The tracker URL.** Web destination → open on tap. Bazaar destination → report the click with `&via=app` (§3). See Golden Rule 1. |
| `ads[].download_url` | URL | Final destination (after the tracker's redirect). Informational — do not open directly. |
| `ads[].destination` | string | `"web"` (browser behavior) or `"bazaar"` (open the Bazaar app directly). Absent on old servers → treat as `"web"`. |
| `ads[].package_name` | string \| null | Bazaar package name of the destination app — meaningful only when `destination="bazaar"`. If `destination` is missing but `click_url`/`download_url` points to `cafebazaar.ir/app/<pkg>`, derive the package from the URL. |
| `ads[].clicks` | int \| null | Lifetime click count (may be `null`). |
| `icons_*` / `icons[]` | — | Same structure for the app's first active **icon** category. |

Omission rules:

- If the app has **no active banner category**, the whole banner block is replaced by `"count": 0, "ads": []` (and vice-versa for icons).
- `only=banners` strips the icon block; `only=icons` strips the banner block. Use `only=icons` when you fetch icons on a separate screen — the response is much lighter.

### 2.4 Error responses

| HTTP | `error` value | Meaning | Recommended client behavior |
|------|---------------|---------|------------------------------|
| 400 | `app_slug_required` | `app` parameter missing | Fix your request (bug). |
| 404 | `app_not_found` | Unknown slug | Show fallback, report — the slug is wrong. |
| 403 | `app_inactive` | App disabled by owner | Hide ads, retry later (e.g. next app start). |
| 403 | `invalid_api_key` | Missing/wrong `key` | Stop retrying with the same key; ask the owner. |
| **200** | `no_category` | App has no active category of the requested kind | Hide the ad container. |
| **200** | `no_icon_category` | Same, for `only=icons` | Hide the icon container. |
| **200** | `no_active_category` | Categories exist but all inactive | Hide, retry next session. |
| **200** | `no_ad` / `no_icon` | Category active but empty | Hide, retry next session. |
| 500 | `server_error` | Server-side failure (details only in server log) | Hide, retry with backoff. |

> ⚠️ The `no_*` family returns **HTTP 200**. Your parser must branch on `error != null` **before** touching `ads[]` / `icons[]`.

---

## 3. Click Tracking — `GET /api/v2/click.php`

```
GET https://golestanjaber.ir/api/v2/click.php?ad=<id>&r=1_1|9_3
```

- Responds **302** → the ad's real destination (or the site homepage if the ad/target is invalid).
- Counts the click **only** when: the target is valid, the User-Agent is not bot-like, and the same (ad + ratio + IP-hash) has not clicked within the last **60 seconds** (double-tap / refresh protection).
- Practical consequences for you:
  1. Open `click_url` in a browser/Custom Tab — that UA is counted.
  2. Do not worry about rapid double invocations; the server dedups them.
  3. Do not build the tracker URL yourself — always use the `click_url` the API gave you (it already encodes `ad` and `r`).

### 3.1 Package mode — `&via=app` (Bazaar ads)

```
GET https://golestanjaber.ir/api/v2/click.php?ad=<id>&r=1_1&via=app
```

When the ad's destination is Cafe Bazaar, the app opens **the Bazaar app itself**
(`bazaar://details?id=<package_name>` with `setPackage("com.farsitel.bazaar")`) — no browser
involved, so the redirect flow never runs. To keep the click counted, fire one background
(fire-and-forget) GET of `click_url + "&via=app"` **before** launching Bazaar:

- `via=app` bypasses the bot filter (it is an explicit user-tap report) and responds **204 No Content**
  with no redirect — nothing extra is downloaded.
- The 60-second dedup still applies; a failed ping is silently ignored (one lost stat, no UI impact).

---

## 4. Integration Plan (Android · Kotlin · Jetpack Compose)

Execute these steps in order. Do not skip the manifest or the click step.

1. **Confirm the `app` slug** with the owner. Slugs are owner-defined in the admin panel (*اپ‌ها → apps tab*), where each app has a ready-to-copy API URL. **Do not guess** — an unregistered slug returns `app_not_found`. Put the slug in `BuildConfig` or a single `AdsConfig` object — never scattered literals.
2. **Add the INTERNET permission** and network dependencies (§5.1, §5.2).
3. **Add data models + API client** (§5.3, §5.4).
4. **Add an AdsViewModel** that loads the response, exposes UI state, and schedules the next fetch (§5.5).
5. **Add Compose UI**: one banner slot (or a group row) + an icon strip (§5.6). Use the `ratio` field for aspect ratio.
6. **Wire the click**: `openAd(ctx, ad)` per item (Bazaar → direct app open + `via=app`; web → `click_url`) (§3, §3.1).
7. **Handle every error path** from §2.4 by hiding the container — never crashing, never showing an empty gray box.
8. **Test** against the checklist in §6 until every row passes.

---

## 5. Complete Kotlin Sample (copy-adapt into the app)

### 5.1 `AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

### 5.2 `build.gradle.kts` (module)

```kotlin
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("io.coil-kt:coil-compose:2.6.0")
}
// also enable in the android block:
// buildFeatures { buildConfig = true }
```

### 5.3 Config + data models

```kotlin
// AdsConfig.kt
object AdsConfig {
    const val BASE_URL = "https://golestanjaber.ir"
    // TODO: confirm with the owner and set the real slug for THIS app
    const val APP_SLUG = "CHANGE_ME"
    const val API_KEY: String? = null // set only if the owner enabled a key for this app
}

// AdsModels.kt
@kotlinx.serialization.Serializable
data class AdItem(
    val slot: Int,
    val title: String? = null,
    val description: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("click_url") val clickUrl: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    val clicks: Int? = null,
)

@kotlinx.serialization.Serializable
data class TablighResponse(
    val app: String? = null,
    @SerialName("app_name") val appName: String? = null,
    val error: String? = null,          // present on API-level errors (§2.4)
    val message: String? = null,

    // banner block
    val category: String? = null,
    val ratio: String? = null,
    val slot: Int? = null,
    val title: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("click_url") val clickUrl: String? = null,
    @SerialName("target_url") val targetUrl: String? = null,
    @SerialName("period_seconds") val periodSeconds: Int? = null,
    @SerialName("next_slot_in") val nextSlotIn: Int? = null,
    val count: Int? = null,
    val ads: List<AdItem> = emptyList(),

    // icon block
    @SerialName("icons_category") val iconsCategory: String? = null,
    @SerialName("icons_ratio") val iconsRatio: String? = null,
    @SerialName("icons_period_seconds") val iconsPeriodSeconds: Int? = null,
    @SerialName("icons_next_slot_in") val iconsNextSlotIn: Int? = null,
    @SerialName("icons_count") val iconsCount: Int? = null,
    val icons: List<AdItem> = emptyList(),
) {
    val hasBanners: Boolean get() = error == null && ads.isNotEmpty()
    val hasIcons: Boolean get() = error == null && icons.isNotEmpty()
    /** Seconds until the earliest rotation (banners vs icons). */
    val refreshAfterSeconds: Int
        get() = listOfNotNull(nextSlotIn, iconsNextSlotIn)
            .filter { it > 0 }
            .minOrNull()
            ?: (periodSeconds ?: iconsPeriodSeconds ?: 7200)
}
```

### 5.4 API client

```kotlin
// AdsApi.kt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object AdsApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    /** Fetch ads. @param only: null (=all), "banners" or "icons". Returns null on transport errors. */
    suspend fun fetch(only: String? = null): TablighResponse? = withContext(Dispatchers.IO) {
        try {
            var url = "${AdsConfig.BASE_URL}/api/v2/api.php?app=${enc(AdsConfig.APP_SLUG)}"
            only?.takeIf { it.isNotBlank() }?.let { url += "&only=${enc(it)}" }
            AdsConfig.API_KEY?.takeIf { it.isNotBlank() }?.let { url += "&key=${enc(it)}" }

            val body = http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                resp.body?.string() ?: return@withContext null
            }
            json.decodeFromString<TablighResponse>(body)
        } catch (e: Exception) {
            null // network/parse failure → caller shows fallback
        }
    }
}
```

### 5.5 ViewModel with server-driven auto-refresh

```kotlin
// AdsViewModel.kt
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AdsUiState {
    data object Loading : AdsUiState
    data class Ready(val data: TablighResponse) : AdsUiState
    data object Hidden : AdsUiState   // any error/empty case → hide the whole ad section
}

class AdsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<AdsUiState>(AdsUiState.Loading)
    val state = _state.asStateFlow()

    init { loadAndSchedule() }

    /** Fetch once, then re-fetch when the server says the rotation advances. */
    fun loadAndSchedule() = viewModelScope.launch {
        while (true) {
            val resp = AdsApi.fetch()
            _state.value = when {
                resp == null            -> AdsUiState.Hidden                    // transport error
                resp.error != null      -> AdsUiState.Hidden                    // API-level error (§2.4)
                else                    -> AdsUiState.Ready(resp)
            }
            if (resp == null) { delay(5 * 60_000L); continue }                  // offline → retry later
            val waitSec = if (resp.error != null) 15 * 60
                          else maxOf(60, resp.refreshAfterSeconds)              // ≥1 min between fetches
            delay(waitSec * 1000L)
        }
    }
}
```

### 5.6 Compose UI (banner + icon strip + click)

```kotlin
// AdsSection.kt
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

private fun openAd(context: android.content.Context, ad: AdItem) {
    // Bazaar package: server field, or auto-detected from legacy cafebazaar links
    val pkg = ad.packageName?.takeIf { it.isNotBlank() }
        ?: bazaarPackageFromUrl(ad.clickUrl)
        ?: bazaarPackageFromUrl(ad.downloadUrl)
    if (pkg != null) {
        // 1) report the click (204, no redirect) — stats stay intact
        pingClickCounter(ad.clickUrl)
        // 2) open the Bazaar app directly — no browser, no chooser
        val opened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("bazaar://details?id=$pkg"))
                    .setPackage("com.farsitel.bazaar")
            )
            true
        }.getOrDefault(false)
        if (opened) return
        // 3) Bazaar not installed → its web page (stats already recorded)
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cafebazaar.ir/app/$pkg")))
        }
        return
    }
    // Web destination — classic behavior: browser opens click_url (count + redirect)
    val url = ad.clickUrl?.takeIf { it.startsWith("http") } ?: return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** Aspect ratio from the API: "1:1" → 1f, "9:3" → 3f (width/height). */
private fun ratioOf(ratio: String?, fallback: Float = 1f): Float =
    ratio?.split(":")?.takeIf { it.size == 2 }
        ?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 2 && it[1] != 0f }
        ?.let { (w, h) -> w / h } ?: fallback

@Composable
fun AdsSection(vm: AdsViewModel = viewModel()) {
    val st by vm.state.collectAsState()
    when (val s = st) {
        is AdsUiState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.data.hasBanners) BannerCarousel(s.data)
            if (s.data.hasIcons) IconStrip(s.data)
        }
        else -> Unit // Loading / Hidden → render nothing, never crash
    }
}

@Composable
private fun BannerCarousel(resp: TablighResponse) {
    val ctx = LocalContext.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(resp.ads, key = { it.slot }) { ad ->
            Card(Modifier.width(if (resp.ratio == "9:3") 320.dp else 160.dp)) {
                Column(Modifier.clickable { openAd(ctx, ad) }) {
                    AsyncImage(
                        model = ad.imageUrl,
                        contentDescription = ad.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratioOf(resp.ratio)),
                    )
                    ad.title?.let {
                        Text(it, fontSize = 14.sp, maxLines = 1,
                             modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun IconStrip(resp: TablighResponse) {
    val ctx = LocalContext.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(resp.icons, key = { it.slot }) { icon ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(72.dp)
                    .clickable { openAd(ctx, icon) },
            ) {
                AsyncImage(
                    model = icon.imageUrl,
                    contentDescription = icon.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .aspectRatio(ratioOf(resp.iconsRatio))
                        .clip(RoundedCornerShape(14.dp)),
                )
                icon.title?.let {
                    Text(it, fontSize = 11.sp, maxLines = 1,
                         modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
```

Drop `<AdsSection />` at the bottom of your home screen — nothing renders while loading or on failure.

---

## 6. Testing Checklist

### 6.1 Server sanity (run from a terminal before touching code)

```bash
# NOTE: replace <SLUG> with the real slug the owner gave you.
# If you get {"error":"app_not_found"}, the slug is wrong — ask the owner.

# happy path — full response
curl -s "https://golestanjaber.ir/api/v2/api.php?app=<SLUG>" | python3 -m json.tool

# icons only (lightweight)
curl -s "https://golestanjaber.ir/api/v2/api.php?app=<SLUG>&only=icons"

# error cases (always work — useful to verify your error handling)
curl -s "https://golestanjaber.ir/api/v2/api.php"                 # 400 app_slug_required
curl -s "https://golestanjaber.ir/api/v2/api.php?app=nope-404"    # 404 app_not_found

# redirect check (curl's UA is bot-filtered: it follows the 302 but does NOT count a click —
# perfect for testing without polluting stats)
curl -s -o /dev/null -w "%{http_code} -> %{redirect_url}\n" \
  "https://golestanjaber.ir/api/v2/click.php?ad=<AD_ID>&r=1_1"
```

### 6.2 QA matrix — all rows must pass before you call the task done

| # | Check | Expected |
|---|-------|----------|
| 1 | App launch with network | Banner and/or icons render within ~2 s; nothing renders while loading. |
| 2 | Aspect ratio | Square images are square; `9:3` banners are wide — per the `ratio` field. |
| 3 | Tap banner/icon | Web destination → browser opens `click_url`, then lands on the real destination. Bazaar destination → background `&via=app` ping, then the Bazaar app opens directly on the package page. |
| 4 | Stats increment | Owner sees the click counter rise in the panel (wait > 60 s or use another device — dedup window). |
| 5 | Rotation | After `next_slot_in` the client re-fetches and a new first banner appears (server-side rotation). |
| 6 | Airplane mode on launch | No crash, no ANR; ad section simply absent; core app features unaffected. |
| 7 | Network drops mid-session | Existing ads may stay until next refresh; no crash; next fetch failure hides cleanly. |
| 8 | Wrong slug / disabled app | Container hidden, no error UI shown to the user. |
| 9 | `only=icons` screen (if used) | Icon strip loads independently; banner block absent in response. |
| 10 | No secrets | `api_key` (if any) only in `AdsConfig`/`BuildConfig`, never logged, never in VCS. |
| 11 | Politeness | At most one fetch per rotation period + on resume; no polling loops. |

---

## 7. Definition of Done

The integration is complete **only when**:

- [ ] The app compiles and the ad section renders with real server data.
- [ ] Banner clicks and icon clicks go through `click_url` (web) or `via=app` + `bazaar://details?id=` (Bazaar) and land on the correct destination (check 4).
- [ ] All rows of the QA matrix (§6.2) pass.
- [ ] Errors/offline hide the ad section without crashes.
- [ ] The slug lives in exactly one place (`AdsConfig`), and the diff contains no credentials.

---

## 8. Troubleshooting / FAQ

**Q: The response has `ads: []` and `count: 0` — is the API broken?**
A: No. The app's category is empty or inactive (§2.4 `no_ad` / `no_active_category` family). The owner must add/activate ads in the admin panel. Hide the container.

**Q: I get `403 invalid_api_key`.**
A: The owner enabled a key for this app. Get the key from the owner and set `AdsConfig.API_KEY`. Do not brute-force.

**Q: Clicks are not counted but the redirect works.**
A: Something opened the tracker with a bot-like User-Agent (raw HTTP client, empty UA) or the same device/ad repeated within 60 s. Open via a real browser intent.

**Q: Can I shuffle `ads[]` client-side for variety?**
A: No. The order is the rotation state; shuffling fights the server and makes `next_slot_in` meaningless. Item 0 is the current ad.

**Q: Can I cache images?**
A: Yes — images are immutable files with random names (`ad_<timestamp>_<hex>.png`). Let Coil/OkHttp cache them. Do **not** cache the JSON API response to disk beyond the current session (`Cache-Control: no-store` is intentional).

**Q: Where do `image_url` files live?**
A: `https://golestanjaber.ir/images/` (1:1) and `…/images_9_3/` (wide). They are public, stable URLs — hot-link them directly.

