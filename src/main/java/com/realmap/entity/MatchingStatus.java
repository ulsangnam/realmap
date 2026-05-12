package com.realmap.entity;

/**
 * 서비스 교환 매칭의 진행 상태를 정의하는 열거형.
 *
 * <p>상태 전이 흐름:</p>
 * <pre>
 *   REQUESTED ──→ ACCEPTED ──→ COMPLETED
 *       │               │
 *       └──→ CANCELLED  └──→ CANCELLED
 * </pre>
 *
 * <p>COMPLETED 상태에 도달해야만 양측이 서로에 대한 Sentiment(평가)를 제출할 수 있습니다.
 * 이 상태 제한은 {@link Matching#canSubmitSentiment(Member)} 에서 강제됩니다.</p>
 */
public enum MatchingStatus {

    /**
     * 서비스 교환 요청됨.
     * requester 가 requestee 에게 교환을 제안한 상태.
     * requestee 의 수락(ACCEPTED) 또는 취소(CANCELLED)를 대기 중.
     */
    REQUESTED,

    /**
     * 교환 수락됨.
     * requestee 가 제안을 수락하고, 본인이 제공할 서비스를 명시한 상태.
     * 실제 서비스 교환이 이 단계에서 진행됨.
     * 이후 교환이 실제로 완료되면 COMPLETED 로 전이.
     */
    ACCEPTED,

    /**
     * 교환 완료.
     * 양측의 서비스 교환이 실제로 이루어진 후 완료 처리된 상태.
     * 이 상태에서만 양측이 Sentiment(평가)를 제출할 수 있음.
     * 신뢰할 수 있는 평판 데이터는 오직 완료된 교환에서만 생성됨.
     */
    COMPLETED,

    /**
     * 취소됨.
     * 요청 또는 수락 단계에서 어느 한쪽이 취소한 상태.
     * 완료된(COMPLETED) 매칭은 취소 불가.
     */
    CANCELLED
}
