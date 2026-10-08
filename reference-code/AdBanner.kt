/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  AdBanner.kt — کومپوزابل‌های UI بنر تبلیغ (روش تبلیغ / ravesh-tabligh)
 * ─────────────────────────────────────────────────────────────────────────────
 *  کد واقعیِ در حال استفاده در اپ «شهر سودوکو» — با کمی بازآرایی برای استفادهٔ مستقل.
 *
 *  محتوا:
 *   • AdSlot           — جایگاه خالی خط‌چین (وقتی تبلیغ خاموش است)
 *   • adRatioToAspect  — تبدیل «1:1»/«9:3» سرور به float
 *   • AdIconsBanner    — بنر اصلی: ۳ آیکون در عرض، ترتیب رندوم، ورود پلکانی (v2)
 *   • IconAdItem       — سلول هر آیکون: ۹۰٪ عرض، انیمیشن ورود فقط یک‌بار (v2)
 *
 *  🆕 v2 — فیکس باگ اسکرول:
 *   در v1، انیمیشن ورودِ هر آیکون با هر (re)composition دوباره پخش می‌شد؛ چون
 *   LazyRow آیتم‌های خارج از دید را dispose می‌کند، اسکرول به راست باعث
 *   «آمدن آهستهٔ» آیکون‌های جدید (تاخیر index×۱ثانیه) و اسکرول به چپ باعث
 *   «غیب و ظاهر شدن دوبارهٔ» آیکون‌های قبلی می‌شد!
 *   در v2 وضعیت «دیده‌شده» هر آیکون در سطحِ بنر (نه آیتم) نگه‌داری می‌شود:
 *   هر آیکون فقط یک بار انیمیشن ورود می‌گیرد و در اسکرول‌های بعدی دیگر هرگز
 *   غیب و ظاهر نمی‌شود — آیکون یک‌بار که لود شد، لودشده است.
 *
 *  ⚠️ نمادهای وابسته به اپ میزبان (خودت تطبیق بده):
 *   • AdManager / AdsUiState / IconAd  → از reference-code/AdManager.kt
 *   • LocalDarkTheme                   → CompositionLocal سادهٔ Boolean اپ نمونه؛
 *                                        جایگزین: `isSystemInDarkTheme()` یا تم خودت
 *   • DarkSurface / DarkOnBackground   → دو رنگ دارک اپ نمونه؛ جایگزین: رنگ تم خودت
 *   • PersianFontFamily                → فونت فارسی اپ نمونه؛ جایگزین: FontFamily.Default
 *
 *  وابستگی‌ها: coil-compose (AsyncImage)، androidx.compose.*، kotlinx-coroutines
 */
package com.example.ads // ← پکیج خودت را بگذار

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

// این‌ها را با تم خودت جایگزین کن:
val LocalDarkTheme = compositionLocalOf { false }
val DarkSurface = Color(0xFF1B2A38)
val DarkOnBackground = Color(0xFFE0F7FA)
val PersianFontFamily: FontFamily = FontFamily.Default

/**
 * جایگاه خالی تبلیغ — کادر خط‌چین «جایگاه تبلیغ».
 * فقط وقتی ADS_ENABLED=false استفاده می‌شود؛ در production حالت Hidden یعنی هیچ.
 */
@Composable
fun AdSlot(slotHeight: Dp, modifier: Modifier = Modifier) {
    val isDark = LocalDarkTheme.current
    val borderColor = if (isDark) DarkOnBackground.copy(alpha = 0.25f)
    else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val textColor = if (isDark) DarkOnBackground.copy(alpha = 0.4f)
    else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(slotHeight)
            .background(Color.Transparent)
            .border(1.5.dp, borderColor, RoundedCornerShape(16.dp))
            .then(Modifier),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Campaign, contentDescription = null, tint = textColor, modifier = Modifier.size(14.dp))
            Text(
                text = "جایگاه تبلیغ",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = textColor, fontWeight = FontWeight.Bold, fontSize = 10.sp
                )
            )
        }
    }
}

/** تبدیل رشتهٔ نسبت تصویر API («1:1» یا «9:3») به float عرض÷ارتفاع. */
fun adRatioToAspect(ratio: String?, fallback: Float = 1f): Float =
    ratio?.split(":")?.takeIf { it.size == 2 }
        ?.mapNotNull { it.trim().toFloatOrNull() }
        ?.takeIf { it.size == 2 && it[1] != 0f }
        ?.let { (w, h) -> w / h }
        ?: fallback

/**
 * بنر تبلیغات آیکونی (صفحهٔ خانه + صفحات داخلی) — طبق مستند اتصال (tabligh_icon.md):
 *  • فقط در حالت Ready و با آیکون‌های واقعیِ API رندر می‌شود؛
 *    در Loading/Hidden هیچ‌چیز نشان داده نمی‌شود (بدون خطا، بدون کرش، بدون قفل شدن).
 *  • در عرض صفحه دقیقاً ۳ آیکون جا می‌گیرد؛ اگر تعداد آیکون‌ها بیشتر باشد،
 *    ردیف به‌صورت افقی اسکرول می‌خورد (سرریز به سمت راست).
 *  • تپ روی هر آیکون → باز شدن مقصد همان آیکون: تبلیغِ کافه‌بازار مستقیم در خود
 *    اپ بازار باز می‌شود (bazaar://details?id + setPackage — بدون مرورگر)، سایر
 *    مقصدها با مرورگر. آمار کلیک در هر دو حالت ثبت می‌شود (AdManager.openAd).
 *  • انیمیشن ورود پلکانی: هر بار صفحه لود می‌شود آیکون‌ها به ترتیب از چپ به راست،
 *    هر کدام ۱ ثانیه بعد از قبلی، با fade-in نرم ظاهر می‌شوند (جلب توجه به تبلیغ).
 */
@Composable
fun AdIconsBanner(modifier: Modifier = Modifier) {
    val st = AdManager.state
    if (st !is AdsUiState.Ready || st.icons.isEmpty()) return

    val ctx = LocalContext.current
    val isDark = LocalDarkTheme.current
    val cardBg = if (isDark) DarkSurface.copy(alpha = 0.55f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val borderColor = if (isDark) DarkOnBackground.copy(alpha = 0.25f)
    else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    val headerColor = if (isDark) DarkOnBackground.copy(alpha = 0.55f)
    else MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    val titleColor = if (isDark) DarkOnBackground.copy(alpha = 0.8f)
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // عرض هر آیتم = (عرض داخل کارت − دو فاصلهٔ بین سه آیتم) ÷ ۳ → دقیقاً ۳ آیکون در صفحه
        val hPad = 12.dp
        val spacing = 10.dp
        val itemWidth = ((maxWidth - hPad * 2) - spacing * 2) / 3
        val iconAspect = adRatioToAspect(st.iconsRatio)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg, RoundedCornerShape(16.dp))
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(vertical = 10.dp)
        ) {
            // سربرگ کوچک بنر
            Row(
                modifier = Modifier.padding(horizontal = hPad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(Icons.Default.Campaign, contentDescription = null, tint = headerColor, modifier = Modifier.size(13.dp))
                Text(
                    text = "پیشنهاد ما",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = headerColor, fontWeight = FontWeight.Bold, fontSize = 10.sp
                    ),
                    fontFamily = PersianFontFamily
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ردیف آیکون‌ها — ترتیب آیکون‌ها هر بار رندوم است (اولین آیکون هر fetch
            // می‌تواند متفاوت باشد)؛ جهتِ ردیف همیشه LTR می‌ماند تا آیکون‌های بیشتر
            // با اسکرول به سمتِ راست ظاهر شوند (مستقل از زبان دستگاه).
            //
            // 🆕 v2 — حافظهٔ «آیکون‌های دیده‌شده» در سطح بنر:
            // این مجموعه در طول عمر بنر (و بین اسکرول‌های LazyRow) زنده می‌ماند؛
            // هر آیکون فقط یک بار انیمیشن ورود می‌گیرد و بعد از آن، در هر
            // اسکرول (چپ/راست) فوراً و بدون هیچ انیمیشنی دیده می‌شود.
            // کلید remember عمداً st.icons است: با refresh واقعیِ داده از سرور،
            // مجموعه از نو ساخته می‌شود و ورود پلکانی طبق قانون ۷ دوباره پخش می‌شود.
            val appearedSlots = remember(st.icons) { androidx.compose.runtime.mutableStateMapOf<Int, Boolean>() }
            val displayIcons = remember(st.icons) { st.icons.shuffled() }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = hPad),
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    itemsIndexed(displayIcons, key = { _, it -> it.slot }) { index, icon ->
                        IconAdItem(
                            icon = icon,
                            appearIndex = index,
                            alreadyShown = icon.slot in appearedSlots,
                            onAppeared = { appearedSlots[icon.slot] = true },
                            reloadKey = st.icons,
                            itemWidth = itemWidth,
                            aspect = iconAspect,
                            titleColor = titleColor
                        ) {
                            AdManager.openAd(ctx, icon)
                        }
                    }
                }
            }
        }
    }
}

/**
 * سلول یک آیکون تبلیغ: تصویر ۹۰٪ عرض سلول + عنوان یک‌خطی زیر آن.
 *
 * 🆕 v2 — انیمیشن ورود فقط یک‌بار (فیکس باگ اسکرول):
 *  • در v1، LaunchedEffect با هر compose دوباره از صفر پخش می‌شد؛ چون LazyRow
 *    آیتم‌های خارج از دید را dispose می‌کند، اسکرول به راست «آمدن آهسته»
 *    (تاخیر index×۱ثانیه) و اسکرول به چپ «غیب و ظاهر شدن مجدد» ایجاد می‌کرد.
 *  • در v2:
 *      – اگر آیکون قبلاً یک‌بار دیده شده (alreadyShown)، مستقیم و بدون هیچ
 *        انیمیشنی نمایش داده می‌شود — در هیچ اسکرولی دیگر غیب/ظاهر نمی‌شود.
 *      – ورود پلکانی ۱ثانیه‌ای فقط برای ۳ آیکونِ پنجرهٔ اولِ صفحه (قانون ۷) است؛
 *        آیکون‌های بیرون از پنجره با یک فید کوتاه (۲۵۰ms) و فقط بار اول وارد
 *        می‌شوند تا حس «لود دوباره» ندهد.
 *      – تصویر با ImageRequest دارای memoryCacheKey ثابت و crossfade خاموش لود
 *        می‌شود تا خود Coil هم هیچ‌گاه دوباره‌سازی بصری ایجاد نکند.
 */
@Composable
private fun IconAdItem(
    icon: IconAd,
    appearIndex: Int,
    alreadyShown: Boolean,
    onAppeared: () -> Unit,
    reloadKey: Any?,
    itemWidth: Dp,
    aspect: Float,
    titleColor: Color,
    onClick: () -> Unit
) {
    val appearAlpha = remember { Animatable(if (alreadyShown) 1f else 0f) }
    LaunchedEffect(reloadKey, icon.slot) {
        if (alreadyShown) {
            // قبلاً دیده شده: بدون هیچ انیمیشنی — فقط مطمئن شو کاملاً پیدا است.
            if (appearAlpha.value < 1f) appearAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        appearAlpha.snapTo(0f)
        // از همین لحظه «دیده‌شده» علامت بخور تا حتی اگر آیتم وسط انیمیشن از دید
        // خارج شد و دوباره برگشت، هیچ‌وقت انیمیشن از نو پخش نشود.
        onAppeared()
        if (appearIndex < 3) {
            delay(appearIndex * 1_000L)           // پنجرهٔ اول: آیکون n → n ثانیه بعد
            appearAlpha.animateTo(1f, tween(700)) // fade-in نرم ~۰٫۷ ثانیه
        } else {
            appearAlpha.animateTo(1f, tween(250)) // بیرون از پنجره: فید کوتاه، فقط بار اول
        }
    }

    val imageCtx = androidx.compose.ui.platform.LocalContext.current
    val imageModel = remember(icon.slot, icon.imageUrl, imageCtx) {
        coil.request.ImageRequest.Builder(imageCtx)
            .data(icon.imageUrl)
            .memoryCacheKey("tabligh-icon-${icon.slot}")
            .crossfade(false)
            .build()
    }

    Column(
        modifier = Modifier
            .graphicsLayer { alpha = appearAlpha.value }
            .width(itemWidth)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = imageModel,
            contentDescription = icon.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(12.dp))
        )
        icon.title?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = titleColor, fontSize = 10.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                fontFamily = PersianFontFamily,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}
