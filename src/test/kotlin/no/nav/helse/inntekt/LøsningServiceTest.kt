package no.nav.helse.inntekt

import com.fasterxml.jackson.databind.JsonNode
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.YearMonth

internal class LøsningServiceTest {

    private val inntektRestClient = spyk(
        InntektRestClient(
            baseUrl = "https://base.url",
            httpClient = mockHttpClient(defaultMockResponseGenerator()),
            stsRestClient = mockStsRestClient
        )
    )
    private val løsningService = LøsningService(inntektRestClient)

    @Test
    fun `behov med beregningsperiode på 3 måneder skal bruke 8-28 filter ved henting av inntektsdata`() = runBlocking {
        val start = YearMonth.of(2020, 1)
        val slutt = YearMonth.of(2020, 3)

        løsningService.løsBehov(behov(start, slutt))

        verify {
            runBlocking {
                inntektRestClient.hentInntektsliste(
                    aktørId = "123",
                    fom = start,
                    tom = slutt,
                    filter = "8-28",
                    callId = "vedtaksperiodeId"
                )
            }
        }
    }

    @Test
    fun `behov med beregningsperiode på 12 måneder skal bruke 8-30 filter ved henting av inntektsdata`() = runBlocking {
        val start = YearMonth.of(2020, 2)
        val slutt = YearMonth.of(2021, 1)

        løsningService.løsBehov(behov(start, slutt))

        verify {
            runBlocking {
                inntektRestClient.hentInntektsliste(
                    aktørId = "123",
                    fom = start,
                    tom = slutt,
                    filter = "8-30",
                    callId = "vedtaksperiodeId"
                )
            }
        }
    }

    @Test
    fun `behov med beregningsperiode på noe annet enn 3 eller 12 måneder skal føre til null`() {
        val start = YearMonth.of(2020, 1)
        val slutt = YearMonth.of(2020, 2)

        runBlocking {
            assertNull(løsningService.løsBehov(behov(start, slutt)))
        }
    }

    private fun behov(start: YearMonth, slutt: YearMonth) = objectMapper.valueToTree<JsonNode>(
        mapOf(
            "@id" to "behovsid",
            "@behov" to listOf(Inntektsberegning, "Sykepengehistorikk"),
            "aktørId" to "123",
            "vedtaksperiodeId" to "vedtaksperiodeId",
            "beregningStart" to "$start",
            "beregningSlutt" to "$slutt"
        )
    )
}
