package lk.boc.ride;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

@Configuration
public class FirebaseSecurityConfig {

    private static final String PROJECT_ID = "rideflow-3dd91";

    @Bean
    public JwtDecoder firebaseJwtDecoder() {
        String issuer =
                "https://securetoken.google.com/" + PROJECT_ID;

        String keysUrl =
                "https://www.googleapis.com/service_accounts/v1/jwk/"
                        + "securetoken@system.gserviceaccount.com";

        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(keysUrl)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        JwtClaimValidator<List<String>> audience =
                new JwtClaimValidator<>(
                        "aud",
                        value -> value != null
                                && value.size() == 1
                                && value.contains(PROJECT_ID)
                );

        JwtClaimValidator<String> subject =
                new JwtClaimValidator<>(
                        "sub",
                        value -> value != null
                                && !value.isBlank()
                                && value.length() <= 128
                );

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<Jwt>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        audience,
                        subject
                )
        );

        return decoder;
    }

    @Bean
    public SecurityFilterChain firebaseAuthChain(
            HttpSecurity http
    ) throws Exception {
        http
                .securityMatcher("/api/auth/**", "/api/rides/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .authorizeHttpRequests(requests -> requests
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                );

        return http.build();
    }
}