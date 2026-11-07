package com.perkus.imagoedit.ui.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perkus.imagoedit.BuildConfig
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.Feedback
import com.perkus.imagoedit.ui.common.AppDialog
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.AppSwitch
import com.perkus.imagoedit.ui.common.AppTitle
import com.perkus.imagoedit.ui.common.Bar
import com.perkus.imagoedit.ui.common.ButtonRow
import com.perkus.imagoedit.ui.common.DialogButton
import com.perkus.imagoedit.ui.common.IconBtn
import com.perkus.imagoedit.ui.theme.BtnGray
import com.perkus.imagoedit.ui.theme.BtnGreen
import com.perkus.imagoedit.ui.theme.LinkBlue
import com.perkus.imagoedit.ui.theme.ScreenBg
import com.perkus.imagoedit.ui.theme.SideBarBg
import com.perkus.imagoedit.ui.theme.SmallShape

private const val GITHUB_URL = "https://github.com/Perkus007/ImagoEdit"
private const val TELEGRAM_URL = "https://t.me/Perkus_Live"
private val DONATIONS = listOf(
    "Bitcoin (BTC)" to "bc1q7wvwc7hdw4qhnj0xfxach7hp5d6mls7q885mm4",
    "Ethereum" to "0x6B1F94654724e8472c8ACd08C0fF48C5bD8ac540",
    "GRAM (TON)" to "UQDcPWrL3_jEEYQV_4SVRmfvHchsmIoC8pIDJ-CSTy5_6h5T",
    "SOL (Solana)" to "5VatikQKyBaRyYcQQQGPKwtoB4nkfoxf7MWxNWyBn58J",
    "USDT (TON)" to "UQDcPWrL3_jEEYQV_4SVRmfvHchsmIoC8pIDJ-CSTy5_6h5T",
    "USDT (TRX)" to "TJR4DT2fvfZfms9kg8u1Ci7xUXdpVtLzkd",
    "BNB (BSC)" to "0x6B1F94654724e8472c8ACd08C0fF48C5bD8ac540",
    "TRON (TRX)" to "TJR4DT2fvfZfms9kg8u1Ci7xUXdpVtLzkd"
)

private const val COPYRIGHT = "© 2026 Perkus"
private const val LICENSE_NAME = "GPL-3.0-or-later"
private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

// Third-party code and fonts in the APK: their notices ship with the binary (texts: res/values/licenses.xml)
private val NOTICES = listOf(
    "libjpeg-turbo" to R.string.notice_libjpeg_turbo,
    "libwebp, libyuv" to R.string.notice_bsd3,
    "libavif" to R.string.notice_libavif,
    "dav1d" to R.string.notice_dav1d,
    "avif-coder" to R.string.notice_avif_coder,
    "Eigen" to R.string.notice_eigen,
    "Fonts: Caveat, Handjet, Tiny5" to R.string.notice_ofl,
    "AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines; Rust: std, tealdust, maroontree, hpvca, hpvcd, pic-scale, yuv, moxcms, gainforge, bytemuck, num-traits, thiserror, anyhow, libc, log, jni, ndk-sys; font: Roboto Slab" to R.string.notice_apache
)
private val NOTICES_W = 600.dp

// Тег → название на самом языке; "en" — по умолчанию (values/)
private val LANGUAGES = listOf("en" to "English", "ru" to "Русский", "uk" to "Українська")

private val TICKER_W = 100.dp // адреса начинаются с одной линии
private val LinkStyles = TextLinkStyles(SpanStyle(color = LinkBlue))

/** Over the menu: the grid scroll is kept */
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val activity = LocalActivity.current
    val settings = remember { AppSettings(context) }
    var vibration by remember { mutableStateOf(settings.vibration) }
    var sounds by remember { mutableStateOf(Feedback.sounds) }
    var showLicense by remember { mutableStateOf(false) }
    var showNotices by remember { mutableStateOf(false) }
    val language = LocalConfiguration.current.locales[0].language.takeIf { c -> LANGUAGES.any { it.first == c } } ?: "en"

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Bar {
                IconBtn(AppIcons.Cancel, onClick = onClose)
                AppTitle(Modifier.weight(1f))
            }
            // No stretch overscroll: it resamples the layer, text pixels shift
            Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState(), overscrollEffect = null).padding(16.dp), Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.app_info), fontSize = 14.sp)
                    DONATIONS.forEach { (ticker, address) -> CoinRow(ticker, address) { context.copyToClipboard(ticker, address) } }
                }
                Column(Modifier.weight(1f), Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.language), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    ButtonRow {
                        LANGUAGES.forEach { (code, name) ->
                            DialogButton(name, if (code == language) BtnGreen else BtnGray, Modifier.weight(1f)) {
                                // Applied on Activity creation (withAppLanguage)
                                if (code != language) { settings.language = code; activity?.recreate() }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        SwitchRow(stringResource(R.string.vibration), vibration, Modifier.weight(1f)) { vibration = it; settings.vibration = it }
                        SwitchRow(stringResource(R.string.sounds), sounds, Modifier.weight(1f)) { sounds = it; Feedback.sounds = it }
                    }
                    HorizontalDivider()
                    Text("${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME} · $COPYRIGHT", fontSize = 14.sp)
                    LinkText(LinkAnnotation.Clickable("license", LinkStyles) { showLicense = true }, LICENSE_NAME, "${stringResource(R.string.license)} ")
                    LinkText(LinkAnnotation.Clickable("notices", LinkStyles) { showNotices = true }, stringResource(R.string.third_party))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        LinkText(LinkAnnotation.Url(GITHUB_URL, LinkStyles), "GitHub")
                        LinkText(LinkAnnotation.Url(TELEGRAM_URL, LinkStyles), "Telegram")
                    }
                }
            }
        }
    }

    if (showLicense) AppDialog(LICENSE_NAME, { showLicense = false }, borderColor = SideBarBg) {
        Text(stringResource(R.string.license_desc), fontSize = 14.sp, textAlign = TextAlign.Center)
        LinkText(LinkAnnotation.Url(LICENSE_URL, LinkStyles), "gnu.org/licenses/gpl-3.0")
    }

    // Lazy by paragraph: the whole text is a long layout on a budget device
    if (showNotices) AppDialog(stringResource(R.string.third_party), { showNotices = false }, NOTICES_W, SideBarBg) {
        val res = LocalResources.current
        val notices = remember(LocalConfiguration.current) { NOTICES.map { (name, id) -> name to res.getString(id).split("\n\n") } }
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), overscrollEffect = null) {
            notices.forEach { (name, paragraphs) ->
                item { Text(name, Modifier.padding(top = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                items(paragraphs) { Text(it, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun CoinRow(ticker: String, address: String, onCopy: () -> Unit) =
    Row(Modifier.height(IntrinsicSize.Min), Arrangement.spacedBy(8.dp)) {
        Text(ticker, Modifier.width(TICKER_W).fillMaxHeight().border(1.5.dp, BtnGray, SmallShape).wrapContentHeight(Alignment.CenterVertically).padding(vertical = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(address, Modifier.weight(1f).fillMaxHeight().background(ScreenBg, SmallShape).clickable(onClick = onCopy).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }

@Composable
private fun SwitchRow(label: String, checked: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) =
    Row(modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp)
        AppSwitch(checked)
    }

@Composable
private fun LinkText(link: LinkAnnotation, text: String, prefix: String = "") =
    Text(buildAnnotatedString { append(prefix); withLink(link) { append(text) } }, fontSize = 14.sp)

/** Android 13+ confirms the copy itself; before — a toast */
@Suppress("UsePropertyAccessSyntax")
private fun Context.copyToClipboard(label: String, text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Feedback.tap()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
}