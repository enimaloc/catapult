package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.UserAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expiryMillis;

    public JwtService(
            SecretKey jwtSecretKey,
            @Value("${app.jwt.expiry-hours:24}") long expiryHours) {
        this.key = jwtSecretKey;
        this.expiryMillis = TimeUnit.HOURS.toMillis(expiryHours);
    }

    public String generate(CatapultOAuth2User user) {
        UserAccount account = user.getUserAccount();
        List<String> roles = user.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .toList();
        return generateForUser(account, roles);
    }

    public String generateForUser(UserAccount account, List<String> roles) {
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

}
