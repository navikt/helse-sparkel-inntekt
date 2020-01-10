package no.nav.helse.inntekt

import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.YearMonth

internal class InntektRestClientTest {

    @Test
    fun `tom liste json response skal gi tom liste med inntekter`() = runBlocking {
        assertEquals(
            emptyList<Inntekt>(),
            inntektRestClient.hentInntektsliste("AktørId", YearMonth.now(), YearMonth.now(), "filter", "callid")
        )
    }

    @Test
    fun `liste med en inntekt skal gi tilsvarende liste med et inntektobjekt`() = runBlocking {
        mockResponseGenerator.apply {
            every { hentInntekter() } returns """[{"beloep": 100.00, "inntektType": "LOENNSINNTEKT", "ubruktFelt": "Kode", "arbeidsforholdREF": "orgnummer"}]"""
        }
        assertEquals(
            listOf(Inntekt(beløp = 100.0, inntektstype = Inntektstype.LOENNSINNTEKT, orgnummer = "orgnummer")),
            inntektRestClient.hentInntektsliste("AktørId", YearMonth.now(), YearMonth.now(), "filter", "callid")
        )
    }

    private val mockResponseGenerator = defaultMockResponseGenerator()
    private val inntektRestClient = InntektRestClient(
        baseUrl = "https://faktiskUrl",
        httpClient = mockHttpClient(mockResponseGenerator),
        stsRestClient = mockStsRestClient
    )
}
