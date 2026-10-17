package pro.potoki.bekon.agent.setup

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * OEM setup knowledge (assets/oem_setup.json, partly adapted from dontkillmyapp.com, CC BY 4.0)
 * matched to this device, plus live status of each Bekon requirement where detectable.
 */
class DeviceSetupGuide(
    private val ctx: Context,
    private val projectionActive: () -> Boolean? = { null },
) {
    private val data: JsonObject by lazy {
        Json.parseToJsonElement(ctx.assets.open("oem_setup.json").bufferedReader().readText()).jsonObject
    }

    private fun prop(k: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, k) as? String)?.ifBlank { null }
    } catch (e: Exception) { null }

    fun rom(): JsonObject = buildJsonObject {
        listOf(
            "miui" to "ro.miui.ui.version.name", "hyperos" to "ro.mi.os.version.name",
            "emui" to "ro.build.version.emui", "harmony" to "hw_sc.build.platform.version",
            "magicos" to "ro.build.version.magic", "coloros" to "ro.build.version.opporom",
            "oxygen" to "ro.oxygen.version", "realmeui" to "ro.build.version.realmeui",
            "funtouch" to "ro.vivo.os.version", "oneui" to "ro.build.version.oneui",
        ).forEach { (n, k) -> prop(k)?.let { put(n, it) } }
    }

    fun vendor(): JsonObject? {
        val m = Build.MANUFACTURER.lowercase(); val b = Build.BRAND.lowercase()
        return data["vendors"]!!.jsonArray.map { it.jsonObject }.firstOrNull { v ->
            v["match"]!!.jsonArray.any { val s = (it as JsonPrimitive).content; m.contains(s) || b.contains(s) }
        }
    }

    private fun applies(step: JsonObject): Boolean {
        val sdk = Build.VERSION.SDK_INT
        val min = (step["minSdk"] as? JsonPrimitive)?.content?.toInt() ?: 0
        val max = (step["maxSdk"] as? JsonPrimitive)?.content?.toInt() ?: 999
        return sdk in min..max
    }

    fun steps(): List<JsonObject> {
        val common = data["common"]!!.jsonArray.map { it.jsonObject }
        val v = vendor()?.get("steps")?.jsonArray?.map { it.jsonObject } ?: emptyList()
        // Order: restricted settings, accessibility, notifications, vendor steps, generic battery, projection.
        val head = common.filter { it.s("req") in setOf("restricted_settings", "accessibility", "notifications") }
        val tail = common.filter { it.s("req") in setOf("battery", "projection") }
        return (head + v + tail).filter(::applies)
    }

    /** true/false when detectable, null when unknown. */
    fun status(req: String): Boolean? = when (req) {
        "accessibility" -> (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').any { it.startsWith(ctx.packageName + "/") }
        "battery" -> if (Build.VERSION.SDK_INT >= 23)
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) else true
        "notifications" -> NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        "projection" -> projectionActive()
        "restricted_settings" -> if (Build.VERSION.SDK_INT < 33) true else restrictedAllowed()
        else -> null
    }

    private fun restrictedAllowed(): Boolean? = try {
        if (status("accessibility") == true) true else {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.unsafeCheckOpNoThrow("android:access_restricted_settings", ctx.applicationInfo.uid, ctx.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        }
    } catch (e: Exception) { null }

    fun guide(): JsonObject {
        val v = vendor()
        val list = steps()
        return buildJsonObject {
            put("device", buildJsonObject {
                put("manufacturer", Build.MANUFACTURER); put("brand", Build.BRAND); put("model", Build.MODEL)
                put("sdk", Build.VERSION.SDK_INT); put("release", Build.VERSION.RELEASE)
                put("rom", rom())
            })
            put("language", lang())
            put("vendor", v?.s("id") ?: "generic")
            v?.get("dkma")?.let { put("moreInfo", it) }
            v?.get("notes")?.let { put("vendorNotes", it) }
            put("steps", buildJsonArray {
                list.forEach { st ->
                    add(buildJsonObject {
                        st.forEach { (k, e) -> if (k != "intents") put(k, e) }
                        put("titleLocalized", st.loc("title")); put("pathLocalized", st.loc("path"))
                        put("canOpen", (st["intents"] as? JsonArray)?.isNotEmpty() == true)
                        val s = status(st.s("req"))
                        put("status", if (s == null) "unknown" else if (s) "ok" else "todo")
                    })
                }
            })
            put("attribution", data["attribution"]!!)
            put("text", buildString {
                append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ${rom()}\n")
                list.forEachIndexed { i, st ->
                    val s = status(st.s("req"))
                    val mark = if (s == null) "?" else if (s) "✓" else "✗"
                    append("${i + 1}. [$mark] ${st.s("id")}: ${st.loc("title")} — ${st.loc("path")}\n")
                }
                append(data["attribution"]!!.let { (it as JsonPrimitive).content })
            })
        }
    }

    /** Try each intent of [stepId] in order; returns description of what opened. */
    fun open(stepId: String): String {
        extraIntent(stepId)?.let { (name, i) ->
            val r = Launcher.launch(ctx, i, name, stepId)
            return r.text
        }
        val st = steps().firstOrNull { it.s("id") == stepId }
            ?: data["common"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.s("id") == stepId }
            ?: throw IllegalArgumentException("unknown step $stepId; see device_setup_guide")
        if (st.s("req") == "accessibility" &&
            !pro.potoki.bekon.agent.consent.Consent.accepted(ctx, pro.potoki.bekon.agent.consent.Consent.Kind.A11Y)
        ) {
            // Play policy: the user must see and accept the prominent disclosure first.
            pro.potoki.bekon.agent.consent.Consent.showDisclosure(ctx, pro.potoki.bekon.agent.consent.Consent.Kind.A11Y)
            return "opened Bekon's accessibility disclosure; the user must tap Accept before the Accessibility settings open"
        }
        val pkg = ctx.packageName
        // Our own label ("Bekon Agent"), never a hardcoded "Bekon": other pro.* Bekon apps may be installed.
        val label = try { ctx.packageManager.getApplicationLabel(ctx.applicationInfo).toString() } catch (_: Exception) { "Bekon Agent" }
        val tried = mutableListOf<String>()
        val background = !pro.potoki.bekon.agent.status.AgentAlert.inForeground()
        for (e in st["intents"]?.jsonArray ?: JsonArray(emptyList())) {
            val o = e.jsonObject
            val i = if (o.s("bekon") == "projection") {
                ctx.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            } else Intent().apply {
                o.s("action").ifEmpty { null }?.let { action = it }
                o.s("component").ifEmpty { null }?.let { component = ComponentName.unflattenFromString(it) }
                o.s("data").ifEmpty { null }?.let { data = Uri.parse(it.replace("{pkg}", pkg)) }
                o.s("extraPkg").ifEmpty { null }?.let { putExtra(it, pkg) }
                (o["extras"] as? JsonObject)?.forEach { (k, v) -> putExtra(k, (v as JsonPrimitive).content.replace("{pkg}", pkg).replace("{label}", label)) }
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Settings apps (e.g. MIUI Security) keep old tasks: without CLEAR_TASK the target
            // activity is brought to front under a stale stack (Other permissions instead of Autostart).
            if (o.s("bekon") != "projection") i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val what = o.s("component").ifEmpty { o.s("action").ifEmpty { "launcher" } }
            if (background) {
                if (o.s("bekon") != "projection" && i.resolveActivity(ctx.packageManager) == null) {
                    tried += "$what: not resolvable"; continue
                }
                return Launcher.launch(ctx, i, st.loc("title"), what, st.loc("path")).text
            }
            try {
                pro.potoki.bekon.agent.status.AgentAlert.lastSetupIntent = i
                ctx.startActivity(i)
                val ex = i.extras?.keySet()?.joinToString { k -> "$k=${i.extras?.get(k)}" }
                return "opened $what${if (ex.isNullOrEmpty()) "" else " [$ex]"} for $pkg (after ${tried.size} fallback(s))"
            }
            catch (ex: Exception) { tried += "$what: ${ex.javaClass.simpleName}" }
        }
        throw IllegalStateException("no screen opened for $stepId. tried: ${tried.joinToString("; ")}. Guide the user manually: ${st.loc("path")}")
    }

    /** Device language for step texts: ru or en. */
    private fun lang(): String = if (java.util.Locale.getDefault().language == "ru") "ru" else "en"
    private fun JsonObject.loc(k: String): String =
        (this[k] as? JsonObject)?.let { o -> o.s(lang()).ifEmpty { o.s("en") } } ?: s(k)

    /** One checklist row for the in-app wizard (same steps as device_setup_guide). */
    data class UiStep(val id: String, val req: String, val title: String, val path: String, val status: Boolean?, val canOpen: Boolean)

    fun uiSteps(): List<UiStep> = steps().map { st ->
        UiStep(st.s("id"), st.s("req"), st.loc("title"), st.loc("path"), status(st.s("req")),
            (st["intents"] as? JsonArray)?.isNotEmpty() == true)
    }

    /** Built-in screens that are not OEM steps. */
    private fun extraIntent(id: String): Pair<String, Intent>? {
        val ru = lang() == "ru"
        return when (id) {
            "app_details" -> (if (ru) "Сведения о приложении Bekon" else "Bekon app info") to
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            "play_listing" -> "Google Play: Bekon Agent" to
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ctx.packageName}"))
            else -> null
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content ?: ""
}
