package com.itantra.packs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The list of downloadable packs: `index.json` from the public pack release (written by
 * scripts/packs/make_index.py). A copy ships in the APK (assets/catalog/index.json) so every language is listed
 * even offline; the app can refresh it from [baseUrl] when there is internet.
 */
@Serializable
data class PackCatalog(
    val format: Int,
    val packs: Map<String, CatalogEntry>,
) {
    /** Packs sorted by language code, then speak before listen. */
    val sorted: List<Pair<String, CatalogEntry>>
        get() = packs.entries.map { it.key to it.value }.sortedWith(compareBy({ it.second.packetCode }, { it.second.kind != "speak" }))

    fun url(id: String): String = baseUrl + packs.getValue(id).zip

    companion object {
        const val baseUrl = "https://github.com/hashbrown3301/signal-zero/releases/download/packs-v1/"
        const val INDEX_URL = baseUrl + "index.json"

        fun parse(text: String): PackCatalog = PackManifest.json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class CatalogEntry(
    val lang: String,
    @SerialName("packet_code") val packetCode: Int,
    val name: String,
    val native: String,
    val kind: String,
    val engine: String,
    /** Unpacked size on the phone. */
    val size: Long,
    val zip: String,
    /** Download size. */
    @SerialName("zip_size") val zipSize: Long,
    @SerialName("zip_sha256") val zipSha256: String,
    val licences: List<String> = emptyList(),
    val built: String = "",
)
