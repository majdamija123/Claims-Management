package ma.cdg.claims.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import ma.cdg.claims.config.ApplicationProperties;
import ma.cdg.claims.domain.AppUser;
import org.springframework.stereotype.Service;

/** Issues and verifies the bearer tokens used by the Angular client. */
@Service
public class JwtService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_NAME = "name";

    private final SecretKey key;
    private final ApplicationProperties properties;

    public JwtService(ApplicationProperties properties) {
        this.properties = properties;
        byte[] secret = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "cdg.jwt.secret must be at least 32 characters long (HMAC-SHA256 requirement)");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    /** Creates a signed token for a successful authentication. */
    public String issue(AppUser user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getJwt().getExpiration());
        return Jwts.builder()
                .issuer(properties.getJwt().getIssuer())
                .subject(user.getUsername())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_NAME, user.getFullName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    /** Seconds until an issued token expires; used by the client to schedule a refresh. */
    public long expiresInSeconds() {
        return properties.getJwt().getExpiration().toSeconds();
    }

    /** Returns the username carried by a valid token, or empty when the token is unusable. */
    public Optional<String> readUsername(String token) {
        return parse(token).map(Claims::getSubject);
    }

    private Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.getJwt().getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
