package com.realmap.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OmniOne CX (행안부 모바일 신분증) DID 인증 서비스.
 *
 * <h3>OACX 인증 흐름 (해커톤 가이드북 25페이지 기준)</h3>
 * <pre>
 *   1. 프론트엔드: cx.raonsecure.co.kr:17543/ent/esign/oacx-vendor.js 로드
 *   2. 사용자 신분증 QR 스캔 또는 앱 연동
 *   3. OACX.LOAD_MODULE(configUrl, json, callback) 호출
 *   4. 콜백 함수에서 res.token 수신
 *   5. 프론트엔드 → 백엔드: POST /api/auth/did-verify { oacxToken: res.token }
 *   6. 백엔드 (이 서비스): GET trans API?token={encoded_token} 호출
 *   7. 응답에서 DID, 이름, 주소 추출
 *   8. Member 생성/조회 후 JWT 발급
 * </pre>
 *
 * <h3>목 모드 (Mock Mode)</h3>
 * {@code omnione.cx.mock-mode: true} 설정 시 OmniOne 서버 없이도
 * 더미 DID 를 반환하므로 오프라인 데모에서도 사용 가능합니다.
 *
 * <h3>OmniOne CX trans API 응답 형식</h3>
 * <pre>
 * {
 *   "resultCode": "200",
 *   "did": "did:omn:abcdef123",
 *   "name": "홍길동",
 *   "address": "서울특별시 강남구",
 *   "birthDate": "19900101"
 * }
 * </pre>
 */
@Slf4j
@Service
public class DidVerificationService {

    /**
     * OmniOne CX trans API URL.
     * application.yml: omnione.cx.trans-api-url
     */
    @Value("${omnione.cx.trans-api-url}")
    private String transApiUrl;

    /**
     * 목 모드 활성화 여부.
     * application.yml: omnione.cx.mock-mode
     * true 이면 실제 OmniOne 서버 호출 없이 더미 데이터 반환.
     */
    @Value("${omnione.cx.mock-mode:false}")
    private boolean mockMode;

    /**
     * OACX 토큰을 OmniOne trans API 로 검증하고 DID + 지역 정보를 반환합니다.
     *
     * <p>목 모드가 활성화된 경우 서버 호출 없이 즉시 더미 결과를 반환합니다.
     * 목 모드가 비활성화된 경우 실제 trans API 를 WebClient 로 호출합니다.</p>
     *
     * @param oacxToken 프론트엔드 OACX.LOAD_MODULE 콜백에서 받은 res.token 값
     * @return DID 와 지역 정보를 담은 레코드
     * @throws DidVerificationException OmniOne 서버 오류 또는 유효하지 않은 토큰
     */
    public DidVerifyResult verifyAndExtract(String oacxToken) {
        // 목 모드: 해커톤 데모/테스트 환경에서 OmniOne 서버 없이 사용
        if (mockMode) {
            log.warn("=== OmniOne CX 목 모드 활성화 === 실제 DID 검증을 건너뜁니다.");
            return createMockResult(oacxToken);
        }

        // 실제 OmniOne CX trans API 호출
        return callTransApi(oacxToken);
    }

    // ─── 실제 API 호출 ────────────────────────────────────────

    /**
     * OmniOne CX trans API 를 호출하여 DID 정보를 조회합니다.
     *
     * <p>WebClient 를 동기 방식으로 사용 (.block()) 합니다.
     * Spring MVC (Servlet) 환경에서 WebFlux WebClient 를 사용할 경우
     * .block() 이 허용됩니다. 단, Netty 이벤트 루프 스레드에서 호출하면 안 됨.</p>
     *
     * @param oacxToken OACX 토큰
     * @return DidVerifyResult (DID + 지역)
     * @throws DidVerificationException API 호출 실패 또는 응답 파싱 오류
     */
    private DidVerifyResult callTransApi(String oacxToken) {
        try {
            log.debug("OmniOne CX trans API 호출 시작. token 앞 10자: {}...",
                    oacxToken.length() > 10 ? oacxToken.substring(0, 10) : oacxToken);

            // URL 에 쿼리 파라미터로 token 을 인코딩하여 붙임
            String url = UriComponentsBuilder.fromUriString(transApiUrl)
                    .queryParam("token", oacxToken)
                    .build()
                    .toUriString();

            // WebClient 로 GET 요청
            TransApiResponse response = WebClient.create()
                    .get()
                    .uri(url)
                    .retrieve()
                    // 4xx, 5xx 응답 시 예외 발생
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .map(body -> new DidVerificationException(
                                            "OmniOne CX API 오류. HTTP 상태: " + clientResponse.statusCode()
                                                    + ", 본문: " + body)))
                    .bodyToMono(TransApiResponse.class)
                    .block(); // Servlet 환경에서 동기 블로킹 허용

            // 응답 유효성 검사
            if (response == null) {
                throw new DidVerificationException("OmniOne CX trans API 응답이 비어 있습니다.");
            }
            if (!"200".equals(response.resultCode())) {
                throw new DidVerificationException(
                        "OmniOne CX 인증 실패. resultCode: " + response.resultCode());
            }
            if (response.did() == null || response.did().isBlank()) {
                throw new DidVerificationException("OmniOne CX 응답에 DID 가 없습니다.");
            }

            log.debug("OmniOne CX 인증 성공. DID: {}", response.did());
            // 주소가 null 이면 빈 문자열로 처리
            String region = (response.address() != null) ? response.address() : "";
            return new DidVerifyResult(response.did(), region);

        } catch (DidVerificationException e) {
            // 이미 래핑된 예외는 그대로 전파
            throw e;
        } catch (Exception e) {
            // 네트워크 오류, 타임아웃 등
            log.error("OmniOne CX trans API 호출 중 예외 발생: {}", e.getMessage(), e);
            throw new DidVerificationException(
                    "OmniOne CX 서버에 연결할 수 없습니다: " + e.getMessage(), e);
        }
    }

    // ─── 목 모드 ─────────────────────────────────────────────

    /**
     * 목 모드용 더미 DID 결과를 생성합니다.
     * 토큰 값을 기반으로 결정론적인 더미 DID 를 만들어 데모/테스트를 지원합니다.
     *
     * @param oacxToken 원본 토큰 (더미 DID 생성 시드로 사용)
     * @return 더미 DidVerifyResult
     */
    private DidVerifyResult createMockResult(String oacxToken) {
        // 토큰의 첫 8자리를 DID 접미사로 사용 (결정론적 + 충돌 방지)
        String tokenSuffix = oacxToken.length() >= 8
                ? oacxToken.substring(0, 8).replaceAll("[^a-zA-Z0-9]", "x")
                : "mockuser";
        String mockDid = "did:omn:mock-" + tokenSuffix;
        String mockRegion = "서울특별시 강남구";
        log.info("목 모드: 더미 DID 생성 - {}", mockDid);
        return new DidVerifyResult(mockDid, mockRegion);
    }

    // ─── 내부 타입 정의 ───────────────────────────────────────

    /**
     * OmniOne CX trans API 응답을 매핑하는 레코드.
     * Jackson 이 JSON 필드를 자동으로 매핑합니다.
     *
     * 응답 예시:
     * {
     *   "resultCode": "200",
     *   "did": "did:omn:abcdef123",
     *   "name": "홍길동",
     *   "address": "서울특별시 강남구",
     *   "birthDate": "19900101"
     * }
     */
    public record TransApiResponse(
            String resultCode,  // "200" 이면 성공
            String did,         // DID 식별자
            String name,        // 이름 (미사용, 개인정보 최소화 원칙)
            String address,     // 주소 → region 으로 사용
            String birthDate    // 생년월일 (미사용)
    ) {}

    /**
     * DID 인증 결과를 담는 레코드.
     * DidVerificationService → AuthController 로 전달되는 값 객체.
     *
     * @param did    OmniOne 인증된 DID (예: "did:omn:abcdef123")
     * @param region 주소/지역 (예: "서울특별시 강남구")
     */
    public record DidVerifyResult(String did, String region) {}

    /**
     * OmniOne CX DID 인증 실패 시 발생하는 예외.
     * AuthController 의 @ExceptionHandler 에서 401 응답으로 처리됩니다.
     */
    public static class DidVerificationException extends RuntimeException {
        public DidVerificationException(String message) {
            super(message);
        }
        public DidVerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
