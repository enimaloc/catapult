package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.account.UserAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expiryMillis;

    public JwtService(
            SecretKey jwtSecretKey,
            // -1 (the default) means sessions never expire; any non-negative value is taken as hours.
            @Value("${app.jwt.expiry-hours:-1}") long expiryHours) {
        this.key = jwtSecretKey;
        this.expiryMillis = expiryHours < 0 ? -1 : TimeUnit.HOURS.toMillis(expiryHours);
    }

    public String generate(CatapultOAuth2User user) {
        UserAccount account = user.getUserAccount();
        List<String> roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        return generateForUser(account, roles);
    }

    public String generateForUser(UserAccount account, List<String> roles) {
        return generate(account, roles, expiryMillis);
    }

    /** {@link #generateForUser(UserAccount, List)}, expiring after {@code expiry} whatever the configured expiry. */
    public String generateForUser(UserAccount account, List<String> roles, Duration expiry) {
        return generate(account, roles, expiry.toMillis());
    }

    /** A negative {@code validityMillis} means no expiration. */
    private String generate(UserAccount account, List<String> roles, long validityMillis) {
        var builder = Jwts.builder()
                .subject(account.getId().toString())
                .claim("twitchId", account.getTwitchId())
                .claim("username", account.getTwitchUsername())
                .claim("roles", roles)
                .issuedAt(new Date());
        if (validityMillis >= 0) {
            builder.expiration(new Date(System.currentTimeMillis() + validityMillis));
        }
        return builder.signWith(key).compact();
    }

    public Claims validate(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

}
