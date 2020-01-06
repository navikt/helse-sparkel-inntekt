package no.nav.helse.sputnik

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

class LøsningService(private val fpsakRestClient: FpsakRestClient) {
    suspend fun løsBehov(behov: JsonNode): JsonNode = behov.deepCopy<ObjectNode>()
        .set("@løsning", objectMapper.valueToTree(Løsning(hentYtelser(behov))))

    private suspend fun hentYtelser(behov: JsonNode): Foreldrepenger {
        val aktørId = behov["aktørId"].asText()
        val behovId = behov["@id"].asText()

        val foreldrepengeytelse = fpsakRestClient.hentGjeldendeForeldrepengeytelse(aktørId)
        log.info("hentet gjeldende foreldrepengeytelse for behov: $behovId")
        val svangerskapsytelse = fpsakRestClient.hentGjeldendeSvangerskapsytelse(aktørId)
        log.info("hentet gjeldende svangerskapspengeytelse for behov: $behovId")

        return Foreldrepenger(foreldrepengeytelse, svangerskapsytelse)
    }
}

data class Løsning(
    @JvmField val Foreldrepenger: Foreldrepenger
)

data class Foreldrepenger(
    @JvmField val Foreldrepengeytelse: Ytelse? = null,
    @JvmField val Svangerskapsytelse: Ytelse? = null
)

