package com.syeyoung.assistant.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.invoke
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.web.server.SecurityWebFilterChain

/**
 * Single-user auth: clients send a Google ID token as a bearer token.
 * A token is accepted only if it was issued by Google for one of our client IDs
 * and belongs to the owner's verified email address.
 */
@Configuration
class SecurityConfig(private val props: AppProperties) {

    @Bean
    fun securityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain = http {
        csrf { disable() }
        httpBasic { disable() }
        formLogin { disable() }
        authorizeExchange {
            authorize("/actuator/health/**", permitAll)
            authorize("/v3/api-docs/**", permitAll)
            authorize("/swagger-ui/**", permitAll)
            authorize("/swagger-ui.html", permitAll)
            authorize(anyExchange, if (props.auth.enabled) authenticated else permitAll)
        }
        if (props.auth.enabled) {
            oauth2ResourceServer { jwt { } }
        }
    }

    @Bean
    fun jwtDecoder(): ReactiveJwtDecoder {
        val decoder = NimbusReactiveJwtDecoder.withJwkSetUri(GOOGLE_JWKS_URI).build()
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtTimestampValidator(),
                claimValidator("invalid issuer") { it.issuer?.toString() in GOOGLE_ISSUERS },
                claimValidator("invalid audience") { jwt -> jwt.audience.orEmpty().any { it in props.auth.googleClientIds } },
                claimValidator("not the owner") { jwt ->
                    props.auth.ownerEmail.isNotBlank() &&
                        jwt.getClaimAsString("email").equals(props.auth.ownerEmail, ignoreCase = true) &&
                        jwt.getClaimAsBoolean("email_verified") == true
                },
            ),
        )
        return decoder
    }

    private fun claimValidator(message: String, check: (Jwt) -> Boolean) = OAuth2TokenValidator<Jwt> { jwt ->
        if (check(jwt)) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", message, null))
        }
    }

    private companion object {
        const val GOOGLE_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs"
        val GOOGLE_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
    }
}
