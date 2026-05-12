package com.realmap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ============================================================
 * realMap - DID 기반 상호 무페이 서비스 교환 평판 플랫폼
 * ============================================================
 *
 * <h2>플랫폼 개념</h2>
 * realMap 는 돈을 주고받지 않고 서로의 재능/능력을 교환하는 플랫폼입니다.
 * 예시:
 *   - 웹 디자이너 A 가 개발자 B 의 코드 리뷰를 받는 대신, B 에게 UI 디자인을 제공
 *   - 영어 번역가 C 가 요리사 D 에게 번역 서비스를 제공하고, D 는 C 에게 쿠킹 클래스를 제공
 *
 * <h2>핵심 개념</h2>
 * <ul>
 *   <li><b>DID 기반 인증</b>: 행안부 모바일 신분증(OmniOne CX)으로 신원 검증</li>
 *   <li><b>무페이 교환</b>: 금전 거래 없이 서비스를 상호 제공</li>
 *   <li><b>상호 평판 시스템</b>: 교환 완료 후 양측이 서로를 평가 (1~5점)</li>
 *   <li><b>무결성 해시</b>: 평가 데이터를 SHA-256 으로 해싱하여 위변조 방지</li>
 * </ul>
 *
 * <h2>역할 분류</h2>
 * <ul>
 *   <li><b>PROVIDER</b>: 서비스 제공자 (자신의 재능을 상대에게 제공)</li>
 *   <li><b>RECEIVER</b>: 서비스 수혜자 (상대의 재능으로부터 서비스를 받음)</li>
 *   <li><b>BOTH</b>: 양방향 — 제공하기도 하고 받기도 함</li>
 * </ul>
 *
 * <h2>서비스 교환 라이프사이클</h2>
 * <pre>
 *   REQUESTED → ACCEPTED → COMPLETED → (양측 Sentiment 제출)
 *             ↘ CANCELLED
 * </pre>
 *
 * <h2>해커톤 정보</h2>
 * 대회: 2026 블록체인 &amp; AI 해커톤
 * 트랙: DID/분산신원 기반 서비스 플랫폼
 * 선택과제:
 *   - OmniOne CX 모바일 신분증 연동 (행안부)
 *   - 상호 평가 무결성 해시 (SHA-256)
 *   - 무페이 서비스 교환 매칭 알고리즘
 *
 * @author realMap Team
 * @version 0.0.1-SNAPSHOT
 */
@SpringBootApplication
public class RealMapApplication {

    /**
     * 애플리케이션 진입점.
     * Spring Boot 의 자동 설정, 컴포넌트 스캔, JPA 스키마 초기화가
     * 이 메서드 호출과 함께 시작됩니다.
     *
     * @param args JVM 실행 인수 (필요 시 spring.profiles.active 등 전달 가능)
     */
    public static void main(String[] args) {
        SpringApplication.run(RealMapApplication.class, args);
    }
}
