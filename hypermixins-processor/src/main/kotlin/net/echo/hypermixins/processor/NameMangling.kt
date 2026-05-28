package net.echo.hypermixins.processor

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Mangled-name algorithm must stay byte-for-byte identical with
 * `MixinTransformer.mangledName` / `dispatchName` in the runtime module.
 * KSP precomputes these so the runtime never has to re-hash.
 */
internal object NameMangling {

    fun mangledOriginalName(methodName: String, descriptor: String): String =
        "__original$" + methodName + "$" + sha1Hex16(descriptor)

    fun dispatchName(methodName: String, descriptor: String): String =
        "__dispatch$" + methodName + "$" + sha1Hex16(descriptor)

    private fun sha1Hex16(input: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash = sha1.digest(input.toByteArray(StandardCharsets.UTF_8))
        return buildString(16) {
            for (i in 0 until 8) append("%02x".format(hash[i]))
        }
    }
}
