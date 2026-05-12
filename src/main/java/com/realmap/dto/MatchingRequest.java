package com.realmap.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 서비스 교환 요청 생성 DTO.
 *
 * <p>POST /api/matching/request 로 전달됩니다.
 * requester 는 SecurityContext 의 DID 로 식별하므로 요청 본문에 포함하지 않습니다.</p>
 *
 * <h3>사용 예시 (JSON)</h3>
 * <pre>
 * {
 *   "requesteeId": 42,
 *   "serviceOfferedByRequester": "React 컴포넌트 개발 지원 (4시간)",
 *   "scheduledAt": "2026-05-20T14:00:00"
 * }
 * </pre>
 */
@Getter
@NoArgsConstructor
public class MatchingRequest {

    /**
     * 교환 제안을 받을 회원의 내부 PK.
     * 상대방 탐색 화면에서 선택 후 전달됩니다.
     */
    @NotNull(message = "상대방 회원 ID(requesteeId)는 필수입니다.")
    private Long requesteeId;

    /**
     * requester 가 이 교환에서 제공하겠다고 제안하는 서비스 설명.
     * 상대방이 수락 여부를 판단하는 핵심 정보입니다.
     * 예: "Figma UI 컴포넌트 디자인 (랜딩 페이지 1개)", "영어 이메일 번역 3건"
     */
    @NotBlank(message = "제공할 서비스 설명은 필수입니다.")
    private String serviceOfferedByRequester;

    /**
     * 서비스 교환 예정 일시 (선택 입력).
     * null 이면 일정 미정 상태로 요청됩니다.
     */
    private LocalDateTime scheduledAt;
}
