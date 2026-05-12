package com.realmap.dto;

import com.realmap.entity.Member;
import com.realmap.entity.MemberRole;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * OmniOne CX DID 인증 응답 DTO.
 *
 * <p>인증 성공 시 프론트엔드에 반환합니다.
 * 프론트엔드는 accessToken 을 로컬 스토리지에 저장하고
 * 이후 모든 API 요청의 Authorization 헤더에 포함시킵니다.</p>
 *
 * <p>isNewMember 를 통해 신규 가입 화면과 기존 회원 로그인 화면을 구분합니다.</p>
 */
@Getter
@Builder
public class DidVerifyResponse {

    /** JWT 액세스 토큰. "Bearer {accessToken}" 형태로 API 요청 헤더에 포함 */
    private String accessToken;

    /** 신규 가입 여부. true 이면 온보딩 화면으로 이동 */
    private boolean isNewMember;

    /** 회원 내부 PK (프론트엔드 API 호출 시 memberId 파라미터에 사용) */
    private Long memberId;

    /** 플랫폼 내 표시 닉네임 */
    private String nickname;

    /** OmniOne 에서 가져온 지역 정보 (예: "서울특별시 강남구") */
    private String region;

    /** 서비스 교환 역할 (PROVIDER / RECEIVER / BOTH) */
    private MemberRole role;

    /** 본인이 제공 가능한 서비스 설명 */
    private String serviceDescription;

    /** 현재 평균 평판 점수 (소수점 2자리). 신규 회원은 0.00 */
    private BigDecimal averageSentimentScore;

    /** 수신한 평가(Sentiment) 총 횟수 */
    private Integer sentimentCount;

    /**
     * Member 엔티티와 JWT 로부터 응답 DTO 를 생성하는 정적 팩토리 메서드.
     *
     * @param jwt         발급된 JWT 액세스 토큰 문자열
     * @param member      인증된 Member 엔티티
     * @param isNewMember 이번 요청으로 신규 가입되었으면 true
     * @return 클라이언트에 반환할 DidVerifyResponse 인스턴스
     */
    public static DidVerifyResponse of(String jwt, Member member, boolean isNewMember) {
        return DidVerifyResponse.builder()
                .accessToken(jwt)
                .isNewMember(isNewMember)
                .memberId(member.getId())
                .nickname(member.getNickname())
                .region(member.getRegion())
                .role(member.getRole())
                .serviceDescription(member.getServiceDescription())
                // 평균 점수 계산은 Member 도메인 메서드에 위임
                .averageSentimentScore(member.getAverageSentimentScore())
                .sentimentCount(member.getSentimentCount())
                .build();
    }
}
