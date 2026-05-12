package com.realmap.config;

import com.realmap.security.JwtAuthenticationFilter;
import com.realmap.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 6 설정 클래스.
 *
 * <h3>Spring Boot 3.x / Spring Security 6 변경 사항</h3>
 * <ul>
 *   <li>WebSecurityConfigurerAdapter 가 제거됨 → SecurityFilterChain @Bean 방식으로 대체</li>
 *   <li>람다 DSL 방식 필수 (메서드 체이닝 + 람다 파라미터)</li>
 *   <li>authorizeRequests() → authorizeHttpRequests()</li>
 *   <li>antMatchers() → requestMatchers()</li>
 * </ul>
 *
 * <h3>realMap 보안 정책</h3>
 * <ul>
 *   <li>CSRF: 비활성화 (REST API + JWT 는 CSRF 취약점 없음)</li>
 *   <li>세션: STATELESS (JWT 로 상태를 관리하므로 서버 세션 불필요)</li>
 *   <li>공개 엔드포인트: /api/auth/**, /h2-console/**</li>
 *   <li>나머지: 모두 인증 필요</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** JWT 토큰 검증에 사용할 Provider */
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * JWT 인증 필터 빈을 등록합니다.
     * JwtAuthenticationFilter 는 @Component 가 아니므로 여기서 직접 생성합니다.
     *
     * @return JwtAuthenticationFilter 인스턴스
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtTokenProvider);
    }

    /**
     * Spring Security 필터 체인을 구성합니다.
     *
     * @param http HttpSecurity 빌더 (Spring 이 주입)
     * @return 구성된 SecurityFilterChain
     * @throws Exception HttpSecurity 설정 중 예외
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
            // --- CSRF 보호 비활성화 ---
            // REST API + JWT 방식에서는 CSRF 공격이 불가능 (쿠키 미사용)
            .csrf(csrf -> csrf.disable())

            // --- 세션 관리: STATELESS ---
            // JWT 로 인증하므로 서버 세션을 생성하거나 사용하지 않음
            // 각 요청은 독립적으로 JWT 를 통해 인증
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // --- 엔드포인트 접근 권한 설정 ---
            .authorizeHttpRequests(auth -> auth
                    // 정적 리소스 (index.html, CSS, JS 등) 공개
                    .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                    .requestMatchers("/", "/index.html").permitAll()
                    // OmniOne CX 인증 엔드포인트는 공개 (로그인 전 접근 필요)
                    .requestMatchers("/api/auth/**").permitAll()
                    // H2 콘솔은 개발/데모 환경에서 공개 (운영 시 제거)
                    .requestMatchers("/h2-console/**").permitAll()
                    // 에러 포워딩 경로 허용
                    .requestMatchers("/error").permitAll()
                    .requestMatchers("/uploads/**").permitAll()
                    // 나머지 모든 요청은 JWT 인증 필요
                    .anyRequest().authenticated()
            )

            // --- H2 콘솔을 위한 X-Frame-Options 비활성화 ---
            // H2 콘솔은 <iframe> 을 사용하므로 기본 DENY 정책을 해제
            // 운영 환경에서는 이 설정을 제거하거나 SAMEORIGIN 으로 변경
            .headers(headers ->
                    headers.frameOptions(frame -> frame.disable()))

            // --- JWT 인증 필터 등록 ---
            // UsernamePasswordAuthenticationFilter 앞에 삽입하여
            // Form 로그인 처리 전에 JWT 인증을 먼저 수행
            .addFilterBefore(jwtAuthenticationFilter(),
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
