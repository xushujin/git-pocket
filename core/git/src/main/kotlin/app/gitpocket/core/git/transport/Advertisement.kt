package app.gitpocket.core.git.transport

import app.gitpocket.core.model.ObjectId
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/** 远端引用广告（protocol v0）。 */
data class RemoteAdvertisement(
    val refs: Map<String, ObjectId>,
    val capabilities: Set<String>,
    val headTarget: String?,
) {
    val headOid: ObjectId? get() = refs["HEAD"]

    fun branches(): Map<String, ObjectId> = refs.filterKeys { it.startsWith("refs/heads/") }

    fun tags(): Map<String, ObjectId> = refs.filterKeys { it.startsWith("refs/tags/") }
}

internal object AdvertisementParser {

    fun parse(bytes: ByteArray): RemoteAdvertisement {
        val reader = PktLineReader(ByteArrayInputStream(bytes))
        val refs = LinkedHashMap<String, ObjectId>()
        var capabilities: Set<String> = emptySet()
        var headTarget: String? = null
        var firstRefSeen = false

        while (!reader.eof) {
            val packet = reader.next() ?: continue
            if (packet.isEmpty()) continue
            val text = String(packet, StandardCharsets.UTF_8).trimEnd('\n')

            // v0 广告首行是 "# service=git-upload-pack"
            if (text.startsWith("# service=")) continue

            val nul = text.indexOf('\u0000')
            val refPart = if (nul >= 0) text.substring(0, nul) else text
            if (!firstRefSeen && nul >= 0) {
                capabilities = text.substring(nul + 1)
                    .split(' ')
                    .filter { it.isNotBlank() }
                    .toSet()
                capabilities.firstOrNull { it.startsWith("symref=HEAD:") }
                    ?.let { headTarget = it.removePrefix("symref=HEAD:") }
                firstRefSeen = true
            }

            val space = refPart.indexOf(' ')
            if (space != 40) continue
            val oid = runCatching { ObjectId(refPart.substring(0, 40)) }.getOrNull() ?: continue
            val name = refPart.substring(41).trim()
            if (name.isNotEmpty()) refs[name] = oid
        }

        return RemoteAdvertisement(refs = refs, capabilities = capabilities, headTarget = headTarget)
    }
}
