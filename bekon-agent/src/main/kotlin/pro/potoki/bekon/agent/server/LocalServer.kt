package pro.potoki.bekon.agent.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import pro.potoki.bekon.agent.core.ActionExecutor
import pro.potoki.bekon.agent.core.CapabilityException
import pro.potoki.bekon.agent.mcp.McpHandler

/**
 * Flat on-phone HTTP + MCP server, bound to 127.0.0.1 only.
 * No `/tunnels/:id` on the phone — that shape belongs to the Node API in front of WLYA.
 */
class LocalServer(
    private val port: Int,
    private val executor: ActionExecutor,
    private val mcp: McpHandler,
) {
    private var engine: CIOApplicationEngine? = null
    val boundPort: Int get() = port

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "127.0.0.1", module = {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            routing {
                get("/health") {
                    val h = executor.health()
                    call.respond(
                        HealthResponse(
                            ok = true,
                            capture = h["capture"] ?: false,
                            touch = h["touch"] ?: false,
                            keyboard = h["keyboard"] ?: false,
                            tunnel = h["tunnel"] ?: false,
                        )
                    )
                }
            get("/snapshot") {
                try {
                    val s = executor.snapshot()
                    call.respond(
                        SnapshotResponse(
                            captureW = s.captureW, captureH = s.captureH,
                            screenW = s.screenW, screenH = s.screenH,
                            items = s.items.map {
                                SnapshotItem(
                                    ref = it.ref, name = it.label,
                                    x = it.x, y = it.y,
                                    bounds = it.bounds?.toList() ?: emptyList(),
                                    clickable = it.clickable,
                                )
                            },
                        )
                    )
                } catch (e: CapabilityException) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "conflict"))
                }
            }
            get("/screenshot") {
                try {
                    val f = executor.screenshot()
                    call.respondBytes(f.bytes, ContentType.Image.JPEG)
                } catch (e: CapabilityException) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "conflict"))
                }
            }
            post("/tap") {
                try {
                    val b = call.receive<TapRequest>()
                    call.respond(OkResponse(executor.tap(b.x, b.y)))
                } catch (e: CapabilityException) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "conflict"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
                }
            }
            post("/swipe") {
                try {
                    val b = call.receive<SwipeRequest>()
                    call.respond(OkResponse(executor.swipe(b.x1, b.y1, b.x2, b.y2, b.ms)))
                } catch (e: CapabilityException) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "conflict"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
                }
            }
            post("/type") {
                try {
                    val b = call.receive<TypeRequest>()
                    call.respond(OkResponse(executor.type(b.text)))
                } catch (e: CapabilityException) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "conflict"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
                }
            }
            post("/mcp") { mcp.handle(call) }
            get("/mcp") { mcp.handle(call) }
        }
        })
        engine?.start(wait = false)
    }

    fun stop() {
        engine?.stop(200, 2000)
        engine = null
    }
}
