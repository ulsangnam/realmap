package com.realmap.dto;

import com.realmap.entity.Matching;
import com.realmap.entity.MatchingStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 서비스 교환 매칭 응답 DTO.
 *
 * <p>매칭 생성, 수락, 완료, 취소, 조회 응답에 공통으로 사용됩니다.
 * 엔티티를 직접 노출하지 않고 DTO 로 변환하여 순환 참조 방지 및
 * API 응답 구조의 안정성을 보장합니다.</p>
 *
 * <p>submittedSentimentsCount:
 * 0 → 아직 아무도 평가 안 함
 * 1 → 한 명이 평가 제출
 * 2 → 양측 모두 평가 완료 (이상적인 완료 상태)
 * </p>
 */
@Getter
@Builder
public class MatchingResponse {

    /** 매칭 내부 PK */
    private Long matchingId;

    // ─── 요청자 정보 ──────────────────────────────────────────

    /** 교환 요청자 회원 PK */
    private Long requesterId;

    /** 요청자 닉네임 */
    private String requesterNickname;

    /** 요청자의 평소 서비스 설명 (프로필 등록 시 입력) */
    private String requesterServiceDescription;

    // ─── 수신자 정보 ──────────────────────────────────────────

    /** 교환 수신자 회원 PK */
    private Long requesteeId;

    /** 수신자 닉네임 */
    private String requesteeNickname;

    /** 수신자의 평소 서비스 설명 */
    private String requesteeServiceDescription;

    // ─── 이번 교환 서비스 설명 ────────────────────────────────

    /** 이번 교환에서 requester 가 제공하기로 한 서비스 */
    private String serviceOfferedByRequester;

    /**
     * 이번 교환에서 requestee 가 제공하기로 한 서비스.
     * 수락(ACCEPTED) 전에는 null.
     */
    private String serviceOfferedByRequestee;

    // ─── 상태 및 시간 정보 ────────────────────────────────────

    /** 현재 매칭 상태 (REQUESTED / ACCEPTED / COMPLETED / CANCELLED) */
    private MatchingStatus status;

    /** 교환 예정 일시 (null 가능) */
    private LocalDateTime scheduledAt;

    /** 교환 완료 일시 (COMPLETED 상태일 때만 값 있음) */
    private LocalDateTime completedAt;

    /** 매칭 생성 일시 */
    private LocalDateTime createdAt;

    /**
     * 이 매칭에 제출된 Sentiment(평가) 수.
     * 0: 미평가, 1: 한 명 평가, 2: 양측 모두 평가 완료
     */
    private int submittedSentimentsCount;

    /**
     * Matching 엔티티에서 MatchingResponse DTO 를 생성하는 정적 팩토리 메서드.
     *
     * <p>엔티티의 연관 엔티티(requester, requestee)에 접근하므로
     * 호출 시점에 해당 필드가 이미 로드되어 있어야 합니다 (LazyInitializationException 방지).
     * findByIdWithMembers() 등 fetch join 쿼리 결과로 호출할 것.</p>
     *
     * @param matching 변환할 Matching 엔티티
     * @return MatchingResponse 인스턴스
     */
    public static MatchingResponse from(Matching matching) {
        return MatchingResponse.builder()
                .matchingId(matching.getId())
                // 요청자 정보 매핑
                .requesterId(matching.getRequester().getId())
                .requesterNickname(matching.getRequester().getNickname())
                .requesterServiceDescription(matching.getRequester().getServiceDescription())
                // 수신자 정보 매핑
                .requesteeId(matching.getRequestee().getId())
                .requesteeNickname(matching.getRequestee().getNickname())
                .requesteeServiceDescription(matching.getRequestee().getServiceDescription())
                // 이번 교환 서비스 설명
                .serviceOfferedByRequester(matching.getServiceOfferedByRequester())
                .serviceOfferedByRequestee(matching.getServiceOfferedByRequestee())
                // 상태 및 시간
                .status(matching.getStatus())
                .scheduledAt(matching.getScheduledAt())
                .completedAt(matching.getCompletedAt())
                .createdAt(matching.getCreatedAt())
                // 제출된 평가 수 (Sentiment 컬렉션 크기)
                .submittedSentimentsCount(matching.getSentiments().size())
                .build();
    }
}
