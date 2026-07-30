package mcorch.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

/**
 * Configuration is total: it produces a usable server, an explicit "off", or a
 * message naming the variable at fault. It never produces an unauthenticated
 * server because something was unset.
 */
class ApiConfigTest {
    private val token = "b".repeat(44)

    private fun parse(vararg entries: Pair<String, String>): ApiConfiguration =
        ApiConfig.fromEnvironment(mapOf(*entries), TestApi.CLOCK)

    @Test
    fun `there is no default token, and the failure says how to make one`() {
        val failure = runCatching { parse() }.exceptionOrNull()
        (failure is IllegalArgumentException) shouldBe true
        val message = failure?.message.orEmpty()
        message shouldContain ApiConfig.TOKEN_VARIABLE
        message shouldContain "no default"
        // The way out is named, so nobody reaches for a blank token to get moving.
        message shouldContain "${ApiConfig.LISTEN_VARIABLE}=${ApiConfig.DISABLED}"
    }

    @Test
    fun `a short token is refused, and the refusal does not repeat it`() {
        val short = "hunter2"
        val failure = runCatching { parse(ApiConfig.TOKEN_VARIABLE to short) }.exceptionOrNull()
        val message = failure?.message.orEmpty()
        message shouldContain ApiConfig.TOKEN_VARIABLE
        message shouldContain "at least 32 characters"
        // The message describes the shape, never the value: a configuration error
        // is exactly the sort of thing that gets pasted into a chat window.
        message shouldNotContain short
    }

    @Test
    fun `the API can be turned off, but only in so many words`() {
        parse(ApiConfig.LISTEN_VARIABLE to "off") shouldBe ApiConfiguration.Disabled
        parse(ApiConfig.LISTEN_VARIABLE to "OFF") shouldBe ApiConfiguration.Disabled
        // And turning it off does not then require a token.
        runCatching { parse(ApiConfig.LISTEN_VARIABLE to "off") }.isSuccess shouldBe true
    }

    @Test
    fun `it binds loopback by default and does not demand a secure cookie there`() {
        val config = (parse(ApiConfig.TOKEN_VARIABLE to token) as ApiConfiguration.Listening).config
        config.bindHost shouldBe "127.0.0.1"
        config.bindPort shouldBe 8080
        // A Secure cookie on a plain-HTTP loopback bind is one a browser stores
        // nowhere, which reads to an operator as "login does not work".
        config.cookieSecure shouldBe false
        config.cookieSameSite shouldBe SameSite.STRICT
        config.allowedOrigins shouldBe emptySet()
    }

    @Test
    fun `binding anywhere else defaults the cookie to Secure`() {
        val config =
            (
                parse(
                    ApiConfig.TOKEN_VARIABLE to token,
                    ApiConfig.LISTEN_VARIABLE to "0.0.0.0:9000",
                ) as ApiConfiguration.Listening
            ).config
        config.cookieSecure shouldBe true
        config.bindPort shouldBe 9000
    }

    @Test
    fun `a cross-site cookie is refused on a bind that cannot carry one`() {
        val failure =
            runCatching {
                parse(
                    ApiConfig.TOKEN_VARIABLE to token,
                    ApiConfig.COOKIE_SAMESITE_VARIABLE to "None",
                )
            }.exceptionOrNull()
        failure?.message.orEmpty() shouldContain ApiConfig.COOKIE_SAMESITE_VARIABLE
        // The same combination is fine once there is something to be secure over.
        val config =
            (
                parse(
                    ApiConfig.TOKEN_VARIABLE to token,
                    ApiConfig.LISTEN_VARIABLE to "10.0.0.5:8443",
                    ApiConfig.COOKIE_SAMESITE_VARIABLE to "None",
                ) as ApiConfiguration.Listening
            ).config
        config.cookieSameSite shouldBe SameSite.NONE
        config.cookieSecure shouldBe true
    }

    @Test
    fun `origins, durations and counts are parsed and validated`() {
        val config =
            (
                parse(
                    ApiConfig.TOKEN_VARIABLE to token,
                    ApiConfig.ORIGINS_VARIABLE to "https://ops.example.com/, https://other.example.com",
                    ApiConfig.SESSION_TTL_VARIABLE to "2h",
                    ApiConfig.MAX_STREAMS_VARIABLE to "4",
                ) as ApiConfiguration.Listening
            ).config
        config.allowedOrigins shouldBe setOf("https://ops.example.com", "https://other.example.com")
        config.sessionTtl shouldBe 2.hours
        config.maxStreams shouldBe 4

        runCatching {
            parse(ApiConfig.TOKEN_VARIABLE to token, ApiConfig.SESSION_TTL_VARIABLE to "PT2H")
        }.exceptionOrNull()?.message.orEmpty() shouldContain ApiConfig.SESSION_TTL_VARIABLE
        runCatching {
            parse(ApiConfig.TOKEN_VARIABLE to token, ApiConfig.MAX_STREAMS_VARIABLE to "0")
        }.exceptionOrNull()?.message.orEmpty() shouldContain "positive"
        runCatching {
            parse(ApiConfig.TOKEN_VARIABLE to token, ApiConfig.LISTEN_VARIABLE to "8080")
        }.exceptionOrNull()?.message.orEmpty() shouldContain "host:port"
    }

    @Test
    fun `the token cannot be read back off the configuration`() {
        val config = (parse(ApiConfig.TOKEN_VARIABLE to token) as ApiConfiguration.Listening).config
        // A configuration object is exactly the sort of thing that gets logged at
        // startup, so it has to be safe to print.
        config.toString() shouldNotContain token
        config.token.toString() shouldBe OperatorToken.REDACTED
        "$config" shouldContain OperatorToken.REDACTED

        // Control: the search would find the token if it were there.
        ("$config$token") shouldContain token

        // There is no accessor either — the only instance state is a digest, so
        // there is nothing on the type that a raw token could be recovered from.
        OperatorToken::class.java.declaredFields
            .filterNot {
                it.isSynthetic ||
                    java.lang.reflect.Modifier
                        .isStatic(it.modifiers)
            }.map { it.name to it.type.simpleName } shouldBe listOf("digest" to "byte[]")
    }
}
