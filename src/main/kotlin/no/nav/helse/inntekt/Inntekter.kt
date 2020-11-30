package no.nav.helse.inntekt

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.features.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking
import net.logstash.logback.argument.StructuredArguments.keyValue
import no.nav.helse.inntekt.Inntekter.Type.InntekterForSammenligningsgrunnlag
import no.nav.helse.inntekt.Inntekter.Type.InntekterForSykepengegrunnlag
import no.nav.helse.rapids_rivers.JsonMessage
import no.nav.helse.rapids_rivers.RapidsConnection
import no.nav.helse.rapids_rivers.River
import no.nav.helse.rapids_rivers.asYearMonth
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.YearMonth

class Inntekter(
    rapidsConnection: RapidsConnection,
    private val inntektsRestClient: InntektRestClient
) {

    private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")
    private val log = LoggerFactory.getLogger(this::class.java)

    init {
        Sykepengegrunnlag(rapidsConnection)
        Sammenligningsgrunnlag(rapidsConnection)
    }

    enum class Type(val ainntektfilter: String) {
        InntekterForSykepengegrunnlag("8-28"),
        InntekterForSammenligningsgrunnlag("8-30")
    }

    inner class Sykepengegrunnlag(rapidsConnection: RapidsConnection) :
        River.PacketListener {

        init {
            River(rapidsConnection).apply {
                validate { it.requireContains("@behov", InntekterForSykepengegrunnlag.name) }
                validate { it.requireKey("@id", "fødselsnummer", "vedtaksperiodeId") }
                validate { it.require("${InntekterForSykepengegrunnlag.name}.beregningsperiodeStart", JsonNode::asYearMonth) }
                validate { it.require("${InntekterForSykepengegrunnlag.name}.beregningsperiodeSlutt", JsonNode::asYearMonth) }
                validate { it.forbid("@løsning") }
            }.register(this)
        }

        override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
            this@Inntekter.onSykepengegrunnlagPacket(packet, context)
        }
    }

    inner class Sammenligningsgrunnlag(rapidsConnection: RapidsConnection) :
        River.PacketListener {

        init {
            River(rapidsConnection).apply {
                validate { it.requireContains("@behov", InntekterForSammenligningsgrunnlag.name) }
                validate { it.requireKey("@id", "fødselsnummer", "vedtaksperiodeId") }
                validate { it.require("${InntekterForSammenligningsgrunnlag.name}.beregningStart", JsonNode::asYearMonth) }
                validate { it.require("${InntekterForSammenligningsgrunnlag.name}.beregningSlutt", JsonNode::asYearMonth) }
                validate { it.forbid("@løsning") }
            }.register(this)
        }

        override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
            this@Inntekter.onSammenligningsgrunnlagPacket(packet, context)
        }
    }

    private fun onSammenligningsgrunnlagPacket(
        packet: JsonMessage,
        context: RapidsConnection.MessageContext
    ) {
        withMDC(
            mapOf(
                "behovId" to packet["@id"].asText(),
                "vedtaksperiodeId" to packet["vedtaksperiodeId"].asText()
            )
        ) {
            val beregningStart = packet["${InntekterForSammenligningsgrunnlag.name}.beregningStart"].asYearMonth()
            val beregningSlutt = packet["${InntekterForSammenligningsgrunnlag.name}.beregningSlutt"].asYearMonth()

            hentInntekter(packet, InntekterForSammenligningsgrunnlag, beregningStart, beregningSlutt, context)
        }
    }

    private fun onSykepengegrunnlagPacket(
        packet: JsonMessage,
        context: RapidsConnection.MessageContext
    ) {
        withMDC(
            mapOf(
                "behovId" to packet["@id"].asText(),
                "vedtaksperiodeId" to packet["vedtaksperiodeId"].asText()
            )
        ) {
            val beregningStart = packet["${InntekterForSykepengegrunnlag.name}.beregningsperiodeStart"].asYearMonth()
            val beregningSlutt = packet["${InntekterForSykepengegrunnlag.name}.beregningsperiodeSlutt"].asYearMonth()

            hentInntekter(packet, InntekterForSykepengegrunnlag, beregningStart, beregningSlutt, context)
        }
    }

    private fun hentInntekter(
        packet: JsonMessage,
        type: Type,
        beregningStart: YearMonth,
        beregningSlutt: YearMonth,
        context: RapidsConnection.MessageContext
    ) {
        try {
            packet["@løsning"] = mapOf<String, Any>(
                type.name to inntektsRestClient.hentInntektsliste(
                    fnr = packet["fødselsnummer"].asText(),
                    fom = beregningStart,
                    tom = beregningSlutt,
                    filter = type.ainntektfilter,
                    callId = "${packet["vedtaksperiodeId"].asText()}-${packet["@id"].asText()}"
                )
            )
            context.send(packet.toJson().also {
                log.info("løser behov: {}", keyValue("id", packet["@id"].asText()))
                sikkerlogg.info("svarer behov {} med {}", keyValue("id", packet["@id"].asText()), it)
            })
        } catch (e: ResponseException) {
            log.warn("Feilet ved løsing av behov: ${e.message}", e)
            runBlocking {
                sikkerlogg.warn(
                    "Feilet ved løsing av behov: ${e.message}\n\t${e.response.readText()}",
                    e
                )
            }
        } catch (e: Exception) {
            log.warn("Feilet ved løsing av behov: ${e.message}", e)
            sikkerlogg.warn("Feilet ved løsing av behov: ${e.message}", e)
        }
    }

    private fun withMDC(context: Map<String, String>, block: () -> Unit) {
        val contextMap = MDC.getCopyOfContextMap() ?: emptyMap()
        try {
            MDC.setContextMap(contextMap + context)
            block()
        } finally {
            MDC.setContextMap(contextMap)
        }
    }
}
