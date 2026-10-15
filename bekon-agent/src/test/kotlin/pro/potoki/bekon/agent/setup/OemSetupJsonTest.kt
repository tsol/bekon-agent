package pro.potoki.bekon.agent.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OemSetupJsonTest {
    @Test fun assetParsesAndStepsComplete() {
        val f = listOf("src/main/assets/oem_setup.json", "apps/bekon-agent/src/main/assets/oem_setup.json").map(::File).first { it.exists() }
        val root = Json.parseToJsonElement(f.readText()).jsonObject
        assertTrue((root["attribution"] as JsonPrimitive).content.contains("CC BY 4.0"))
        val steps = root["common"]!!.jsonArray + root["vendors"]!!.jsonArray.flatMap { it.jsonObject["steps"]!!.jsonArray }
        val ids = HashSet<String>()
        steps.map { it.jsonObject }.forEach { s ->
            assertTrue(ids.add((s["id"] as JsonPrimitive).content))
            for (k in listOf("title", "path")) for (l in listOf("en", "ru"))
                assertTrue("${s["id"]} $k.$l", (s[k]!!.jsonObject[l] as JsonPrimitive).content.isNotBlank())
        }
        assertTrue(ids.size >= 15)
    }
}
