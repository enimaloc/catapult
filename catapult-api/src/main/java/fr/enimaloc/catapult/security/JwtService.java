package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.UserAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class JwtService {

    private final SecretKey key;
    private final long expiryMillis;

    public JwtService(
            @Value("${app.jwt.secret:}") String secret,
            @Value("${app.jwt.expiry-hours:24}") long expiryHours) {
        this.key = buildKey(secret);
        this.expiryMillis = TimeUnit.HOURS.toMillis(expiryHours);
    }

    public String generate(CatapultOAuth2User user) {
        UserAccount account = user.getUserAccount();
        List<String> roles = user.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .toList();
        return Jwts.builder()
                .subject(account.getId().toString())
                .claim("twitchId", account.getTwitchId())
                .claim("username", account.getTwitchUsername())
                .claim("roles", roles)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiryMillis))
                .signWith(key)
                .compact();
    }

    public Claims validate(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static SecretKey buildKey(String secret) {
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
