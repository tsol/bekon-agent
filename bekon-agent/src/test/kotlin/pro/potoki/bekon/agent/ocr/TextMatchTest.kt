package pro.potoki.bekon.agent.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OCR strings below are real Latin-model outputs captured on the Redmi 9 stand (RU Settings). */
class TextMatchTest {
    private val ocr = listOf(
        "laponMu 6esonaCHOCTb", "TeMbl", "HactpoňKM", "LļuopoBoe 6naronony4ne",
        "M poguTeNbCKM KOHTPONb", "Oco6ble BO3MOKHOCTM", "npou3B04MTeNbHOCTb",
        "PacumpeHHbI", "lpunoKeHMA", "3auųwTa", "060M",
    )

    private fun hits(q: String) = ocr.filter { TextMatch.matches(it, q) }

    @Test fun cyrillicQueriesMatchLatinLookalikes() {
        assertTrue(hits("Пароли и безопасность").toString(), hits("Пароли и безопасность") == listOf("laponMu 6esonaCHOCTb"))
        assertTrue(hits("Темы").toString(), "TeMbl" in hits("Темы"))
        assertTrue(hits("Настройки").toString(), hits("Настройки") == listOf("HactpoňKM"))
        assertTrue(hits("Цифровое благополучие").toString(), hits("Цифровое благополучие") == listOf("LļuopoBoe 6naronony4ne"))
        assertTrue(hits("Родительский контроль").toString(), hits("Родительский контроль") == listOf("M poguTeNbCKM KOHTPONb"))
        assertTrue(hits("Особые возможности").toString(), hits("Особые возможности") == listOf("Oco6ble BO3MOKHOCTM"))
        assertTrue(hits("Расширенные").toString(), "PacumpeHHbI" in hits("Расширенные"))
    }

    @Test fun cyrillicQueryDoesNotMatchUnrelatedRows() {
        assertTrue(hits("Уведомления").toString(), hits("Уведомления").isEmpty())
        assertTrue(hits("Батарея").toString(), hits("Батарея").isEmpty())
        assertFalse(TextMatch.matches("Wi-Fi", "Пароли и безопасность"))
    }

    @Test fun exactCyrillicAndLatinStillWork() {
        assertTrue(TextMatch.matches("Пароли и безопасность", "пароли"))
        assertTrue(TextMatch.matches("Bluetooth", "bluetooth"))
        assertFalse(TextMatch.matches("Bluetooth", "Bluetoth")) // ASCII queries stay exact
        assertFalse(TextMatch.matches("Wi-Fi settings", "Wi-Fi", contains = false))
        assertTrue(TextMatch.matches(" Wi-Fi ", "wi-fi", contains = false))
    }
}
