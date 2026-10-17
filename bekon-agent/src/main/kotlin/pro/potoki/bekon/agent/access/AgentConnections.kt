package pro.potoki.bekon.agent.access

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Agents that passed the gate in this process (approved now or already trusted). Drives the home screen status. */
object AgentConnections {
    private val _connected = MutableStateFlow<List<String>>(emptyList())
    val connected: StateFlow<List<String>> = _connected

    fun mark(clientId: String) {
        val cur = _connected.value
        if (clientId !in cur) _connected.value = cur + clientId
    }

    fun clear() { _connected.value = emptyList() }
}
