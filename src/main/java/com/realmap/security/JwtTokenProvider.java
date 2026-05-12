package com.realmap.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * JWT 토큰 생성, 검증, 파싱을 담당하는 컴포넌트.
 *
 * <h3>JWT 구조 (3부분)</h3>
 * <pre>
 *   Header  : {"alg":"HS256","typ":"JWT"}
 *   Payload : {"sub":"did:omn:abc...", "iat":..., "exp":...}
 *   Signature: HMAC-SHA256(base64(header) + "." + base64(payload), secret)
 * </pre>
 *
 * <h3>realMap 에서의 사용 방식</h3>
 * <ol>
 *   <li>AuthController 에서 OmniOne CX 인증 완료 후 DID 를 subject 로 토큰 발급</li>
 *   <li>이후 모든 API 요청의 Authorization 헤더에 "Bearer {token}" 형태로 전달</li>
 *   <li>JwtAuthenticationFilter 가 각 요청에서 토큰을 검증하고 DID 를 SecurityContext 에 설정</li>
 * </ol>
 *
 * <h3>JJWT 0.11.5 API</h3>
 * JJWT 0.11.x 부터 deprecated API 가 정리되었으므로:
 * - {@code Jwts.parserBuilder()} 사용 (구: {@code Jwts.parser()})
 * - {@code Keys.hmacShaKeyFor()} 로 안전한 서명 키 생성
 * - {@code signWith(key, algorithm)} 으로 명시적 알고리즘 지정
 */
@Slf4j
@Component
public class JwtTokenProvider {

    /**
     * JWT 서명 비밀 키 문자열.
     * application.yml 의 jwt.secret 에서 주입됨.
     * 운영 환경에서는 환경변수나 Vault 에서 주입할 것.
     * HS256 을 사용하므로 최소 256비트(32바이트) 이상이어야 함.
     */
    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * JWT 토큰 만료 시간 (밀리초).
     * application.yml 의 jwt.expiration-ms 에서 주입됨.
     * 기본값: 86400000ms = 24시간
     */
    @Value("${jwt.expiration-ms}")
    private long jwtExpirationMs;

    // ─── 토큰 생성 ───────────────────────────────────────────

    /**
     * DID 를 subject 로 하는 JWT 액세스 토큰을 생성합니다.
     *
     * @param did OmniOne CX 에서 인증된 회원의 DID (예: "did:omn:abcdef123")
     * @return 서명된 JWT 문자열 (Bearer 토큰으로 사용)
     */
    public String generateToken(String did) {
        // 현재 시각과 만료 시각 계산
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        // HMAC-SHA256 서명 키 생성 (JJWT 0.11.5 권장 방식)
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());

        return Jwts.builder()
                // sub 클레임: DID 를 사용자 식별자로 설정
                .setSubject(did)
                // iat 클레임: 발급 시각
                .setIssuedAt(now)
                // exp 클레임: 만료 시각
                .setExpiration(expiryDate)
                // HS256 알고리즘으로 서명
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // ─── 토큰 검증 ───────────────────────────────────────────

    /**
     * JWT 토큰의 유효성을 검증합니다.
     * 서명 일치, 만료 여부, 형식 오류를 모두 확인합니다.
     *
     * @param token 검증할 JWT 문자열
     * @return 유효한 토큰이면 true, 유효하지 않으면 false
     */
    public boolean validateToken(String token) {
        try {
            // 토큰 파싱을 시도하면서 서명 검증 및 만료 여부 자동 확인
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
            Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (SecurityException | MalformedJwtException e) {
            // 서명 불일치 또는 형식 오류 — 위조된 토큰
            log.warn("JWT 서명 검증 실패: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            // 토큰 만료 — 재로그인 필요
            log.warn("JWT 토큰 만료: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            // 지원하지 않는 JWT 타입
            log.warn("지원하지 않는 JWT: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            // 토큰 문자열이 null 이거나 비어 있음
            log.warn("JWT 토큰이 비어 있습니다: {}", e.getMessage());
        }
        return false;
    }

    // ─── 토큰 파싱 ───────────────────────────────────────────

    /**
     * JWT 토큰에서 DID(subject)를 추출합니다.
     * 반드시 {@link #validateToken(String)} 으로 검증 후 호출해야 합니다.
     *
     * @param token 유효한 JWT 문자열
     * @return 토큰 subject 에 저장된 DID 문자열
     * @throws JwtException 토큰이 유효하지 않은 경우
     */
    public String getDid(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
        // Claims 파싱 후 subject (= DID) 반환
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }
}
