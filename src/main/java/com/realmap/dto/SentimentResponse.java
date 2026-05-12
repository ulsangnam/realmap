package com.realmap.dto;

import com.realmap.entity.Sentiment;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 상호 평가(Sentiment) 응답 DTO.
 *
 * <p>평가 제출, 받은 평가 목록 조회, 무결성 검증 응답에 공통으로 사용됩니다.</p>
 *
 * <p>integrityHash 를 포함하므로 프론트엔드 또는 감사자(auditor)가
 * SHA-256 을 재계산하여 데이터 무결성을 직접 검증할 수 있습니다.</p>
 */
@Getter
@Builder
public class SentimentResponse {

    /** 평가 내부 PK */
    private Long sentimentId;

    /** 이 평가가 속하는 매칭 PK */
    private Long matchingId;

    // ─── 평가자 정보 ──────────────────────────────────────────

    /** 평가를 작성한 회원 PK */
    private Long reviewerId;

    /** 평가 작성자 닉네임 */
    private String reviewerNickname;

    // ─── 피평가자 정보 ────────────────────────────────────────

    /** 평가를 받은 회원 PK */
    private Long revieweeId;

    /** 피평가자 닉네임 */
    private String revieweeNickname;

    // ─── 평가 내용 ────────────────────────────────────────────

    /** 평점 1~5 */
    private Integer score;

    /** 텍스트 코멘트 (null 가능) */
    private String comment;

    /**
     * SHA-256 무결성 해시.
     * 원문: "matchingId|reviewerId|revieweeId|score|comment|createdAt"
     * 이 값을 이용해 데이터 변조 여부를 검증할 수 있습니다.
     */
    private String integrityHash;

    /** 평가 제출 일시 */
    private LocalDateTime createdAt;

    /**
     * Sentiment 엔티티에서 SentimentResponse DTO 를 생성하는 정적 팩토리 메서드.
     *
     * <p>호출 시점에 reviewer 와 reviewee 가 로드되어 있어야 합니다.
     * SentimentRepository.findAllByRevieweeWithReviewer() 는 reviewer 를 fetch join 하므로
     * 목록 조회에는 안전하게 사용 가능합니다.</p>
     *
     * @param sentiment 변환할 Sentiment 엔티티
     * @return SentimentResponse 인스턴스
     */
    public static SentimentResponse from(Sentiment sentiment) {
        return SentimentResponse.builder()
                .sentimentId(sentiment.getId())
                .matchingId(sentiment.getMatching().getId())
                // 평가자 정보
                .reviewerId(sentiment.getReviewer().getId())
                .reviewerNickname(sentiment.getReviewer().getNickname())
                // 피평가자 정보
                .revieweeId(sentiment.getReviewee().getId())
                .revieweeNickname(sentiment.getReviewee().getNickname())
                // 평가 내용
                .score(sentiment.getScore())
                .comment(sentiment.getComment())
                .integrityHash(sentiment.getIntegrityHash())
                .createdAt(sentiment.getCreatedAt())
                .build();
    }
}
