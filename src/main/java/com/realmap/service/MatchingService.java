package com.realmap.service;

import com.realmap.dto.MatchingRequest;
import com.realmap.dto.MatchingResponse;
import com.realmap.entity.Matching;
import com.realmap.entity.Member;
import com.realmap.repository.MatchingRepository;
import com.realmap.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 서비스 교환 매칭 비즈니스 로직 서비스.
 *
 * <p>realMap 의 핵심 서비스로, 서비스 교환 요청·수락·완료·취소 흐름을 처리합니다.
 * 모든 상태 전이는 Matching 엔티티의 도메인 메서드(accept/complete/cancel)를 통해 이루어집니다.
 * 서비스 계층은 유효성 검사 및 조회 책임만 담당하여 비즈니스 규칙은 도메인에 위임합니다.</p>
 *
 * <h3>트랜잭션 정책</h3>
 * 클래스 레벨 @Transactional(readOnly = true) → 기본 읽기 전용
 * 변경 메서드에는 @Transactional(readOnly = false) 개별 적용 → 쓰기 가능
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MatchingService {

    private final MatchingRepository matchingRepository;
    private final MemberRepository memberRepository;

    // ─── 서비스 교환 요청 ─────────────────────────────────────

    /**
     * 새로운 서비스 교환 요청을 생성합니다 (REQUESTED 상태).
     *
     * <p>검증 사항:
     * <ol>
     *   <li>requester 와 requestee 가 모두 존재하는 회원인지 확인</li>
     *   <li>자기 자신에게 요청하는지 방지</li>
     *   <li>두 회원 사이에 현재 진행 중인(REQUESTED/ACCEPTED) 매칭이 없는지 확인</li>
     * </ol>
     * </p>
     *
     * @param requesterId 요청자 회원 PK (SecurityContext 의 DID 로 조회됨)
     * @param dto         교환 요청 데이터 (requesteeId, serviceOfferedByRequester, scheduledAt)
     * @return 생성된 매칭 응답 DTO
     * @throws IllegalArgumentException 회원이 존재하지 않거나 자기 자신에게 요청하는 경우
     * @throws IllegalStateException    두 회원 간 진행 중인 매칭이 이미 존재하는 경우
     */
    @Transactional
    public MatchingResponse requestMatching(Long requesterId, MatchingRequest dto) {
        // 요청자 조회
        Member requester = memberRepository.findById(requesterId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "요청자를 찾을 수 없습니다. ID: " + requesterId));

        // 수신자 조회
        Member requestee = memberRepository.findById(dto.getRequesteeId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "교환 상대방을 찾을 수 없습니다. ID: " + dto.getRequesteeId()));

        // 자기 자신에게 요청 방지
        if (requesterId.equals(dto.getRequesteeId())) {
            throw new IllegalArgumentException("자기 자신에게 서비스 교환을 요청할 수 없습니다.");
        }

        // 진행 중인 매칭 중복 방지: 두 회원 간 REQUESTED 또는 ACCEPTED 매칭이 이미 있으면 차단
        if (matchingRepository.existsActiveMatchingBetween(requester, requestee)) {
            throw new IllegalStateException(
                    "이미 진행 중인 서비스 교환이 있습니다. 기존 교환이 완료되거나 취소된 후 재요청해 주세요.");
        }

        // 매칭 생성 (REQUESTED 상태로 초기화)
        Matching matching = Matching.createRequest(
                requester,
                requestee,
                dto.getServiceOfferedByRequester(),
                dto.getScheduledAt()
        );
        Matching saved = matchingRepository.save(matching);

        log.info("서비스 교환 요청 생성 완료. matchingId: {}, requester: {}, requestee: {}",
                saved.getId(), requesterId, dto.getRequesteeId());

        return MatchingResponse.from(saved);
    }

    // ─── 서비스 교환 수락 ─────────────────────────────────────

    /**
     * requestee 가 서비스 교환 요청을 수락합니다 (REQUESTED → ACCEPTED).
     *
     * <p>수락 시 requestee 가 본인이 제공할 서비스를 명시합니다.
     * 이 시점부터 두 참여자 모두 서비스 제공 의무를 가집니다.</p>
     *
     * @param matchingId                  수락할 매칭 PK
     * @param requesteeId                 수락 요청자의 회원 PK (본인 검증용)
     * @param serviceOfferedByRequestee   requestee 가 이번 교환에서 제공할 서비스 설명
     * @return 수락 처리된 매칭 응답 DTO
     * @throws IllegalArgumentException 매칭이 존재하지 않거나 requestee 가 아닌 사람이 수락 시도 시
     * @throws IllegalStateException    REQUESTED 상태가 아닐 때 (Matching.accept 에서 발생)
     */
    @Transactional
    public MatchingResponse acceptMatching(Long matchingId, Long requesteeId,
                                           String serviceOfferedByRequestee) {
        // fetch join 으로 requester/requestee 함께 로딩 (N+1 방지)
        Matching matching = matchingRepository.findByIdWithMembers(matchingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "매칭을 찾을 수 없습니다. ID: " + matchingId));

        // 이 매칭의 requestee 본인인지 검증 — 다른 사람이 수락 불가
        if (!matching.getRequestee().getId().equals(requesteeId)) {
            throw new IllegalArgumentException(
                    "교환 수락 권한이 없습니다. 수신자 본인만 수락할 수 있습니다.");
        }

        // 상태 전이: REQUESTED → ACCEPTED (Matching 도메인 메서드에 위임)
        matching.accept(serviceOfferedByRequestee);
        matchingRepository.save(matching);

        log.info("서비스 교환 수락 완료. matchingId: {}, requesteeId: {}", matchingId, requesteeId);

        return MatchingResponse.from(matching);
    }

    // ─── 서비스 교환 완료 처리 ────────────────────────────────

    /**
     * 서비스 교환이 실제로 이루어졌음을 완료 처리합니다 (ACCEPTED → COMPLETED).
     * 완료 후에는 양측이 상호 Sentiment 를 제출할 수 있습니다.
     * requester 또는 requestee 중 누구나 완료 처리 가능합니다.
     *
     * @param matchingId 완료 처리할 매칭 PK
     * @param memberId   완료 처리를 요청하는 회원 PK (참여자 여부 검증용)
     * @return 완료 처리된 매칭 응답 DTO
     * @throws IllegalArgumentException 매칭 미존재 또는 비참여자가 요청 시
     * @throws IllegalStateException    ACCEPTED 상태가 아닐 때 (Matching.complete 에서 발생)
     */
    @Transactional
    public MatchingResponse completeMatching(Long matchingId, Long memberId) {
        Matching matching = matchingRepository.findByIdWithMembers(matchingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "매칭을 찾을 수 없습니다. ID: " + matchingId));

        // 참여자 검증: requester 또는 requestee 만 완료 처리 가능
        boolean isParticipant = matching.getRequester().getId().equals(memberId)
                || matching.getRequestee().getId().equals(memberId);
        if (!isParticipant) {
            throw new IllegalArgumentException("이 서비스 교환의 참여자만 완료 처리할 수 있습니다.");
        }

        // 상태 전이: ACCEPTED → COMPLETED (완료 일시 자동 기록)
        matching.complete();
        matchingRepository.save(matching);

        log.info("서비스 교환 완료 처리. matchingId: {}, 처리자: {}", matchingId, memberId);

        return MatchingResponse.from(matching);
    }

    // ─── 서비스 교환 취소 ─────────────────────────────────────

    /**
     * 서비스 교환을 취소합니다 (REQUESTED|ACCEPTED → CANCELLED).
     * requester 또는 requestee 중 누구나 취소할 수 있으며,
     * 이미 완료된(COMPLETED) 교환은 취소할 수 없습니다.
     *
     * @param matchingId 취소할 매칭 PK
     * @param memberId   취소를 요청하는 회원 PK (참여자 여부 검증용)
     * @return 취소 처리된 매칭 응답 DTO
     * @throws IllegalArgumentException 매칭 미존재 또는 비참여자가 요청 시
     * @throws IllegalStateException    COMPLETED 또는 이미 CANCELLED 상태 (Matching.cancel 에서 발생)
     */
    @Transactional
    public MatchingResponse cancelMatching(Long matchingId, Long memberId) {
        Matching matching = matchingRepository.findByIdWithMembers(matchingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "매칭을 찾을 수 없습니다. ID: " + matchingId));

        // 참여자 검증
        boolean isParticipant = matching.getRequester().getId().equals(memberId)
                || matching.getRequestee().getId().equals(memberId);
        if (!isParticipant) {
            throw new IllegalArgumentException("이 서비스 교환의 참여자만 취소할 수 있습니다.");
        }

        // 상태 전이: REQUESTED|ACCEPTED → CANCELLED
        matching.cancel();
        matchingRepository.save(matching);

        log.info("서비스 교환 취소. matchingId: {}, 취소자: {}", matchingId, memberId);

        return MatchingResponse.from(matching);
    }

    // ─── 내 매칭 목록 조회 ────────────────────────────────────

    /**
     * 특정 회원이 참여한 모든 매칭 목록을 최신 순으로 반환합니다.
     * requester 또는 requestee 로 참여한 매칭을 모두 포함합니다.
     *
     * @param memberId 조회할 회원 PK
     * @return 매칭 목록 (최신 순)
     * @throws IllegalArgumentException 회원이 존재하지 않는 경우
     */
    public List<MatchingResponse> getMyMatchings(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "회원을 찾을 수 없습니다. ID: " + memberId));

        // 내가 참여한 모든 매칭 조회 (JPQL 커스텀 쿼리 사용)
        List<Matching> matchings = matchingRepository.findAllByMember(member);

        // 엔티티 → DTO 변환
        return matchings.stream()
                .map(MatchingResponse::from)
                .collect(Collectors.toList());
    }

    // ─── 매칭 단건 조회 ───────────────────────────────────────

    /**
     * 매칭 ID 로 매칭 상세 정보를 조회합니다.
     * requester 와 requestee 를 fetch join 으로 함께 로딩합니다.
     *
     * @param matchingId 조회할 매칭 PK
     * @return 매칭 상세 응답 DTO
     * @throws IllegalArgumentException 매칭이 존재하지 않는 경우
     */
    public MatchingResponse getMatchingDetail(Long matchingId) {
        Matching matching = matchingRepository.findByIdWithMembers(matchingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "매칭을 찾을 수 없습니다. ID: " + matchingId));
        return MatchingResponse.from(matching);
    }
}
