package no.nav.helse.inntekt

import com.fasterxml.jackson.databind.JsonNode
import net.logstash.logback.argument.StructuredArguments.keyValue
import no.nav.helse.rapids_rivers.JsonMessage
import no.nav.helse.rapids_rivers.RapidsConnection
import no.nav.helse.rapids_rivers.River
import no.nav.helse.rapids_rivers.asYearMonth
import org.slf4j.LoggerFactory
import java.time.YearMonth
import java.time.temporal.ChronoUnit

class Inntektsberegning(rapidsConnection: RapidsConnection, private val inntektsRestClient: InntektRestClient) :
    River.PacketListener {
    private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")
    private val log = LoggerFactory.getLogger(this::class.java)

    init {
        River(rapidsConnection).apply {
            validate { it.requireContains("@behov", Inntektsberegningbehov) }
            validate { it.requireKey("@id", "fødselsnummer", "vedtaksperiodeId") }
            validate { it.require ("beregningStart", JsonNode::asYearMonth) }
            validate { it.require ("beregningSlutt", JsonNode::asYearMonth) }
            validate { it.forbid("@løsning") }
        }.register(this)
    }

    override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
        sikkerlogg.info("løser behov: {}", keyValue("id", packet["@id"].asText()))
        log.info("løser behov: {}", keyValue("id", packet["@id"].asText()))

        val beregningStart = packet["beregningStart"].asYearMonth()
        val beregningSlutt = packet["beregningSlutt"].asYearMonth()

        packet["@løsning"] = mapOf<String, Any>(
            Inntektsberegningbehov to hentLøsning(
                id = packet["@id"].asText(),
                vedtaksperiodeId = packet["vedtaksperiodeId"].asText(),
                fødselsnummer = packet["fødselsnummer"].asText(),
                beregningStart = beregningStart,
                beregningSlutt = beregningSlutt
            )
        )
        context.send(packet.toJson().also {
            sikkerlogg.info("svarer behov {} med {}", keyValue("id", packet["@id"].asText()), it)
        })
    }

    private fun hentLøsning(
        id: String,
        vedtaksperiodeId: String,
        fødselsnummer: String,
        beregningStart: YearMonth,
        beregningSlutt: YearMonth
    ): List<Måned> {
        log.info("hentet inntekter for behov: $id")
        return try {
            val filter = filterForPeriode(beregningStart, beregningSlutt)
            inntektsRestClient.hentInntektsliste(
                fødselsnummer,
                beregningStart,
                beregningSlutt,
                filter,
                vedtaksperiodeId
            )
        } catch (e: Exception) {
            log.error("Feilet ved løsing av behov id=$id for vedtaksperiodeId=$vedtaksperiodeId: ${e.message}", e)
            emptyList()
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