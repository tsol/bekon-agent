package pro.potoki.bekon.agent.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pro.potoki.bekon.agent.R
import pro.potoki.bekon.agent.setup.DeviceSetupGuide

/** OEM family for the illustrated Accessibility guide (decided via DeviceSetupGuide ROM/vendor detection). */
enum class GuideOem { SAMSUNG, XIAOMI, STOCK }

fun guideOem(guide: DeviceSetupGuide): GuideOem {
    val rom = try { guide.rom() } catch (_: Exception) { null }
    val m = (android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND).lowercase()
    return when {
        rom?.containsKey("oneui") == true || m.contains("samsung") -> GuideOem.SAMSUNG
        rom?.containsKey("miui") == true || rom?.containsKey("hyperos") == true ||
            listOf("xiaomi", "redmi", "poco").any { m.contains(it) } -> GuideOem.XIAOMI
        else -> GuideOem.STOCK
    }
}

enum class RowKind { ROW, TOGGLE, BUTTON }
data class MockRow(val text: Int, val kind: RowKind = RowKind.ROW, val target: Boolean = false)
/** One illustrated step: caption + a mock Settings screen ([dialog] = rows drawn as a dialog). */
data class GuideStep(val caption: Int, val screen: Int, val rows: List<MockRow>, val dialog: Boolean = false, val menuDots: Boolean = false)

private fun t(r: Int) = MockRow(r, target = true)

fun restrictedSteps() = listOf(
    GuideStep(R.string.g_cap_r1, R.string.g_scr_appinfo, listOf(MockRow(R.string.g_row_open), MockRow(R.string.g_row_forcestop), MockRow(R.string.g_row_permissions)), menuDots = true),
    GuideStep(R.string.g_cap_r2, R.string.g_scr_appinfo, listOf(t(R.string.g_row_allow_restricted), MockRow(R.string.g_row_uninstall)), dialog = true),
)

fun a11ySteps(oem: GuideOem): List<GuideStep> {
    val toggle = GuideStep(R.string.g_cap_toggle, R.string.g_row_bekon,
        listOf(MockRow(R.string.g_row_use_bekon, RowKind.TOGGLE, target = true), MockRow(R.string.g_row_shortcut)))
    val allow = GuideStep(R.string.g_cap_allow, R.string.g_scr_dialog,
        listOf(MockRow(R.string.g_row_deny, RowKind.BUTTON), MockRow(R.string.g_row_allow, RowKind.BUTTON, target = true)), dialog = true)
    return when (oem) {
        GuideOem.SAMSUNG -> listOf(
            GuideStep(R.string.g_cap_s1, R.string.g_scr_settings, listOf(MockRow(R.string.g_row_display), MockRow(R.string.g_row_battery), t(R.string.g_row_a11y), MockRow(R.string.g_row_general))),
            GuideStep(R.string.g_cap_s2, R.string.g_row_a11y, listOf(MockRow(R.string.g_row_vision), MockRow(R.string.g_row_hearing), t(R.string.g_row_installed_apps))),
            GuideStep(R.string.g_cap_app, R.string.g_row_installed_apps, listOf(t(R.string.g_row_bekon))),
            toggle, allow,
        )
        GuideOem.XIAOMI -> listOf(
            GuideStep(R.string.g_cap_x1, R.string.g_scr_settings, listOf(MockRow(R.string.g_row_display), t(R.string.g_row_additional), MockRow(R.string.g_row_battery))),
            GuideStep(R.string.g_cap_x2, R.string.g_row_additional, listOf(MockRow(R.string.g_row_datetime), t(R.string.g_row_a11y), MockRow(R.string.g_row_language))),
            GuideStep(R.string.g_cap_x3, R.string.g_row_a11y, listOf(MockRow(R.string.g_row_vision), t(R.string.g_row_downloaded))),
            GuideStep(R.string.g_cap_app, R.string.g_row_downloaded, listOf(t(R.string.g_row_bekon))),
            toggle, allow,
        )
        GuideOem.STOCK -> listOf(
            GuideStep(R.string.g_cap_g1, R.string.g_scr_settings, listOf(MockRow(R.string.g_row_display), MockRow(R.string.g_row_battery), t(R.string.g_row_a11y), MockRow(R.string.g_row_system))),
            GuideStep(R.string.g_cap_g2, R.string.g_row_a11y, listOf(MockRow(R.string.g_row_vision), t(R.string.g_row_downloaded))),
            GuideStep(R.string.g_cap_app, R.string.g_row_downloaded, listOf(t(R.string.g_row_bekon))),
            toggle, allow,
        )
    }
}

/** Swipeable illustrated guide: mock screens with a pulsing ring on the row to tap. No permissions needed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IllustratedGuide(steps: List<GuideStep>) {
    val pager = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalPager(state = pager, pageSpacing = 16.dp) { i ->
            val st = steps[i]
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.g_step_n, i + 1, steps.size) + "  " + stringResource(st.caption),
                    style = MaterialTheme.typography.bodyMedium)
                MockScreen(st)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) {
                Text(stringResource(R.string.g_back))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                repeat(steps.size) { k ->
                    Box(Modifier.padding(3.dp).size(8.dp).background(
                        if (k == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                }
            }
            TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < steps.size - 1) {
                Text(stringResource(R.string.g_next))
            }
        }
    }
}

@Composable
private fun MockScreen(st: GuideStep) {
    val pulse by rememberInfiniteTransition(label = "ring").animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    val ring = Color(0xFFE53935)
    val frame = MaterialTheme.colorScheme.outline
    Column(
        Modifier.fillMaxWidth().border(1.dp, frame, RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹  " + stringResource(st.screen), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (st.menuDots) Box(Modifier.size(36.dp).border(3.dp, ring.copy(alpha = pulse), CircleShape), contentAlignment = Alignment.Center) {
                Text("⋮", style = MaterialTheme.typography.titleMedium)
            }
        }
        val body = Modifier.fillMaxWidth().let {
            if (st.dialog) it.padding(horizontal = 12.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(8.dp) else it
        }
        Column(body, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val buttons = st.rows.all { it.kind == RowKind.BUTTON }
            if (buttons) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                st.rows.forEach { r -> MockItem(r, ring, pulse, Modifier) }
            } else st.rows.forEach { r -> MockItem(r, ring, pulse, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun MockItem(r: MockRow, ring: Color, pulse: Float, mod: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        mod.then(if (r.target) Modifier.border(3.dp, ring.copy(alpha = pulse), shape) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(r.text), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = if (r.kind == RowKind.TOGGLE) Modifier.weight(1f) else Modifier)
        if (r.kind == RowKind.TOGGLE) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(width = 40.dp, height = 22.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp),
                contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.size(16.dp).background(Color.White, CircleShape))
            }
        }
    }
}
