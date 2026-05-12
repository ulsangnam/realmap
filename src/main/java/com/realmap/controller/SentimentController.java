package com.realmap.controller;

import com.realmap.dto.SentimentRequest;
import com.realmap.dto.SentimentResponse;
import com.realmap.entity.Member;
import com.realmap.repository.MemberRepository;
import com.realmap.service.SentimentService;
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
 * 상호 평가(Sentiment) API 컨트롤러.
 *
 * <h3>상호 평가 규칙</h3>
 * <ul>
 *   <li>COMPLETED 상태의 매칭에 대해서만 평가 제출 가능</li>
 *   <li>매칭 참여자(requester, requestee)만 평가 제출 가능</li>
 *   <li>각 참여자는 상대방에 대해 1회만 제출 가능 (중복 불가)</li>
 *   <li>제출된 평가는 수정/삭제 불가 (불변 엔티티)</li>
 * </ul>
 *
 * <h3>엔드포인트 목록</h3>
 * <pre>
 *   POST  /api/sentiment/submit                  — 상호 평가 제출
 *   GET   /api/sentiment/received/{memberId}     — 특정 회원이 받은 평가 목록
 *   GET   /api/sentiment/verify/{id}             — 평가 SHA-256 무결성 검증
 *   GET   /api/sentiment/matching/{matchingId}   — 매칭의 평가 목록
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/sentiment")
@RequiredArgsConstructor
public class SentimentController {

    private final SentimentService sentimentService;
    private final MemberRepository memberRepository;

    // ─── 공통 헬퍼 ───────────────────────────────────────────

    /**
     * Authentication 객체에서 DID 를 추출하고 회원 엔티티를 반환합니다.
     *
     * @param authentication JWT 기반 인증 객체
     * @return 인증된 회원 엔티티
     * @throws IllegalStateException 회원이 DB 에 존재하지 않는 경우
     */
    private Member getAuthenticatedMember(Authentication authentication) {
        String did = authentication.getName();
        return memberRepository.findByDid(did)
                .orElseThrow(() -> new IllegalStateException(
                        "인증된 DID 에 해당하는 회원을 찾을 수 없습니다. DID: " + did));
    }

    // ─── 상호 평가 제출 ───────────────────────────────────────

    /**
     * 서비스 교환 완료 후 상대방에 대한 평가를 제출합니다.
     *
     * <p>요청 본문 예시:
     * <pre>
     * {
     *   "matchingId": 15,
     *   "score": 5,
     *   "comment": "약속한 서비스를 성실하게 제공해 주셨습니다. 전문성이 뛰어납니다."
     * }
     * </pre>
     * </p>
     *
     * <p>reviewee 는 서비스 계층에서 매칭 정보를 기반으로 자동 결정됩니다:
     * 현재 로그인 사용자가 requester 이면 reviewee = requestee, 반대도 마찬가지.</p>
     *
     * @param request        평점과 코멘트를 담은 요청 DTO
     * @param authentication 현재 인증된 사용자 (평가 작성자)
     * @return 생성된 평가 정보 (201 CREATED)
     */
    @PostMapping("/submit")
    public ResponseEntity<SentimentResponse> submitSentiment(
            @Valid @RequestBody SentimentRequest request,
            Authentication authentication) {

        Member reviewer = getAuthenticatedMember(authentication);
        log.debug("평가 제출 요청. reviewerId: {}, matchingId: {}, score: {}",
                reviewer.getId(), request.getMatchingId(), request.getScore());

        SentimentResponse response = sentimentService.submitSentiment(
                request.getMatchingId(),
                reviewer.getId(),
                request
        );
        // 새 리소스(평가 레코드) 생성이므로 201 CREATED
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ─── 받은 평가 목록 조회 ──────────────────────────────────

    /**
     * 특정 회원이 받은 모든 평가를 최신 순으로 반환합니다.
     * 다른 사람의 평판을 확인하는 용도로 사용할 수 있습니다.
     * (공개 프로필 열람 시 활용)
     *
     * @param memberId 평가를 조회할 회원 PK
     * @return 받은 평가 목록 (200 OK)
     */
    @GetMapping("/received/{memberId}")
    public ResponseEntity<List<SentimentResponse>> getReceivedSentiments(
            @PathVariable Long memberId) {
        log.debug("받은 평가 목록 조회. memberId: {}", memberId);
        List<SentimentResponse> sentiments = sentimentService.getReceivedSentiments(memberId);
        return ResponseEntity.ok(sentiments);
    }

    // ─── 무결성 검증 ──────────────────────────────────────────

    /**
     * 특정 평가의 SHA-256 무결성 해시를 재계산하여 데이터 변조 여부를 검증합니다.
     *
     * <p>블록체인 해커톤 선택과제: "데이터 불변성 증명" 요건을 충족합니다.
     * 저장된 integrityHash 와 현재 필드값으로 재계산한 해시를 비교합니다.</p>
     *
     * <p>응답 예시:
     * <pre>
     * { "sentimentId": 7, "intact": true, "message": "무결성 검증 통과" }
     * </pre>
     * </p>
     *
     * @param id 검증할 Sentiment PK
     * @return 무결성 검증 결과 (200 OK)
     */
    @GetMapping("/verify/{id}")
    public ResponseEntity<Map<String, Object>> verifySentimentIntegrity(@PathVariable Long id) {
        log.debug("평가 무결성 검증 요청. sentimentId: {}", id);

        boolean isIntact = sentimentService.verifySentimentIntegrity(id);

        // 검증 결과를 구조화된 응답으로 반환
        Map<String, Object> result = Map.of(
                "sentimentId", id,
                "intact", isIntact,
                "message", isIntact ? "무결성 검증 통과: 데이터 변조 없음" : "무결성 검증 실패: 데이터 변조 의심"
        );
        return ResponseEntity.ok(result);
    }

    // ─── 매칭의 평가 목록 조회 ────────────────────────────────

    /**
     * 특정 매칭에 제출된 모든 평가를 반환합니다.
     * 매칭 상세 페이지에서 "평가 현황" 표시에 사용합니다.
     * (0개: 미평가, 1개: 한 명 평가, 2개: 양측 완료)
     *
     * @param matchingId 조회할 매칭 PK
     * @return 해당 매칭의 평가 목록 (최대 2개, 200 OK)
     */
    @GetMapping("/matching/{matchingId}")
    public ResponseEntity<List<SentimentResponse>> getSentimentsByMatching(
            @PathVariable Long matchingId) {
        log.debug("매칭 평가 목록 조회. matchingId: {}", matchingId);
        List<SentimentResponse> sentiments = sentimentService.getSentimentsByMatching(matchingId);
        return ResponseEntity.ok(sentiments);
    }

    // ─── 예외 처리 ────────────────────────────────────────────

    /**
     * 비즈니스 규칙 위반(IllegalStateException) 처리.
     * 예: COMPLETED 아닌 매칭에 평가 시도, 중복 평가 시도
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        log.warn("평가 비즈니스 규칙 위반: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("error", "BUSINESS_RULE_VIOLATION", "message", e.getMessage()));
    }

    /**
     * 잘못된 인수(IllegalArgumentException) 처리.
     * 예: 존재하지 않는 매칭/회원 ID
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("잘못된 요청: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }
}
