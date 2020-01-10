package no.nav.helse.inntekt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.YearMonth
import java.time.temporal.ChronoUnit

class LøsningService(val inntektsRestClient: InntektRestClient) {
    suspend fun løsBehov(behov: JsonNode): JsonNode =
        behov.deepCopy<ObjectNode>().set("@løsning", objectMapper.valueToTree(hentLøsning(behov)))

    private suspend fun hentLøsning(behov: JsonNode): Løsning {
        log.info("hentet inntekter for behov: ${behov["@id"].asText()}")
        val vedtaksid = behov["vedtaksperiodeId"].asText()
        val beregningStart = YearMonth.parse(behov["beregningStart"].asText())
        val beregningSlutt = YearMonth.parse(behov["beregningSlutt"].asText())
        val filter = filterForPeriode(beregningStart, beregningSlutt)
        return Løsning(
            inntektsRestClient.hentInntektsliste(
                behov["aktørId"].asText(),
                beregningStart,
                beregningSlutt,
                filter,
                vedtaksid
            )
        )
    }

    private fun filterForPeriode(beregningStart: YearMonth, beregningSlutt: YearMonth): String {
        return when {
            månederMellom(beregningStart, beregningSlutt) == 12L -> "8-30"
            månederMellom(beregningStart, beregningSlutt) == 3L -> "8-28"
            else -> error("Ukjent beregning for periode på ${månederMellom(beregningStart, beregningSlutt)} måneder")
        }
    }

    private fun månederMellom(fom: YearMonth, tom: YearMonth) = ChronoUnit.MONTHS.between(fom, tom)
}

data class Løsning(
    @JvmField val Inntekter: List<Inntekt>
)
