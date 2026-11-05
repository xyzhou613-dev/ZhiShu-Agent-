package com.bemodel.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 签发与校验（HS256）。密钥读 env JWT_SECRET，缺失用内置 dev 值并告警（仅演示环境）。
 */
@Slf4j
@Component
public class JwtService {

    private static final Duration TTL = Duration.ofHours(12);
    private static final String DEV_SECRET = "bemodel-dev-jwt-secret-do-not-use-in-prod";

    private final SecretKey key;

    public JwtService(@Value("${jwt.secret:${JWT_SECRET:}}") String secret) {
        String s = (secret == null || secret.isBlank()) ? DEV_SECRET : secret;
        if (DEV_SECRET.equals(s)) {
            log.warn("JWT_SECRET 未配置，使用内置开发密钥（仅限演示环境）");
        }
        this.key = Keys.hmacShaKeyFor(s.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(PlatformUser user) {
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("role", user.getRole())
                .claim("displayName", user.getDisplayName() == null ? "" : user.getDisplayName())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + TTL.toMillis()))
                .signWith(key)
                .compact();
    }

    /** 校验并解析；无效/过期返回 empty（由安全入口点统一 401） */
    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
