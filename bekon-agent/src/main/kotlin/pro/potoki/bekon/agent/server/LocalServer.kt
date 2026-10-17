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
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.http.withCharset
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import pro.potoki.bekon.agent.access.ClientGate
import pro.potoki.bekon.agent.core.ActionExecutor
import pro.potoki.bekon.agent.core.CapabilityException
import pro.potoki.bekon.agent.core.ScreenshotDefaults
import pro.potoki.bekon.agent.mcp.McpHandler

/**
 * Flat on-phone HTTP + MCP server, bound to 127.0.0.1 only.
 * No `/tunnels/:id` on the phone — that shape belongs to the Node API in front of WLYA.
 */
class LocalServer(
    private val port: Int,
    private val executor: ActionExecutor,
    private val mcp: McpHandler,
    private val tunnelUp: () -> Boolean = { false },
    private val gate: ClientGate? = null,
    private val readOnly: () -> Boolean = { false },
    private val linkApps: LinkControl.Apps? = null,
    private val linkCoach: LinkControl.Coach? = null,
) {
    private val links by lazy {
        LinkControl(
            executor = executor,
            gate = gate,
            readOnly = readOnly,
            root = { pro.potoki.bekon.agent.handoff.Handoff.currentUrl?.trimEnd('/') ?: "" },
            apps = linkApps,
            coach = linkCoach,
        )
    }

    private var engine: CIOApplicationEngine? = null
    @Volatile private var running = false
    val boundPort: Int get() = port
    fun isRunning(): Boolean = running

    private val WRITE_PATHS = setOf("/tap", "/swipe", "/type")
    private val GUIDE_PATHS = setOf("/", "/llms.txt", "/README", "/README.md")

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "127.0.0.1", module = {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            // CORS: the browser console calls /mcp cross-origin (Vite dev -> tunnel URL).
            // Javalin-style before{} handler since enableCorsForAllOrigins() is not on Ktor.
            intercept(io.ktor.server.application.ApplicationCallPipeline.Call) {
                // No CORS: agents call over plain HTTP; browser origins are not served (no Access-Control-* headers).
                if (call.request.httpMethod == io.ktor.http.HttpMethod.Options) {
                    call.respond(HttpStatusCode.MethodNotAllowed)
                    return@intercept
                }
                val path = call.request.path()
                if (stopping && path == "/mcp") {
                    call.respondText(
                        "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32001,\"message\":\"session_stopping: the owner stopped the Bekon session on the phone\"}}",
                        io.ktor.http.ContentType.Application.Json, HttpStatusCode.ServiceUnavailable,
                    )
                    return@intercept
                }
                val g = gate
                if (g != null && path != "/health" && path != "/mcp" && path != "/robots.txt" && path !in GUIDE_PATHS && !path.startsWith("/link/") && !path.startsWith("/s/")) {
                    val headers = call.request.headers
                    val ip = headers["CF-Connecting-IP"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?: headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: call.request.local.remoteHost
                    val token = ClientGate.tokenFrom(headers["Authorization"], headers[ClientGate.TOKEN_HEADER])
                    when (val d = g.check(headers[ClientGate.HEADER], ip, token)) {
                        is ClientGate.Decision.Reject -> {
                            if (d.status == 401) {
                                call.response.headers.append("WWW-Authenticate", "Bearer realm=\"bekon\"")
                                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(d.reason))
                            } else call.respond(HttpStatusCode.Forbidden, ErrorResponse(d.reason))
                            return@intercept
                        }
                        is ClientGate.Decision.Allow -> d.issuedToken?.let { call.response.headers.append(ClientGate.TOKEN_HEADER, it) }
                    }
                }
                if (readOnly() && call.request.httpMethod == io.ktor.http.HttpMethod.Post && path in WRITE_PATHS) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(McpHandler.READ_ONLY))
                    return@intercept
                }
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
                            tunnel = tunnelUp() || (h["tunnel"] ?: false),
                        )
                    )
                }
            get("/snapshot") {
                try {
                    val s = executor.snapshot()
                    call.respond(
                        SnapshotResponse(
                            screenW = s.screenW, screenH = s.screenH,
                            imageW = s.imageW, imageH = s.imageH,
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
                    val q = call.request.queryParameters
                    val scale = q["scale"]?.toFloatOrNull() ?: ScreenshotDefaults.SCALE
                    val quality = q["quality"]?.toIntOrNull() ?: ScreenshotDefaults.QUALITY
                    val format = q["format"] ?: ScreenshotDefaults.FORMAT
                    val f = executor.screenshot(scale, quality, format)
                    val type = if (f.mime.endsWith("png")) ContentType.Image.PNG else ContentType.Image.JPEG
                    call.respondBytes(f.bytes, type)
                } catch (e: CapabilityException) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "conflict"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
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
            for (p in GUIDE_PATHS) get(p) {
                val base = pro.potoki.bekon.agent.handoff.Handoff.currentUrl
                call.respondText(pro.potoki.bekon.agent.handoff.AgentGuide.markdown(base), ContentType.Text.Plain.withCharset(Charsets.UTF_8))
            }
            // Our own robots.txt. On trycloudflare the edge prepends its managed AI-crawler block; ours is appended
            // after it, so explicit Allow groups for user-triggered agents merge with (and per RFC 9309 win over) it.
            get("/robots.txt") { call.respondText(pro.potoki.bekon.agent.handoff.AgentGuide.ROBOTS_TXT, ContentType.Text.Plain.withCharset(Charsets.UTF_8)) }
            for (p in GUIDE_PATHS + listOf("/mcp", "/robots.txt", "/health", "/link/start")) head(p) { call.respond(HttpStatusCode.OK) }
            // Link-control FALLBACK (GET-only chatbots). Token lives in the path; same expiry as MCP.
            get("/link/{rest...}") {
                val q = call.request.queryParameters.names().associateWith { call.request.queryParameters[it] ?: "" }
                val page = links.handle("GET", call.request.path(), q)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "not found")
                call.response.headers.append("Cache-Control", "no-store")
                call.response.headers.append("X-Robots-Tag", "noindex, nofollow")
                call.respondText(page.html ?: "", ContentType.Text.Html.withCharset(Charsets.UTF_8), HttpStatusCode.fromValue(page.status))
            }
            get("/s/{rest...}") {
                val q = call.request.queryParameters.names().associateWith { call.request.queryParameters[it] ?: "" }
                val page = links.handle("GET", call.request.path(), q)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "not found")
                call.response.headers.append("Cache-Control", "no-store")
                call.response.headers.append("X-Robots-Tag", "noindex, nofollow")
                if (page.jpeg != null) {
                    call.respondBytes(page.jpeg, ContentType.Image.JPEG, HttpStatusCode.fromValue(page.status))
                } else {
                    call.respondText(page.html ?: "", ContentType.Text.Html.withCharset(Charsets.UTF_8), HttpStatusCode.fromValue(page.status))
                }
            }
            post("/mcp") { pro.potoki.bekon.agent.access.AgentConnections.busy { mcp.handle(call) } }
            get("/mcp") { mcp.handle(call) }
        }
        })
        running = true
        engine?.start(wait = false)
    }

    /** Set before shutdown so in-flight and new MCP calls get a JSON-RPC error instead of a dropped connection. */
    @Volatile var stopping = false

    fun stop() {
        stopping = true
        running = false
        engine?.stop(800, 2000)
        engine = null
    }
}
