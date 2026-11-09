package fr.enimaloc.catapult.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;

@Slf4j
@Configuration
public class JwtKeyConfig {

    @Bean
    public SecretKey jwtSecretKey(@Value("${app.jwt.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            log.warn("app.jwt.secret is not configured — generating a random ephemeral key (tokens will not survive restarts)");
            return Jwts.SIG.HS256.key().build();
        }
        try {
            return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        } catch (Exception e) {
            log.warn("app.jwt.secret is not valid Base64 — using raw UTF-8 bytes");
            return Keys.hmacShaKeyFor(secret.getBytes());
        }
    }
}
