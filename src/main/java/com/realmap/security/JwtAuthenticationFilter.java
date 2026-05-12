package com.realmap.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 인증 필터.
 *
 * <p>모든 HTTP 요청에서 JWT 토큰을 추출하고 검증합니다.
 * 유효한 토큰이면 SecurityContextHolder 에 인증 정보를 설정하여
 * 이후 Controller 에서 {@code authentication.getName()} 으로 DID 를 조회할 수 있게 합니다.</p>
 *
 * <h3>처리 흐름</h3>
 * <pre>
 *   HTTP 요청
 *     → Authorization 헤더에서 "Bearer {token}" 추출
 *     → JwtTokenProvider.validateToken(token) 검증
 *     → 유효하면: SecurityContextHolder 에 UsernamePasswordAuthenticationToken 설정
 *     → FilterChain 계속 진행
 * </pre>
 *
 * <h3>OncePerRequestFilter 상속 이유</h3>
 * 단일 요청에서 필터가 두 번 실행되는 것을 방지합니다.
 * (예: Spring 내부의 forward/error dispatch 에서 중복 실행 방지)
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** JWT 생성/검증/파싱 컴포넌트 */
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 요청마다 JWT 토큰을 검사하고 인증 정보를 SecurityContext 에 주입합니다.
     *
     * @param request     HTTP 요청
     * @param response    HTTP 응답
     * @param filterChain 다음 필터 체인
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // --- 1단계: Authorization 헤더에서 토큰 추출 ---
        String token = extractTokenFromRequest(request);

        // --- 2단계: 토큰 존재 여부 및 유효성 검증 ---
        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            // --- 3단계: 토큰에서 DID 추출 ---
            String did = jwtTokenProvider.getDid(token);
            log.debug("JWT 인증 성공 - DID: {}", did);

            // --- 4단계: Spring Security 인증 객체 생성 ---
            // principal = DID (컨트롤러에서 authentication.getName() 으로 접근)
            // credentials = null (JWT 방식에서는 비밀번호 불필요)
            // authorities = ROLE_USER (기본 권한 부여)
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            did,          // principal: DID 문자열
                            null,         // credentials: JWT 방식이므로 null
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))
                    );

            // --- 5단계: SecurityContextHolder 에 인증 정보 설정 ---
            // 이 설정으로 인해 Spring Security 가 이 요청을 "인증된 요청"으로 처리
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } else if (StringUtils.hasText(token)) {
            // 토큰이 있지만 유효하지 않음 — SecurityContext 는 비워둠 (미인증 상태)
            log.debug("JWT 토큰이 유효하지 않습니다. 요청 URI: {}", request.getRequestURI());
        }

        // --- 6단계: 다음 필터로 요청 전달 ---
        filterChain.doFilter(request, response);
    }

    /**
     * HTTP 요청의 Authorization 헤더에서 Bearer 토큰을 추출합니다.
     *
     * <p>Authorization 헤더 형식: {@code "Bearer eyJhbGciOiJIUzI1NiJ9..."}</p>
     *
     * @param request HTTP 요청
     * @return 토큰 문자열 ("Bearer " 접두사 제거됨), 헤더가 없거나 형식이 다르면 null
     */
    private String extractTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");

        // "Bearer " 접두사 확인 후 실제 토큰 부분만 추출
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            // "Bearer " 는 7자이므로 7번째 인덱스부터 실제 JWT 시작
            return bearerToken.substring(7);
        }
        return null;
    }
}
