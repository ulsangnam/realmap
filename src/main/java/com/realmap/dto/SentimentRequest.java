package com.realmap.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상호 평가(Sentiment) 제출 요청 DTO.
 *
 * <p>POST /api/sentiment/submit 으로 전달됩니다.
 * reviewer 는 SecurityContext 에서 DID 로 식별하므로 요청 본문에 포함하지 않습니다.
 * reviewee 는 서비스 계층에서 매칭 정보를 기반으로 자동으로 결정합니다
 * (reviewer == requester 이면 reviewee = requestee, 반대도 마찬가지).</p>
 *
 * <h3>사용 예시 (JSON)</h3>
 * <pre>
 * {
 *   "matchingId": 15,
 *   "score": 5,
 *   "comment": "약속된 서비스를 성실하게 제공해 주셔서 감사합니다. 전문성이 뛰어납니다."
 * }
 * </pre>
 */
@Getter
@NoArgsConstructor
public class SentimentRequest {

    /**
     * 평가 대상 매칭의 PK.
     * 이 매칭은 COMPLETED 상태여야 하며, 요청자가 참여자여야 합니다.
     */
    @NotNull(message = "매칭 ID는 필수입니다.")
    private Long matchingId;

    /**
     * 평점 (1~5 정수).
     * 1: 매우 불만족, 2: 불만족, 3: 보통, 4: 만족, 5: 매우 만족
     *
     * @Min, @Max 로 범위 검증.
     * 엔티티 레벨에서도 {@link com.realmap.entity.Member#addSentimentScore} 에서 재검증.
     */
    @NotNull(message = "평점(score)은 필수입니다.")
    @Min(value = 1, message = "평점은 1 이상이어야 합니다.")
    @Max(value = 5, message = "평점은 5 이하여야 합니다.")
    private Integer score;

    /**
     * 텍스트 코멘트 (선택, 최대 500자).
     * 상대방에게 전달되는 정성적 피드백.
     * 내용은 Sentiment 무결성 해시에 포함됩니다.
     */
    @Size(max = 500, message = "코멘트는 500자를 초과할 수 없습니다.")
    private String comment;
}
