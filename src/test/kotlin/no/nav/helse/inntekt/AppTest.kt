package no.nav.helse.inntekt

import com.fasterxml.jackson.databind.JsonNode
import no.nav.helse.rapids_rivers.RapidsConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.YearMonth

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class AppTest {
    private val sentMessages = mutableListOf<JsonNode>()
    private val rapid = object : RapidsConnection() {
        fun sendTestMessage(message: String) {
            listeners.forEach { it.onMessage(message, context) }
        }

        override fun publish(message: String) {}
        override fun publish(key: String, message: String) {}
        override fun start() {}
        override fun stop() {}
    }
    private val context = object : RapidsConnection.MessageContext {
        override fun send(message: String) {
            sentMessages.add(objectMapper.readTree(message))
        }
        override fun send(key: String, message: String) {}
    }

    private val mockResponseGenerator = defaultMockResponseGenerator()
    private val inntektsRestClient =
        InntektRestClient("http://baseUrl.local", mockHttpClient(mockResponseGenerator), mockStsRestClient)

    private val løsningService = LøsningService(rapid, inntektsRestClient)

    @BeforeEach
    fun reset() {
        sentMessages.clear()
    }

    @Test
    fun `skal motta behov og produsere løsning`() {
        val start = YearMonth.of(2020, 2)
        val slutt = YearMonth.of(2021, 1)
        rapid.sendTestMessage(behov(start, slutt))
        assertEquals(1, sentMessages.size)
        val svar = sentMessages.first()
        assertEquals("123", svar["fødselsnummer"].asText())
        assertTrue(svar["@løsning"].hasNonNull(Inntektsberegning))
        assertEquals(2, svar["@løsning"][Inntektsberegning].size())
    }

    @Test
    fun `skal kun behandle opprinnelig behov`() {
        val start = YearMonth.of(2020, 2)
        val slutt = YearMonth.of(2021, 1)
        val behovAlleredeBesvart = behovMedLøsning(start, slutt, "1")
        val behovSomTrengerSvar = behov(start, slutt, "2")
        rapid.sendTestMessage(behovAlleredeBesvart)
        rapid.sendTestMessage(behovSomTrengerSvar)
        assertEquals(1, sentMessages.size)
        val svar = sentMessages.first()
        assertEquals("123", svar["fødselsnummer"].asText())
        assertTrue(svar["@løsning"].hasNonNull(Inntektsberegning))
        assertEquals("2", svar["@id"].asText())
    }

    @Test
    fun `ignorerer hendelser med ugyldig json`() {
        val start = YearMonth.of(2020, 2)
        val slutt = YearMonth.of(2021, 1)
        val behovAlleredeBesvart = behovMedLøsning(start, slutt, "1")
        val behovSomTrengerSvar = behov(start, slutt, "2")
        rapid.sendTestMessage("THIS IS NOT JSON")
        rapid.sendTestMessage(behovAlleredeBesvart)
        rapid.sendTestMessage(behovSomTrengerSvar)
        assertEquals(1, sentMessages.size)
        val svar = sentMessages.first()
        assertEquals("123", svar["fødselsnummer"].asText())
        assertTrue(svar["@løsning"].hasNonNull(Inntektsberegning))
        assertEquals("2", svar["@id"].asText())
    }

    private fun behov(start: YearMonth, slutt: YearMonth, id: String = "behovsid") = objectMapper.writeValueAsString(behovMap(start, slutt, id))

    private fun behovMedLøsning(start: YearMonth, slutt: YearMonth, id: String) =
        objectMapper.writeValueAsString(behovMap(start, slutt, id) + mapOf<String, Any>(
            "@løsning" to mapOf<String, Any>(
                Inntektsberegning to emptyList<Any>()
            )
        ))

    private fun behovMap(start: YearMonth, slutt: YearMonth, id: String) = mapOf(
        "@id" to id,
        "@behov" to listOf(Inntektsberegning, "EgenAnsatt"),
        "fødselsnummer" to "123",
        "vedtaksperiodeId" to "vedtaksperiodeId",
        "beregningStart" to "$start",
        "beregningSlutt" to "$slutt"
    )
}
