package no.nav.helse.sputnik

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

internal class FpsakRestClientTest {

    @Test
    fun `skal hente foreldrepengerytelse`() = runBlocking {
        val ytelse = fpsakRestClient.hentGjeldendeForeldrepengeytelse("aktør")
        val forventetYtelse = Ytelse(
            aktørId = "aktør",
            fom = LocalDate.of(2019, 10, 1),
            tom = LocalDate.of(2020, 2, 7),
            vedtatt = LocalDateTime.of(2019, 10, 18, 0, 0, 0),
            perioder = listOf(
                Periode(
                    fom = LocalDate.of(2019, 10, 1),
                    tom = LocalDate.of(2020, 2, 7)
                )
            )
        )

        assertEquals(forventetYtelse, ytelse)
    }

    @Test
    fun `skal returnere null hvis bruker ikke har foreldrepenger`() = runBlocking {
        mockResponseGenerator.apply {
            every { foreldrepenger() }.returns("[]")
        }
        val ytelse = fpsakRestClient.hentGjeldendeForeldrepengeytelse("aktør")

        assertNull(ytelse, "skal returnere null hvis bruker ikke har foreldrepenger")
    }

    @Test
    fun `skal hente svangerskapspenger ytelse`() = runBlocking {
        val ytelse = fpsakRestClient.hentGjeldendeSvangerskapsytelse("aktør")
        val forventetYtelse = Ytelse(
            aktørId = "aktør",
            fom = LocalDate.of(2019, 10, 1),
            tom = LocalDate.of(2020, 2, 7),
            vedtatt = LocalDateTime.of(2019, 10, 18, 0, 0, 0),
            perioder = listOf(
                Periode(
                    fom = LocalDate.of(2019, 10, 1),
                    tom = LocalDate.of(2020, 2, 7)
                )
            )
        )

        assertEquals(forventetYtelse, ytelse)
    }

    @Test
    fun `skal returnere null hvis bruker ikke har svangerskapspenger`() = runBlocking {
        mockResponseGenerator.apply {
            every { svangerskapspenger() }.returns("[]")
        }
        val ytelse = fpsakRestClient.hentGjeldendeSvangerskapsytelse("aktør")

        assertNull(ytelse, "skal returnere null hvis bruker ikke har svangerskapspenger")
    }

    private val baseUrl = "https://faktiskUrl"
    private val mockResponseGenerator = mockk<ResponseGenerator>(relaxed = true).apply {
        every { foreldrepenger() }.returns(ytelseResponse())
        every { svangerskapspenger() }.returns(ytelseResponse())
    }

    private val fpsakRestClient = FpsakRestClient(baseUrl, fpsakMockClient(mockResponseGenerator), mockStsRestClient)
}
