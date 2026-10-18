package cn.edu.njupt.kms.security;

import cn.edu.njupt.kms.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * JWT 令牌的签发与校验（HS256）。
 *
 * <p>令牌载荷包含用户 id、用户名与角色列表；角色写入令牌可避免每次请求查库，
 * 代价是权限变更后需重新登录才能生效（对课程设计场景可接受）。</p>
 */
@Slf4j
@Service
public class JwtService {

    private final SecretKey key;
    private final long expireMillis;
    private final String issuer;

    public JwtService(AppProperties props) {
        AppProperties.Jwt jwt = props.getJwt();
        byte[] secret = jwt.getSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("kms.jwt.secret 长度不足 32 字节，HS256 无法安全签名");/*长度不够的时候退回*/
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.expireMillis = jwt.getExpireMinutes() * 60_000L;
        this.issuer = jwt.getIssuer();
    }

    /** 签发令牌。 */
    public String generate(UserPrincipal principal) {
        Date now = new Date();
        return Jwts.builder()
                .issuer(issuer) /*签发者*/
                .subject(String.valueOf(principal.getId()))/*用户id*/
                .claim("username", principal.getUsername())
                .claim("realName", principal.getRealName())
                .claim("roles", List.copyOf(principal.getRoles()))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    /** 解析并校验令牌；失败返回 {@code null}（由过滤器决定后续行为）。 */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)/*校验测*/
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 校验失败：{}", e.getMessage());
            return null;
        }
    }

    public long getExpireSeconds() {
        return expireMillis / 1000;
    }/*四个异常：令牌过期，用其余的密钥签名（被篡改），签发者非本系统，密钥长度不够*/
}
