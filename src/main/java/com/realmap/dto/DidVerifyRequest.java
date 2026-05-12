package com.realmap.dto;

import com.realmap.entity.MemberRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * OmniOne CX DID 인증 요청 DTO.
 *
 * <h3>요청 흐름</h3>
 * <pre>
 *   프론트엔드 OACX.LOAD_MODULE 콜백
 *     → res.token 수신
 *     → POST /api/auth/did-verify { "oacxToken": res.token, "nickname": "...", ... }
 * </pre>
 *
 * <h3>신규 vs 기존 회원 처리</h3>
 * <ul>
 *   <li>기존 회원: nickname, role, serviceDescription 은 무시됨 (기존 정보 유지)</li>
 *   <li>신규 회원: nickname 과 role 은 필수, serviceDescription 은 선택</li>
 * </ul>
 */
@Getter
@NoArgsConstructor
public class DidVerifyRequest {

    /**
     * OmniOne CX LOAD_MODULE 콜백에서 전달받은 res.token 값.
     * 이 토큰을 OmniOne trans API 에 전달하여 DID + 주소 정보를 획득합니다.
     * 빈 값이면 인증 자체를 진행할 수 없으므로 @NotBlank.
     */
    @NotBlank(message = "OACX 토큰은 필수입니다.")
    private String oacxToken;

    /**
     * 신규 가입 시 사용할 닉네임.
     * 기존 회원의 경우 이 필드는 무시됩니다 (DB 값 유지).
     * 신규 회원이면 이 값으로 닉네임이 설정됩니다.
     */
    @NotBlank(message = "닉네임은 필수입니다.")
    private String nickname;

    /**
     * 신규 가입 시 설정할 서비스 교환 역할.
     * 기존 회원의 경우 무시됩니다.
     * PROVIDER: 제공자, RECEIVER: 수혜자, BOTH: 양방향
     */
    @NotNull(message = "역할(role)은 필수입니다.")
    private MemberRole role;

    /**
     * 본인이 제공 가능한 서비스 설명 (선택 입력).
     * 예: "React/TypeScript 프론트엔드 개발 지원", "영어 번역 (비즈니스 문서)"
     * 기존 회원이라도 이 값이 있으면 업데이트합니다 (TODO: 현재 구현은 신규만).
     */
    private String serviceDescription;
}
