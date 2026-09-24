# ۲. لایهٔ داده — AdManager

> کد کامل واقعی: [`reference-code/AdManager.kt`](../reference-code/AdManager.kt)
> این فایل «چرا این‌طوری نوشته شده» را توضیح می‌دهد.

## ۲.۱ معماری — state machine سه‌حالته

```
                 fetch موفق + icons پر
        ┌────────────────────────────────────┐
        │                                    ▼
   ┌─────────┐   init()/refresh    ┌──────────────┐
   │ Loading │ ──────────────────► │  هر fetch:   │
   └─────────┘                     └──────┬───────┘
                                          │ state = وقتی:
                            ┌─────────────┼─────────────┐
                            ▼             ▼             ▼
                       resp == null   resp.error    icons.isEmpty()
                            │             │             │
                            └─────────────┴──────┬──────┘
                                                 ▼
                                          ┌──────────┐
                                          │  Hidden  │  (بنر هیچ رندر نمی‌کند)
                                          └────┬─────┘
                                               │ retry تایمر / onAppForegrounded
                                               ▼
                                          دوباره fetch
```

- `state` یک `mutableStateOf` است — کامپوزابل‌ها خودکار واکنش نشان می‌دهند.
- **هیچ حالت «Error با UI» وجود ندارد.** خطا فقط یعنی Hidden.

## ۲.۲ حلقهٔ fetch — قلب تایمرها

```kotlin
while (isActive) {
    val resp = fetchIcons()
    state = when { ... }                       // جدول تصمیم در 01-api.md §1.5
    lastFetchAtMs = SystemClock.elapsedRealtime()
    delay(waitSec * 1000L)                     // waitSec طبق جدول retry
}
```

**چرا `SystemClock.elapsedRealtime()` و نه `System.currentTimeMillis()`؟**
چون کاربر می‌تواند ساعت دستگاه را دستی جابه‌جا کند؛ `elapsedRealtime` از بوت، تغییرناپذیر است.
گارد «حداقل ۶۰ ثانیه بین دو fetch» روی همین تکیه دارد.

## ۲.۳ onAppForegrounded — ناجیِ بنر مُرده ⭐

```kotlin
fun onAppForegrounded() {
    if (!ADS_ENABLED || !initialized) return
    if (state !is AdsUiState.Hidden) return     // Ready را دست نمی‌زند (قانون یک fetch در دوره)
    val now = SystemClock.elapsedRealtime()
    if (now - lastFetchAtMs < 60_000L) return   // گارد حداقل ۶۰ ثانیه
    loopJob?.cancel(); startLoop()              // فوری دوباره تلاش
}
```

از `onResume()` اکتیویتی صدا زده می‌شود. سناریوی واقعی که این را ساخت:

> سرور تازه آپدیت شده بود؛ اپ در همان لحظه fetch زده و `Hidden` شده بود. کد قدیمی قرار بود
> ~۲ ساعت صبر کند. کاربر گزارش داد: **«هیچ تبلیغی نمایش داده نمی‌شود»** — سرور سالم، اپ سالم،
> فقط تایمر غلط. فیکس دوبخشی شد: (۱) retry های کوتاه حالت خطا، (۲) تلاش فوری هنگام foreground.

## ۲.۴ fetch و پارس — نکات کلیدی

- همهٔ شکست‌ها (شبکه، timeout، پارس) → `null` → Hidden. **هیچ استثنایی از object بیرون نمی‌زند.**
- آیتم بدون `image_url` رد می‌شود (حتی اگر بقیه‌اش کامل باشد).
- `click_url` گاهی null است (آیتم بدون لینک) — نمایش بده؟ نه: `openAd` خودش گارد دارد و
  تپ را بی‌صدا رد می‌کند؛ ولی آیتم را حذف نکن، شاید فقط این آیتم لینک نداشته باشد.
  *(در اپ نمونه: آیتم بدون image حذف می‌شود؛ بدون click_url نگه داشته می‌شود اما تپ کاری نمی‌کند.)*
- پارس با `org.json` (داخل اندروید) — نیازی به Gson/Moshi نیست، حجم صفر.

## ۲.۵ openAd — قانون طلایی ۱

```kotlin
fun openAd(context: Context, clickUrl: String?) {
    val url = clickUrl?.takeIf { it.startsWith("http") } ?: return   // گارد URL غیر http
    runCatching {                                                     // گارد نبود مرورگر
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
```

- فقط `click_url` — **هرگز** `download_url` (آمار کلیک از مسیر click.php ثبت می‌شود).
- `runCatching` حتی اگر هیچ مرورگری نبود هم کرش نمی‌کند.

## ۲.۶ کلید خاموش/روشن

`ADS_ENABLED = true|false` — وقتی false: هیچ fetch زده نمی‌شود و (اگر اپ خواست) می‌تواند
جایگاه خالی `AdSlot` (کادر خط‌چین «جایگاه تبلیغ») نشان دهد. برای دیباگ و بیلدهای بدون تبلیغ عالی است.

## ۲.۷ triggerManualRefresh

حلقه را قطع و از نو شروع می‌کند — یک fetch فوری. برای دکمهٔ مخفی دیباگ یا pull-to-refresh
(به‌شرط رعایت گارد ۶۰ ثانیه اگر خودت اضافه کردی).
