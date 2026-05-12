package com.realmap.service;

import com.realmap.dto.SentimentRequest;
import com.realmap.dto.SentimentResponse;
import com.realmap.entity.Matching;
import com.realmap.entity.MatchingStatus;
import com.realmap.entity.Member;
import com.realmap.entity.Sentiment;
import com.realmap.repository.MatchingRepository;
import com.realmap.repository.MemberRepository;
import com.realmap.repository.SentimentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 상호 평가(Sentiment) 비즈니스 로직 서비스.
 *
 * <h3>상호 평가 원칙</h3>
 * realMap 의 핵심 가치인 "신뢰할 수 있는 평판"은 다음 규칙으로 보장됩니다:
 * <ol>
 *   <li>COMPLETED 상태의 매칭에서만 평가 제출 가능</li>
 *   <li>각 참여자(requester, requestee)는 상대방에 대해 각 1회만 제출 가능</li>
 *   <li>제출된 평가는 절대 수정 불가 (Sentiment 불변 엔티티)</li>
 *   <li>SHA-256 해시로 데이터 무결성 검증 가능</li>
 * </ol>
 *
 * <h3>트랜잭션 정책</h3>
 * 클래스 레벨 @Transactional(readOnly = true) → 기본 읽기 전용
 * 변경 메서드에는 @Transactional(readOnly = false) 개별 적용
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SentimentService {

    private final SentimentRepository sentimentRepository;
    private final MatchingRepository matchingRepository;
    private final MemberRepository memberRepository;
    private final OmniOneChainService omniOneChainService;

    // ─── 평가 제출 ────────────────────────────────────────────

    /**
     * 서비스 교환 완료 후 상대방에 대한 평가를 제출합니다.
     *
     * <h3>처리 흐름</h3>
     * <ol>
     *   <li>매칭 조회 → COMPLETED 상태 검증</li>
     *   <li>reviewer 가 매칭 참여자인지 검증</li>
     *   <li>reviewer 가 requester 이면 reviewee = requestee, 반대도 마찬가지</li>
     *   <li>중복 제출 방지 (동일 매칭+reviewer 조합 존재 여부 확인)</li>
     *   <li>Sentiment 생성 (SHA-256 해시 자동 계산)</li>
     *   <li>reviewee 의 평판 점수 누적</li>
     * </ol>
     *
     * @param matchingId 평가 대상 매칭 PK
     * @param reviewerId 평가 작성자 회원 PK
     * @param dto        점수 및 코멘트
     * @return 생성된 평가 응답 DTO
     * @throws IllegalArgumentException 매칭 또는 회원 미존재, 비참여자 제출 시도 시
     * @throws IllegalStateException    COMPLETED 가 아닌 매칭에 제출 시도 또는 중복 제출 시
     */
    @Transactional
    public SentimentResponse submitSentiment(Long matchingId, Long reviewerId,
                                             SentimentRequest dto) {
        // --- 1단계: 매칭 조회 (requester, requestee fetch join) ---
        Matching matching = matchingRepository.findByIdWithMembers(matchingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "매칭을 찾을 수 없습니다. ID: " + matchingId));

        // --- 2단계: COMPLETED 상태 검증 ---
        if (matching.getStatus() != MatchingStatus.COMPLETED) {
            throw new IllegalStateException(
                    "완료된(COMPLETED) 서비스 교환에 대해서만 평가를 제출할 수 있습니다. " +
                    "현재 상태: " + matching.getStatus());
        }

        // --- 3단계: reviewer 로드 ---
        Member reviewer = memberRepository.findById(reviewerId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "평가자 회원을 찾을 수 없습니다. ID: " + reviewerId));

        // --- 4단계: reviewer 가 이 매칭의 참여자인지 검증 ---
        boolean isRequester = matching.getRequester().getId().equals(reviewerId);
        boolean isRequestee = matching.getRequestee().getId().equals(reviewerId);

        if (!isRequester && !isRequestee) {
            throw new IllegalArgumentException(
                    "서비스 교환 참여자만 평가를 제출할 수 있습니다.");
        }

        // --- 5단계: reviewee 결정 (상대방) ---
        // reviewer 가 requester 이면 → reviewee = requestee
        // reviewer 가 requestee 이면 → reviewee = requester
        Member reviewee = isRequester
                ? matching.getRequestee()
                : matching.getRequester();

        // --- 6단계: 중복 제출 방지 ---
        if (sentimentRepository.existsByMatchingAndReviewer(matching, reviewer)) {
            throw new IllegalStateException(
                    "이미 이 서비스 교환에 대한 평가를 제출하셨습니다. 평가는 한 번만 제출 가능합니다.");
        }

        // --- 7단계: Sentiment 생성 (SHA-256 해시 자동 계산) ---
        Sentiment sentiment = Sentiment.create(
                matching,
                reviewer,
                reviewee,
                dto.getScore(),
                dto.getComment()
        );
        Sentiment savedSentiment = sentimentRepository.save(sentiment);

        // --- 8단계: reviewee 의 평판 점수 누적 ---
        reviewee.addSentimentScore(dto.getScore());
        memberRepository.save(reviewee);

        log.info("평가 제출 완료. sentimentId: {}, matchingId: {}, reviewer: {}, reviewee: {}, score: {}",
                savedSentiment.getId(), matchingId, reviewerId, reviewee.getId(), dto.getScore());

        // --- 9단계: OmniOne Chain 앵커링 (선택과제2 — 비동기, DB 커밋 후 실행) ---
        // DB 트랜잭션이 완전히 커밋된 후에 체인 앵커링을 시작합니다.
        // 체인 실패가 평가 저장 롤백을 유발하지 않도록 트랜잭션 외부에서 실행합니다.
        final Long finalSentimentId = savedSentiment.getId();
        final String finalIntegrityHash = savedSentiment.getIntegrityHash();
        // 평판 앵커링: addSentimentScore() 호출 직후의 최신 점수를 캡처
        final Long finalRevieweeId = reviewee.getId();
        final int finalScoreScaled = reviewee.getAverageSentimentScore()
                .multiply(BigDecimal.valueOf(100)).intValue();
        final int finalCount = reviewee.getSentimentCount();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 평가 무결성 해시 앵커링
                omniOneChainService.anchorSentimentAsync(finalSentimentId, finalIntegrityHash)
                        .thenAccept(txHash -> {
                            if (txHash != null) {
                                updateChainTxHash(finalSentimentId, txHash);
                            }
                        });
                // 평판 점수 앵커링 — reviewee의 최신 평균·횟수를 체인에 기록
                omniOneChainService.anchorReputationAsync(finalRevieweeId, finalScoreScaled, finalCount);
            }
        });

        return SentimentResponse.from(savedSentiment);
    }

    // ─── 체인 TX 해시 업데이트 (내부용) ─────────────────────────

    /**
     * OmniOne Chain 앵커링 완료 후 TX 해시를 Sentiment에 저장합니다.
     * 별도 트랜잭션(@Transactional)으로 실행되어 메인 평가 저장과 독립됩니다.
     *
     * @param sentimentId 업데이트할 Sentiment ID
     * @param txHash      OmniOne Chain 트랜잭션 해시
     */
    @Transactional
    public void updateChainTxHash(Long sentimentId, String txHash) {
        sentimentRepository.findById(sentimentId).ifPresent(s -> {
            s.anchorToChain(txHash);
            sentimentRepository.save(s);
            log.info("[OmniOne Chain] TX 해시 저장 완료. sentimentId={}, txHash={}", sentimentId, txHash);
        });
    }

    // ─── 받은 평가 목록 조회 ──────────────────────────────────

    /**
     * 특정 회원이 받은 모든 평가를 최신 순으로 반환합니다.
     * 리뷰어 정보를 fetch join 으로 함께 로딩하여 N+1 방지합니다.
     *
     * @param memberId 피평가자 회원 PK
     * @return 받은 평가 목록 (최신 순)
     * @throws IllegalArgumentException 회원이 존재하지 않는 경우
     */
    public List<SentimentResponse> getReceivedSentiments(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "회원을 찾을 수 없습니다. ID: " + memberId));

        // reviewer fetch join 쿼리로 N+1 방지
        List<Sentiment> sentiments = sentimentRepository.findAllByRevieweeWithReviewer(member);

        return sentiments.stream()
                .map(SentimentResponse::from)
                .collect(Collectors.toList());
    }

    // ─── 무결성 검증 ──────────────────────────────────────────

    /**
     * 특정 평가의 SHA-256 무결성 해시를 재계산하여 데이터 변조 여부를 검증합니다.
     *
     * <p>저장된 integrityHash 와 현재 필드값으로 재계산한 해시를 비교합니다.
     * 두 값이 다르면 DB 수준에서 데이터가 변조되었음을 의미합니다.</p>
     *
     * @param sentimentId 검증할 Sentiment PK
     * @return 무결성 통과(변조 없음)이면 true, 변조 감지 시 false
     * @throws IllegalArgumentException 평가가 존재하지 않는 경우
     */
    public boolean verifySentimentIntegrity(Long sentimentId) {
        Sentiment sentiment = sentimentRepository.findById(sentimentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "평가를 찾을 수 없습니다. ID: " + sentimentId));

        boolean isIntact = sentiment.verifyIntegrity();

        if (!isIntact) {
            // 무결성 검증 실패 — 감사 로그로 기록
            log.warn("=== 평가 무결성 검증 실패 === sentimentId: {} — 데이터 변조 의심!", sentimentId);
        } else {
            log.debug("평가 무결성 검증 성공. sentimentId: {}", sentimentId);
        }

        return isIntact;
    }

    // ─── 매칭의 평가 목록 조회 ────────────────────────────────

    /**
     * 특정 매칭에 제출된 모든 평가를 반환합니다.
     * 매칭 완료 후 양측 평가 현황(0/1/2개) 확인에 사용합니다.
     *
     * @param matchingId 조회할 매칭 PK
     * @return 해당 매칭의 평가 목록 (최대 2개)
     * @throws IllegalArgumentException 매칭이 존재하지 않는 경우
     */
    public List<SentimentResponse> getSentimentsByMatching(Long matchingId) {
        Matching matching = matchingRepository.findByIdWithMembers(matchingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "매칭을 찾을 수 없습니다. ID: " + matchingId));

        List<Sentiment> sentiments = sentimentRepository.findAllByMatching(matching);

        return sentiments.stream()
                .map(SentimentResponse::from)
                .collect(Collectors.toList());
    }
}
