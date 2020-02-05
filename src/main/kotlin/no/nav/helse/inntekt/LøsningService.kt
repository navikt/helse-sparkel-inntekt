package no.nav.helse.inntekt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import net.logstash.logback.argument.StructuredArguments.keyValue
import java.time.YearMonth
import java.time.temporal.ChronoUnit

class LøsningService(private val inntektsRestClient: InntektRestClient) {
    suspend fun løsBehov(behov: JsonNode): JsonNode? = hentLøsning(behov)?.let { løsning ->
        behov.deepCopy<ObjectNode>().set("@løsning", objectMapper.valueToTree(løsning))
    }

    private suspend fun hentLøsning(behov: JsonNode): Løsning? {
        log.info("hentet inntekter for behov: ${behov["@id"].asText()}")
        val vedtaksid = behov["vedtaksperiodeId"].asText()
        val beregningStart = YearMonth.parse(behov["beregningStart"].asText())
        val beregningSlutt = YearMonth.parse(behov["beregningSlutt"].asText())
        return try {
            val filter = filterForPeriode(beregningStart, beregningSlutt)
            Løsning(
                inntektsRestClient.hentInntektsliste(
                    behov["aktørId"].asText(),
                    beregningStart,
                    beregningSlutt,
                    filter,
                    vedtaksid
                )
            )
        } catch (e: Exception) {
            log.error("Feilet ved løsing av behov for {} {}",
                keyValue("vedtaksperiodeId", vedtaksid),
                keyValue("behovId", behov["@id"]), e)
            null
        }
    }

    private fun filterForPeriode(beregningStart: YearMonth, beregningSlutt: YearMonth): String {
        return when {
            månederMellom(beregningStart, beregningSlutt) == 11L -> "8-30"
            månederMellom(beregningStart, beregningSlutt) == 2L -> "8-28"
            else -> error("Ukjent beregning for periode på ${månederMellom(beregningStart, beregningSlutt)} måneder")
        }
    }

    private fun månederMellom(fom: YearMonth, tom: YearMonth) = ChronoUnit.MONTHS.between(fom, tom)
}

data class Løsning(
    @JvmField val Inntektsberegning: List<Måned>
)
