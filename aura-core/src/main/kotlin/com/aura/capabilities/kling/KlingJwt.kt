package com.aura.capabilities.kling

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal HS256 JWT minting for the Kling AI API.
 *
 * Kling authenticates with an API token built from an AccessKey/SecretKey
 * pair (https://app.klingai.com/global/dev/document-api): the request
 * carries `Authorization: Bearer <JWT>` where the JWT is:
 *
 *   header  : {"alg":"HS256","typ":"JWT"}
 *   payload : {"iss":<accessKey>,"exp":now+1800,"nbf":now-5}
 *   sig     : HMAC-SHA256(base64url(header) + "." + base64url(payload), secretKey)
 *
 * All segments are base64url without padding. Uses javax.crypto.Mac —
 * no JWT library dependency.
 */
internal object KlingJwt {

    /** Token validity window in seconds (Kling documents 30 minutes). */
    private const val EXPIRY_SECONDS = 1800L

    /** Not-before skew in seconds, tolerating small clock drift. */
    private const val NOT_BEFORE_SKEW_SECONDS = 5L

    /**
     * Mint a signed JWT for [accessKey]/[secretKey]. [nowSeconds] is
     * injectable for deterministic tests; production callers use the
     * default wall clock.
     */
    fun mint(
        accessKey: String,
        secretKey: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): String {
        val header = """{"alg":"HS256","typ":"JWT"}"""
        val payload = """{"iss":"$accessKey","exp":${nowSeconds + EXPIRY_SECONDS},"nbf":${nowSeconds - NOT_BEFORE_SKEW_SECONDS}}"""
        val signingInput = "${base64Url(header.toByteArray(Charsets.UTF_8))}.${base64Url(payload.toByteArray(Charsets.UTF_8))}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(signingInput.toByteArray(Charsets.UTF_8))
        return "$signingInput.${base64Url(signature)}"
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
