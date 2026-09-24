# کد مرجع — reference-code

> این کدها **عیناً** همان کدهای در حال استفاده در اپ «شهر سودوکو» هستند
> (با کمترین بازآرایی برای استفادهٔ مستقل). کپی‌شان کن و تطبیق بده — از حفظ بازنویسی نکن.

| فایل | نقش | کجا برود در اپ تو |
|---|---|---|
| [`AdManager.kt`](AdManager.kt) | لایهٔ داده: fetch، state machine، تایمرها، openAd | `data/` (یا هر پکیجی) |
| [`AdBanner.kt`](AdBanner.kt) | UI بنر: AdIconsBanner + انیمیشن پلکانی + AdSlot | `ui/` |

## چک‌لیست تطبیق بعد از کپی

1. **پکیج‌ها:** خط اول هر فایل را به پکیج پروژه‌ات تغییر بده.
2. **`AdsConfig.APP_SLUG`** — اسلاگ اپ خودت (از پنل تبلیغات).
3. **نمادهای تم در `AdBanner.kt`:** `LocalDarkTheme`، `DarkSurface`، `DarkOnBackground`،
   `PersianFontFamily` — یا از اپ نمونه بردار یا با پیش‌فرض‌های خودت جایگزین کن
   (بالای فایل هدنویس شده).
4. **MainActivity:**
   ```kotlin
   override fun onCreate(savedInstanceState: Bundle?) {
       super.onCreate(savedInstanceState)
       AdManager.init(applicationContext, lifecycleScope)
       ...
   }
   override fun onResume() {
       super.onResume()
       AdManager.onAppForegrounded()
   }
   ```
5. **جایگذاری UI:** `AdIconsBanner()` در صفحهٔ خانه و صفحات داخلی.

## وابستگی‌ها

```kotlin
// app/build.gradle.kts
implementation("io.coil-kt:coil-compose:2.6.0")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
```

## منبع اصلی (ریفرنس زندهٔ تولید)

- اپ نمونه: <https://github.com/jaberkh123/shahr-sudoku>
  - `app/src/main/java/com/sudoku/shahr/data/AdManager.kt`
  - `app/src/main/java/com/sudoku/shahr/ui/Screens.kt` (کومپوزابل‌های بنر)
- مستند رسمی شبکه: [`docs/tabligh_icon.md`](../docs/tabligh_icon.md)
