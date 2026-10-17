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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** One UI = rounded white cards on light grey; FLAT = MIUI / stock Android white list. */
enum class GuideStyle { ONEUI, FLAT }
enum class Kind { LIST, TOGGLE, DIALOG }

/** A row in the mock screen. [value] = right-side text (e.g. "Выкл."), [target] = the pulsing ring. */
data class MockRow(val text: Int, val value: Int? = null, val target: Boolean = false, val icon: Long? = null)

data class GuideStep(
    val caption: Int, val title: Int, val rows: List<MockRow> = emptyList(),
    val kind: Kind = Kind.LIST, val style: GuideStyle = GuideStyle.FLAT,
    val menuDots: Boolean = false, val dialogText: Int? = null,
)

private const val BLUE = 0xFF3E91FF
private fun r(t: Int, icon: Long? = null, value: Int? = null) = MockRow(t, value, false, icon)
private fun target(t: Int, icon: Long? = null, value: Int? = null) = MockRow(t, value, true, icon)

fun restrictedSteps(oem: GuideOem = GuideOem.STOCK): List<GuideStep> {
    val st = if (oem == GuideOem.SAMSUNG) GuideStyle.ONEUI else GuideStyle.FLAT
    return listOf(
        GuideStep(R.string.g_cap_r1, R.string.g_scr_appinfo, listOf(r(R.string.g_row_permissions), r(R.string.g_row_notifications), r(R.string.g_row_battery)), style = st, menuDots = true),
        GuideStep(R.string.g_cap_r2, R.string.g_scr_appinfo, listOf(target(R.string.g_row_allow_restricted), r(R.string.g_row_uninstall)), style = st),
    )
}

/**
 * Paths as seen on real devices: One UI (Samsung, ru) and MIUI 12 (Redmi, captured 2026-10-17:
 * Accessibility → "Downloaded services" → app → "Accessibility" switch). Stock = AOSP/Pixel.
 */
fun a11ySteps(oem: GuideOem): List<GuideStep> = when (oem) {
    GuideOem.SAMSUNG -> {
        val s = GuideStyle.ONEUI
        listOf(
            GuideStep(R.string.g_cap_s1, R.string.g_scr_settings, listOf(
                r(R.string.g_row_battery, 0xFF35C759), r(R.string.g_row_general, 0xFF8E8E93),
                target(R.string.g_row_a11y, 0xFF34A853), r(R.string.g_row_update, 0xFF7B61FF)), style = s),
            GuideStep(R.string.g_cap_s2, R.string.g_row_a11y, listOf(
                r(R.string.g_row_visibility), r(R.string.g_row_interaction), r(R.string.g_row_advanced),
                target(R.string.g_row_installed_apps)), style = s),
            GuideStep(R.string.g_cap_app, R.string.g_row_installed_apps, listOf(target(R.string.g_row_bekon, value = R.string.g_off)), style = s),
            GuideStep(R.string.g_cap_toggle, R.string.g_row_bekon, listOf(target(R.string.g_off)), kind = Kind.TOGGLE, style = s),
            GuideStep(R.string.g_cap_allow, R.string.g_row_bekon, listOf(r(R.string.g_row_deny), target(R.string.g_row_allow)),
                kind = Kind.DIALOG, style = s, dialogText = R.string.g_dlg_full_control),
        )
    }
    GuideOem.XIAOMI -> listOf(
        GuideStep(R.string.g_cap_x1, R.string.g_scr_settings, listOf(r(R.string.g_row_display), r(R.string.g_row_battery), target(R.string.g_row_additional))),
        GuideStep(R.string.g_cap_x2, R.string.g_row_additional, listOf(r(R.string.g_row_datetime), r(R.string.g_row_language), target(R.string.g_row_a11y))),
        GuideStep(R.string.g_cap_x3, R.string.g_row_a11y, listOf(r(R.string.g_row_volume_shortcut), r(R.string.g_row_a11y_menu), target(R.string.g_row_downloaded_services))),
        GuideStep(R.string.g_cap_app, R.string.g_row_downloaded_services, listOf(target(R.string.g_row_bekon, value = R.string.g_off))),
        GuideStep(R.string.g_cap_toggle, R.string.g_row_bekon, listOf(target(R.string.g_row_a11y)), kind = Kind.TOGGLE),
        GuideStep(R.string.g_cap_ok, R.string.g_row_bekon, listOf(r(R.string.g_row_deny), target(R.string.g_row_ok)), kind = Kind.DIALOG, dialogText = R.string.g_dlg_full_control),
    )
    GuideOem.STOCK -> listOf(
        GuideStep(R.string.g_cap_g1, R.string.g_scr_settings, listOf(r(R.string.g_row_display), r(R.string.g_row_battery), target(R.string.g_row_a11y), r(R.string.g_row_system))),
        GuideStep(R.string.g_cap_g2, R.string.g_row_a11y, listOf(r(R.string.g_row_visibility), r(R.string.g_row_interaction), target(R.string.g_row_downloaded))),
        GuideStep(R.string.g_cap_app, R.string.g_row_downloaded, listOf(target(R.string.g_row_bekon, value = R.string.g_off))),
        GuideStep(R.string.g_cap_toggle, R.string.g_row_bekon, listOf(target(R.string.g_row_use_bekon)), kind = Kind.TOGGLE),
        GuideStep(R.string.g_cap_allow, R.string.g_row_bekon, listOf(r(R.string.g_row_deny), target(R.string.g_row_allow)), kind = Kind.DIALOG, dialogText = R.string.g_dlg_full_control),
    )
}

/** Fixed-height mock so the whole step card fits one screen (no scrolling). */
private val MOCK_H = 216.dp

/** Swipeable illustrated guide: mock screens with a pulsing ring on the control to tap. No permissions needed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IllustratedGuide(steps: List<GuideStep>) {
    val pager = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalPager(state = pager, pageSpacing = 16.dp) { i ->
            val st = steps[i]
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${i + 1}/${steps.size} · " + stringResource(st.caption), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(40.dp))
                MockScreen(st)
            }
        }
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) {
                Text(stringResource(R.string.g_back))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                repeat(steps.size) { k ->
                    Box(Modifier.padding(3.dp).size(7.dp).background(
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
private fun pulse(): Float = rememberInfiniteTransition(label = "ring").animateFloat(
    0.25f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a").value

@Composable
private fun MockScreen(st: GuideStep) {
    val oneUi = st.style == GuideStyle.ONEUI
    val bg = if (oneUi) Color(0xFFF2F2F4) else Color.White
    val p = pulse()
    val ring = Color(BLUE).copy(alpha = p)
    Box(
        Modifier.fillMaxWidth().height(MOCK_H).clip(RoundedCornerShape(16.dp)).background(bg)
            .border(1.dp, Color(0x22000000), RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color(0xFF222222), fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(st.title), color = Color(0xFF111111), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (st.menuDots) Box(Modifier.size(32.dp).border(3.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
                    Text("⋮", color = Color(0xFF222222), fontSize = 18.sp)
                }
            }
            when (st.kind) {
                Kind.LIST, Kind.DIALOG -> RowsCard(st.rows.takeIf { st.kind == Kind.LIST } ?: listOf(r(R.string.g_row_bekon, value = R.string.g_off)), oneUi, ring)
                Kind.TOGGLE -> ToggleCard(st.rows.first(), oneUi, ring)
            }
        }
        if (st.kind == Kind.DIALOG) DialogMock(st, ring)
    }
}

@Composable
private fun RowsCard(rows: List<MockRow>, oneUi: Boolean, ring: Color) {
    val shape = RoundedCornerShape(if (oneUi) 18.dp else 0.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Color.White)) {
        rows.take(4).forEachIndexed { i, row ->
            val rs = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 4.dp, vertical = 2.dp)
                    .then(if (row.target) Modifier.border(3.dp, ring, rs) else Modifier).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.icon?.let {
                    Box(Modifier.size(20.dp).background(Color(it), CircleShape)); Spacer(Modifier.width(10.dp))
                }
                Text(stringResource(row.text), color = Color(0xFF111111), fontSize = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                row.value?.let { Text(stringResource(it), color = Color(0xFF8A8A8E), fontSize = 13.sp) }
                if (!oneUi) Text("  ›", color = Color(0xFFB0B0B5), fontSize = 16.sp)
            }
            if (oneUi && i < rows.size - 1 && i < 3) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Color(0xFFEDEDED)))
        }
    }
}

@Composable
private fun ToggleCard(row: MockRow, oneUi: Boolean, ring: Color) {
    val shape = RoundedCornerShape(if (oneUi) 22.dp else 12.dp)
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(shape).background(Color.White).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(row.text), color = Color(0xFF111111), fontSize = 15.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // Switch shown OFF with the ring: the human turns it on in real Settings.
        Box(Modifier.border(3.dp, ring, CircleShape).padding(4.dp)) {
            Box(Modifier.size(width = 44.dp, height = 24.dp).background(Color(0xFFD6D6DA), CircleShape).padding(3.dp),
                contentAlignment = Alignment.CenterStart) {
                Box(Modifier.size(18.dp).background(Color.White, CircleShape))
            }
        }
    }
    Text(stringResource(R.string.g_toggle_hint), color = Color(0xFF6E6E73), fontSize = 12.sp, maxLines = 2)
}

@Composable
private fun DialogMock(st: GuideStep, ring: Color) {
    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(10.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            st.dialogText?.let { Text(stringResource(it), color = Color(0xFF111111), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 3) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                st.rows.forEach { b ->
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).then(if (b.target) Modifier.border(3.dp, ring, RoundedCornerShape(16.dp)) else Modifier)
                        .padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text(stringResource(b.text), color = if (b.target) Color(BLUE) else Color(0xFF444444), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
