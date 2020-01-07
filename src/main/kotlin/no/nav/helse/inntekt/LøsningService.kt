package no.nav.helse.inntekt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

class LøsningService(val inntektsRestClient: InntektRestClient) {
    suspend fun løsBehov(behov: JsonNode): JsonNode =
        behov.deepCopy<ObjectNode>().set("@løsning", objectMapper.valueToTree(hentLøsning(behov)))

    private suspend fun hentLøsning(behov: JsonNode): Løsning {
        log.info("hentet inntekter for behov: ${behov["@id"].asText()}")
        return Løsning(inntektsRestClient.hentInntektsliste(behov["aktørId"].asText()).map { it.toInntekt() })
    }
}

data class Løsning(
    @JvmField val Inntekter: List<Inntekt>
)
