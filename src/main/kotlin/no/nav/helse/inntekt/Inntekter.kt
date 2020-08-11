package no.nav.helse.inntekt

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.features.ResponseException
import io.ktor.client.statement.readText
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
                validate { it.require("beregningStart", JsonNode::asYearMonth) }
                validate { it.require("beregningSlutt", JsonNode::asYearMonth) }
                validate { it.forbid("@løsning") }
            }.register(this)
        }

        override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
            this@Inntekter.onPacket(packet, context, InntekterForSykepengegrunnlag)
        }
    }

    inner class Sammenligningsgrunnlag(rapidsConnection: RapidsConnection) :
        River.PacketListener {

        init {
            River(rapidsConnection).apply {
                validate { it.requireContains("@behov", InntekterForSammenligningsgrunnlag.name) }
                validate { it.requireKey("@id", "fødselsnummer", "vedtaksperiodeId") }
                validate { it.require("beregningStart", JsonNode::asYearMonth) }
                validate { it.require("beregningSlutt", JsonNode::asYearMonth) }
                validate { it.forbid("@løsning") }
            }.register(this)
        }

        override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
            this@Inntekter.onPacket(packet, context, InntekterForSammenligningsgrunnlag)
        }
    }

    private fun onPacket(
        packet: JsonMessage,
        context: RapidsConnection.MessageContext,
        type: Type
    ) {
        withMDC(
            mapOf(
                "behovId" to packet["@id"].asText(),
                "vedtaksperiodeId" to packet["vedtaksperiodeId"].asText()
            )
        ) {
            val beregningStart = packet["beregningStart"].asYearMonth()
            val beregningSlutt = packet["beregningSlutt"].asYearMonth()

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
