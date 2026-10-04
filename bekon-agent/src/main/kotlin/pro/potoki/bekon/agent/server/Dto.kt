package pro.potoki.bekon.agent.server

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val ok: Boolean, val capture: Boolean, val touch: Boolean, val keyboard: Boolean, val tunnel: Boolean)

@Serializable
data class SnapshotItem(val ref: String, val name: String, val x: Int, val y: Int, val bounds: List<Int> = emptyList(), val clickable: Boolean = false)

@Serializable
data class SnapshotResponse(val screenW: Int, val screenH: Int, val imageW: Int, val imageH: Int, val items: List<SnapshotItem>)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class TapRequest(val x: Int, val y: Int)

@Serializable
data class SwipeRequest(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val ms: Int = 300)

@Serializable
data class TypeRequest(val text: String)

@Serializable
data class OkResponse(val ok: Boolean)
