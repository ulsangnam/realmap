package com.realmap.controller;

import com.realmap.dto.DidVerifyRequest;
import com.realmap.dto.DidVerifyResponse;
import com.realmap.entity.Member;
import com.realmap.entity.MemberRole;
import com.realmap.repository.MemberRepository;
import com.realmap.security.JwtTokenProvider;
import com.realmap.service.DidVerificationService;
import com.realmap.service.DidVerificationService.DidVerificationException;
import com.realmap.service.DidVerificationService.DidVerifyResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * OmniOne CX DID 인증 및 JWT 발급 컨트롤러.
 *
 * <h3>인증 흐름</h3>
 * <pre>
 *   1. 프론트엔드: OACX.LOAD_MODULE → res.token 수신
 *   2. POST /api/auth/did-verify { oacxToken, nickname, role, serviceDescription }
 *   3. 이 컨트롤러: OmniOne trans API 호출 → DID + 주소 획득
 *   4. DID 로 기존 회원 조회 또는 신규 가입 처리
 *   5. JWT 발급 후 응답
 * </pre>
 *
 * <h3>공개 엔드포인트</h3>
 * /api/auth/** 는 SecurityConfig 에서 permitAll 로 설정되어 JWT 없이 접근 가능합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final DidVerificationService didVerificationService;
    private final MemberRepository memberRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    /**
     * OmniOne CX 토큰을 검증하고 JWT 를 발급합니다.
     *
     * <h3>처리 흐름</h3>
     * <ol>
     *   <li>요청 본문 유효성 검사 (@Valid)</li>
     *   <li>OmniOne trans API 호출로 DID + 지역 획득</li>
     *   <li>DID 로 기존 회원 조회:
     *     <ul>
     *       <li>존재하면: 기존 회원으로 JWT 발급 (isNewMember = false)</li>
     *       <li>없으면: 신규 가입 처리 후 JWT 발급 (isNewMember = true)</li>
     *     </ul>
     *   </li>
     *   <li>DidVerifyResponse 반환</li>
     * </ol>
     *
     * @param request OmniOne 토큰, 닉네임, 역할, 서비스 설명을 담은 요청 DTO
     * @return JWT 액세스 토큰과 회원 정보를 담은 200 OK 응답
     */
    @PostMapping("/did-verify")
    public ResponseEntity<DidVerifyResponse> didVerify(
            @Valid @RequestBody DidVerifyRequest request) {

        log.debug("DID 인증 요청 수신. nickname: {}", request.getNickname());

        // --- 1단계: OmniOne CX trans API 로 DID + 지역 추출 ---
        // 실패 시 DidVerificationException 발생 → @ExceptionHandler 에서 401 반환
        DidVerifyResult didResult = didVerificationService.verifyAndExtract(request.getOacxToken());

        log.debug("OmniOne CX 인증 성공. DID: {}, 지역: {}", didResult.did(), didResult.region());

        // --- 2단계: 기존 회원 여부 확인 ---
        Optional<Member> existingMember = memberRepository.findByDid(didResult.did());

        Member member;
        boolean isNewMember;

        if (existingMember.isPresent()) {
            // 기존 회원: 이미 가입된 DID → 기존 정보 유지, JWT 재발급
            member = existingMember.get();
            isNewMember = false;
            log.info("기존 회원 로그인. DID: {}, memberId: {}", didResult.did(), member.getId());
        } else {
            // 신규 회원: DID 가 처음 등장 → 가입 처리
            member = Member.create(
                    didResult.did(),
                    request.getNickname(),
                    didResult.region(),
                    request.getServiceDescription(),
                    request.getRole()
            );
            member = memberRepository.save(member);
            isNewMember = true;
            log.info("신규 회원 가입 완료. DID: {}, memberId: {}, nickname: {}",
                    didResult.did(), member.getId(), member.getNickname());
        }

        // --- 3단계: DID 를 subject 로 JWT 발급 ---
        String jwt = jwtTokenProvider.generateToken(didResult.did());

        // --- 4단계: 응답 반환 ---
        return ResponseEntity.ok(DidVerifyResponse.of(jwt, member, isNewMember));
    }

    // ── 이메일 회원가입 ───────────────────────────────────────

    @PostMapping("/register")
    public ResponseEntity<DidVerifyResponse> register(@Valid @RequestBody EmailRegisterRequest req) {
        if (memberRepository.existsByEmail(req.getEmail())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(null);
        }
        String hash = passwordEncoder.encode(req.getPassword());
        Member member = Member.createWithEmail(
                req.getEmail(), hash,
                req.getNickname(),
                req.getServiceDescription() != null ? req.getServiceDescription() : "",
                req.getRole() != null ? req.getRole() : MemberRole.BOTH
        );
        member = memberRepository.save(member);
        String jwt = jwtTokenProvider.generateToken(member.getDid());
        log.info("이메일 회원가입 완료. email={}, memberId={}", req.getEmail(), member.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(DidVerifyResponse.of(jwt, member, true));
    }

    // ── 이메일 로그인 ─────────────────────────────────────────

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody EmailLoginRequest req) {
        Member member = memberRepository.findByEmail(req.getEmail())
                .orElse(null);
        if (member == null || !passwordEncoder.matches(req.getPassword(), member.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "INVALID_CREDENTIALS", "message", "이메일 또는 비밀번호가 올바르지 않습니다."));
        }
        String jwt = jwtTokenProvider.generateToken(member.getDid());
        log.info("이메일 로그인 성공. email={}, memberId={}", req.getEmail(), member.getId());
        return ResponseEntity.ok(DidVerifyResponse.of(jwt, member, false));
    }

    // ── 이메일 인증 요청/응답 DTO ────────────────────────────

    @Getter @NoArgsConstructor
    public static class EmailRegisterRequest {
        @NotBlank @Email
        private String email;
        @NotBlank @Size(min = 6, message = "비밀번호는 6자 이상이어야 합니다.")
        private String password;
        @NotBlank
        private String nickname;
        private String serviceDescription;
        private MemberRole role;
    }

    @Getter @NoArgsConstructor
    public static class EmailLoginRequest {
        @NotBlank @Email
        private String email;
        @NotBlank
        private String password;
    }

    /**
     * OmniOne CX DID 인증 실패 시 401 UNAUTHORIZED 를 반환합니다.
     *
     * <p>발생 시나리오:
     * <ul>
     *   <li>유효하지 않은 OACX 토큰</li>
     *   <li>OmniOne 서버 응답 오류</li>
     *   <li>네트워크 연결 실패 (목 모드 비활성화 시)</li>
     * </ul>
     * </p>
     *
     * @param e DidVerificationService 에서 발생한 예외
     * @return 오류 메시지를 포함한 401 응답
     */
    @ExceptionHandler(DidVerificationException.class)
    public ResponseEntity<Map<String, String>> handleDidVerificationException(
            DidVerificationException e) {
        log.warn("DID 인증 실패: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "error", "DID_VERIFICATION_FAILED",
                        "message", e.getMessage()
                ));
    }

    /**
     * 일반 IllegalArgumentException 처리 (회원 데이터 이상 등).
     *
     * @param e 잘못된 인수 예외
     * @return 400 BAD REQUEST 응답
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("잘못된 요청: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }
}
