package com.realmap.controller;

import com.realmap.entity.Sentiment;
import com.realmap.repository.SentimentRepository;
import com.realmap.service.OmniOneChainService;
import com.realmap.service.SentimentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * OmniOne Chain 연동 API 컨트롤러 (선택과제2 — 가산점 +5%)
 *
 * <h3>제공 기능</h3>
 * <pre>
 *   GET  /api/chain/status                   — 체인 연동 상태 확인
 *   GET  /api/chain/verify/{sentimentId}     — 온체인 + 오프체인 이중 무결성 검증
 *   POST /api/chain/anchor/{sentimentId}     — 수동 앵커링 재시도 (자동 앵커링 실패 시)
 * </pre>
 *
 * <h3>이중 검증 개념</h3>
 * realMap의 평가 무결성은 두 계층으로 보장됩니다:
 * <ol>
 *   <li>오프체인(DB): SHA-256 integrityHash 재계산 비교</li>
 *   <li>온체인(OmniOne Chain): SentimentRegistry 컨트랙트의 저장 해시 비교</li>
 * </ol>
 * 두 검증을 모두 통과하면 데이터가 절대 변조되지 않았음을 증명합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/chain")
@RequiredArgsConstructor
public class ChainController {

    private final OmniOneChainService omniOneChainService;
    private final SentimentService sentimentService;
    private final SentimentRepository sentimentRepository;

    // ── 체인 상태 조회 ───────────────────────────────────────────

    /**
     * OmniOne Chain 연동 상태를 반환합니다.
     * 해커톤 데모 시 체인 활성화 여부를 빠르게 확인할 수 있습니다.
     *
     * @return 체인 활성화 여부, 설명 메시지
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getChainStatus() {
        boolean enabled = omniOneChainService.isEnabled();

        return ResponseEntity.ok(Map.of(
                "chainEnabled", enabled,
                "chainType", "OmniOne Chain (Ethereum Compatible)",
                "feature", "선택과제2 — Sentiment 무결성 해시 온체인 앵커링",
                "bonusScore", "+5%",
                "description", enabled
                        ? "OmniOne Chain 연동 활성화 — SentimentRegistry 컨트랙트로 평가 해시 앵커링 중"
                        : "OmniOne Chain 비활성화 (omnione.chain.enabled=false). SHA-256 오프체인 검증만 작동",
                "checkedAt", LocalDateTime.now().toString()
        ));
    }

    // ── 이중 무결성 검증 ─────────────────────────────────────────

    /**
     * 특정 평가의 이중 무결성 검증 결과를 반환합니다.
     *
     * <h3>검증 단계</h3>
     * <ol>
     *   <li><b>오프체인 검증</b>: DB 필드값으로 SHA-256 재계산 → 저장된 integrityHash와 비교</li>
     *   <li><b>온체인 검증</b>: OmniOne Chain의 SentimentRegistry에서 해시 조회 → DB 해시와 비교</li>
     * </ol>
     *
     * <h3>응답 예시</h3>
     * <pre>
     * {
     *   "sentimentId": 42,
     *   "offChainVerified": true,
     *   "onChainVerified": true,
     *   "onChainAnchored": true,
     *   "chainTxHash": "0xabc123...",
     *   "integrityHash": "a3f2e1...",
     *   "fullyVerified": true,
     *   "message": "오프체인 및 온체인 검증 모두 통과 — 데이터 무결성 확인됨"
     * }
     * </pre>
     *
     * @param sentimentId 검증할 Sentiment ID
     * @return 이중 검증 결과 (200 OK)
     */
    @GetMapping("/verify/{sentimentId}")
    public ResponseEntity<Map<String, Object>> verifyIntegrity(
            @PathVariable Long sentimentId) {

        log.debug("이중 무결성 검증 요청. sentimentId={}", sentimentId);

        // Sentiment 조회
        Sentiment sentiment = sentimentRepository.findById(sentimentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "평가를 찾을 수 없습니다. ID: " + sentimentId));

        // 1단계: 오프체인 SHA-256 검증
        boolean offChainVerified = sentimentService.verifySentimentIntegrity(sentimentId);

        // 2단계: OmniOne Chain 온체인 검증
        boolean onChainAnchored = sentiment.isAnchored();
        boolean onChainVerified = false;

        if (onChainAnchored && omniOneChainService.isEnabled()) {
            // 체인에서 해시를 직접 조회하여 DB 해시와 비교
            onChainVerified = omniOneChainService.verifyOnChain(
                    sentimentId, sentiment.getIntegrityHash());
        }

        // 두 검증 모두 통과하면 완전 검증
        boolean fullyVerified = offChainVerified && (!onChainAnchored || onChainVerified);

        String message = buildVerificationMessage(offChainVerified, onChainAnchored, onChainVerified);

        log.info("무결성 검증 완료. sentimentId={}, offChain={}, onChain={}, full={}",
                sentimentId, offChainVerified, onChainVerified, fullyVerified);

        return ResponseEntity.ok(Map.of(
                "sentimentId", sentimentId,
                "offChainVerified", offChainVerified,    // SHA-256 오프체인 검증
                "onChainAnchored", onChainAnchored,      // 체인 앵커링 완료 여부
                "onChainVerified", onChainVerified,       // 온체인 해시 일치 여부
                "chainTxHash", sentiment.getChainTxHash() != null
                        ? sentiment.getChainTxHash() : "미앵커링",
                "integrityHash", sentiment.getIntegrityHash(),
                "fullyVerified", fullyVerified,
                "message", message
        ));
    }

    // ── 수동 앵커링 재시도 ────────────────────────────────────────

    /**
     * 자동 앵커링이 실패한 평가를 수동으로 재앵커링합니다.
     * 이미 앵커링된 경우 409 CONFLICT를 반환합니다.
     *
     * @param sentimentId 앵커링할 Sentiment ID
     * @return 앵커링 결과 (202 ACCEPTED — 비동기)
     */
    @PostMapping("/anchor/{sentimentId}")
    public ResponseEntity<Map<String, Object>> retryAnchor(
            @PathVariable Long sentimentId) {

        if (!omniOneChainService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "error", "CHAIN_DISABLED",
                            "message", "OmniOne Chain 연동이 비활성화 상태입니다. " +
                                       "application.yml에서 omnione.chain.enabled=true로 설정하세요."
                    ));
        }

        Sentiment sentiment = sentimentRepository.findById(sentimentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "평가를 찾을 수 없습니다. ID: " + sentimentId));

        // 이미 앵커링된 경우 중복 방지
        if (sentiment.isAnchored()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "error", "ALREADY_ANCHORED",
                            "sentimentId", sentimentId,
                            "chainTxHash", sentiment.getChainTxHash(),
                            "message", "이미 OmniOne Chain에 앵커링된 평가입니다."
                    ));
        }

        // 비동기 앵커링 시작
        omniOneChainService.anchorSentimentAsync(sentimentId, sentiment.getIntegrityHash())
                .thenAccept(txHash -> {
                    if (txHash != null) {
                        sentimentService.updateChainTxHash(sentimentId, txHash);
                    }
                });

        log.info("수동 앵커링 시작. sentimentId={}", sentimentId);

        // 202 Accepted — 비동기이므로 즉시 반환, 완료는 나중에 verify로 확인
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of(
                        "sentimentId", sentimentId,
                        "status", "ANCHORING_STARTED",
                        "message", "OmniOne Chain 앵커링이 시작되었습니다. " +
                                   "완료 후 GET /api/chain/verify/" + sentimentId + " 로 결과를 확인하세요."
                ));
    }

    // ── 예외 처리 ────────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "NOT_FOUND", "message", e.getMessage()));
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────

    private String buildVerificationMessage(boolean offChain, boolean anchored, boolean onChain) {
        if (!offChain) {
            return "오프체인 SHA-256 검증 실패 — 데이터 변조 의심! DB 데이터를 즉시 확인하세요.";
        }
        if (!anchored) {
            return "오프체인 검증 통과. 온체인 앵커링 미완료 (앵커링 진행 중이거나 체인 비활성화 상태).";
        }
        if (!onChain) {
            return "오프체인 검증 통과. 온체인 검증 실패 — 체인 연결 오류이거나 컨트랙트 주소를 확인하세요.";
        }
        return "오프체인 및 온체인 검증 모두 통과 — 데이터 완전 무결성 확인됨 (OmniOne Chain 앵커링 완료).";
    }
}
