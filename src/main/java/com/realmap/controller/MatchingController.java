package com.realmap.controller;

import com.realmap.dto.MatchingRequest;
import com.realmap.dto.MatchingResponse;
import com.realmap.entity.Member;
import com.realmap.repository.MemberRepository;
import com.realmap.service.MatchingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 서비스 교환 매칭 API 컨트롤러.
 *
 * <h3>인증 방식</h3>
 * 모든 엔드포인트는 JWT 인증 필요 (SecurityConfig 설정).
 * DID 는 {@code authentication.getName()} 으로 추출하고,
 * DID → memberId 변환은 MemberRepository 를 통해 수행합니다.
 *
 * <h3>엔드포인트 목록</h3>
 * <pre>
 *   POST   /api/matching/request           — 서비스 교환 요청 생성
 *   PUT    /api/matching/{id}/accept       — 수락 (제공 서비스 설명 포함)
 *   PUT    /api/matching/{id}/complete     — 교환 완료 처리
 *   PUT    /api/matching/{id}/cancel       — 취소
 *   GET    /api/matching/my               — 내 매칭 목록
 *   GET    /api/matching/{id}             — 매칭 상세
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/matching")
@RequiredArgsConstructor
public class MatchingController {

    private final MatchingService matchingService;
    private final MemberRepository memberRepository;

    // ─── 공통 헬퍼 ───────────────────────────────────────────

    /**
     * SecurityContext 의 Authentication 에서 DID 를 추출하고,
     * 해당 DID 로 회원을 조회하여 반환합니다.
     *
     * @param authentication Spring Security 인증 객체 (JwtAuthenticationFilter 에서 설정됨)
     * @return 인증된 회원 엔티티
     * @throws IllegalStateException DID 에 해당하는 회원이 없는 경우 (DB 불일치)
     */
    private Member getAuthenticatedMember(Authentication authentication) {
        // JwtAuthenticationFilter 에서 DID 를 principal 로 설정
        String did = authentication.getName();
        return memberRepository.findByDid(did)
                .orElseThrow(() -> new IllegalStateException(
                        "인증된 DID 에 해당하는 회원을 찾을 수 없습니다. DID: " + did));
    }

    // ─── 서비스 교환 요청 ─────────────────────────────────────

    /**
     * 새로운 서비스 교환을 요청합니다.
     *
     * <p>요청 본문 예시:
     * <pre>
     * {
     *   "requesteeId": 42,
     *   "serviceOfferedByRequester": "React UI 컴포넌트 개발 (3시간)",
     *   "scheduledAt": "2026-05-20T14:00:00"
     * }
     * </pre>
     * </p>
     *
     * @param request        교환 요청 데이터
     * @param authentication 현재 인증된 사용자 (JWT 기반)
     * @return 생성된 매칭 정보 (201 CREATED)
     */
    @PostMapping("/request")
    public ResponseEntity<MatchingResponse> requestMatching(
            @Valid @RequestBody MatchingRequest request,
            Authentication authentication) {

        Member requester = getAuthenticatedMember(authentication);
        log.debug("서비스 교환 요청. requesterId: {}, requesteeId: {}",
                requester.getId(), request.getRequesteeId());

        MatchingResponse response = matchingService.requestMatching(requester.getId(), request);
        // 새 리소스 생성이므로 201 CREATED 반환
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ─── 서비스 교환 수락 ─────────────────────────────────────

    /**
     * requestee 가 서비스 교환 요청을 수락합니다.
     *
     * <p>요청 본문 예시 (serviceOfferedByRequestee 만 포함):
     * <pre>
     * {
     *   "serviceOfferedByRequestee": "Figma UI 디자인 피드백 및 시안 제작"
     * }
     * </pre>
     * </p>
     *
     * @param id             수락할 매칭 PK
     * @param body           제공할 서비스 설명을 담은 맵 (key: "serviceOfferedByRequestee")
     * @param authentication 현재 인증된 사용자
     * @return 수락된 매칭 정보 (200 OK)
     */
    @PutMapping("/{id}/accept")
    public ResponseEntity<MatchingResponse> acceptMatching(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            Authentication authentication) {

        Member requestee = getAuthenticatedMember(authentication);
        // 수락 시 requestee 가 제공할 서비스 설명을 요청 본문에서 추출
        String serviceOfferedByRequestee = body.getOrDefault("serviceOfferedByRequestee", "");

        log.debug("서비스 교환 수락. matchingId: {}, requesteeId: {}", id, requestee.getId());

        MatchingResponse response = matchingService.acceptMatching(
                id, requestee.getId(), serviceOfferedByRequestee);
        return ResponseEntity.ok(response);
    }

    // ─── 서비스 교환 완료 처리 ────────────────────────────────

    /**
     * 서비스 교환이 실제로 이루어졌음을 완료 처리합니다.
     * requester 또는 requestee 누구나 완료 처리 가능합니다.
     * 완료 후 양측이 상호 평가(Sentiment)를 제출할 수 있습니다.
     *
     * @param id             완료 처리할 매칭 PK
     * @param authentication 현재 인증된 사용자
     * @return 완료 처리된 매칭 정보 (200 OK)
     */
    @PutMapping("/{id}/complete")
    public ResponseEntity<MatchingResponse> completeMatching(
            @PathVariable Long id,
            Authentication authentication) {

        Member member = getAuthenticatedMember(authentication);
        log.debug("서비스 교환 완료 처리. matchingId: {}, memberId: {}", id, member.getId());

        MatchingResponse response = matchingService.completeMatching(id, member.getId());
        return ResponseEntity.ok(response);
    }

    // ─── 서비스 교환 취소 ─────────────────────────────────────

    /**
     * 서비스 교환을 취소합니다.
     * COMPLETED 상태의 매칭은 취소할 수 없습니다.
     *
     * @param id             취소할 매칭 PK
     * @param authentication 현재 인증된 사용자
     * @return 취소된 매칭 정보 (200 OK)
     */
    @PutMapping("/{id}/cancel")
    public ResponseEntity<MatchingResponse> cancelMatching(
            @PathVariable Long id,
            Authentication authentication) {

        Member member = getAuthenticatedMember(authentication);
        log.debug("서비스 교환 취소. matchingId: {}, memberId: {}", id, member.getId());

        MatchingResponse response = matchingService.cancelMatching(id, member.getId());
        return ResponseEntity.ok(response);
    }

    // ─── 내 매칭 목록 조회 ────────────────────────────────────

    /**
     * 현재 로그인한 사용자의 모든 매칭 목록을 반환합니다.
     * requester 또는 requestee 로 참여한 매칭을 최신 순으로 반환합니다.
     *
     * @param authentication 현재 인증된 사용자
     * @return 매칭 목록 (200 OK)
     */
    @GetMapping("/my")
    public ResponseEntity<List<MatchingResponse>> getMyMatchings(
            Authentication authentication) {

        Member member = getAuthenticatedMember(authentication);
        log.debug("내 매칭 목록 조회. memberId: {}", member.getId());

        List<MatchingResponse> matchings = matchingService.getMyMatchings(member.getId());
        return ResponseEntity.ok(matchings);
    }

    // ─── 매칭 상세 조회 ───────────────────────────────────────

    /**
     * 특정 매칭의 상세 정보를 반환합니다.
     *
     * @param id 조회할 매칭 PK
     * @return 매칭 상세 정보 (200 OK)
     */
    @GetMapping("/{id}")
    public ResponseEntity<MatchingResponse> getMatchingDetail(@PathVariable Long id) {
        log.debug("매칭 상세 조회. matchingId: {}", id);
        return ResponseEntity.ok(matchingService.getMatchingDetail(id));
    }

    // ─── 예외 처리 ────────────────────────────────────────────

    /**
     * 비즈니스 규칙 위반(IllegalStateException) 처리.
     * 예: 진행 중인 매칭 중복, 잘못된 상태 전이 등
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        log.warn("비즈니스 규칙 위반: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("error", "BUSINESS_RULE_VIOLATION", "message", e.getMessage()));
    }

    /**
     * 잘못된 인수(IllegalArgumentException) 처리.
     * 예: 존재하지 않는 회원/매칭 ID, 권한 없음
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("잘못된 요청: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }
}
