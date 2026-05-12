package com.realmap.repository;

import com.realmap.entity.Matching;
import com.realmap.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 서비스 교환 매칭 데이터 접근 레포지토리.
 *
 * <p>매칭 조회에는 N+1 문제 방지를 위해 JPQL JOIN FETCH 쿼리를 사용합니다.
 * 단순 findById 는 LAZY 로딩으로 인해 requester/requestee 를 각각
 * 별도 쿼리로 가져오므로, 화면 렌더링에 필요한 경우 반드시 fetch join 메서드를 사용하세요.</p>
 */
public interface MatchingRepository extends JpaRepository<Matching, Long> {

    /**
     * 특정 회원이 관여된 모든 매칭을 최신 순으로 조회합니다.
     * 요청자(requester) 또는 수신자(requestee) 로 참여한 모든 매칭을 포함합니다.
     *
     * <p>N+1 방지: 쿼리 한 번에 requester, requestee 를 함께 로딩하지 않으므로,
     * 목록 화면에서 닉네임 등을 보여줄 때는 응답 DTO 변환 시 LAZY 로딩이 발생합니다.
     * 성능이 중요한 경우 fetch join 을 추가하거나 DTO 프로젝션을 사용하세요.</p>
     *
     * @param member 조회 대상 회원 (requester 또는 requestee)
     * @return 관련 매칭 목록 (createdAt 내림차순)
     */
    @Query("SELECT m FROM Matching m WHERE m.requester = :member OR m.requestee = :member ORDER BY m.createdAt DESC")
    List<Matching> findAllByMember(@Param("member") Member member);

    /**
     * 두 회원 사이에 현재 진행 중인(REQUESTED 또는 ACCEPTED) 매칭이 있는지 확인합니다.
     * 동일 두 회원 간 중복 교환 요청을 방지하기 위해 사용합니다.
     *
     * <p>방향성 무관: A→B 이든 B→A 이든 활성 매칭이 있으면 true 를 반환합니다.</p>
     *
     * <p>JPQL text block(Java 15+) 사용으로 가독성 향상.</p>
     *
     * @param a 첫 번째 회원
     * @param b 두 번째 회원
     * @return 진행 중인 매칭이 있으면 true
     */
    @Query("""
            SELECT COUNT(m) > 0 FROM Matching m
            WHERE (m.requester = :a AND m.requestee = :b OR m.requester = :b AND m.requestee = :a)
            AND m.status IN (com.realmap.entity.MatchingStatus.REQUESTED, com.realmap.entity.MatchingStatus.ACCEPTED)
            """)
    boolean existsActiveMatchingBetween(@Param("a") Member a, @Param("b") Member b);

    /**
     * 매칭 ID 로 매칭을 조회하면서 requester 와 requestee 를 함께 로딩합니다 (N+1 방지).
     *
     * <p>단건 상세 조회, 상태 변경, Sentiment 제출 시 이 메서드를 사용합니다.
     * JOIN FETCH 로 requester 와 requestee 를 동일 쿼리에서 가져옵니다.</p>
     *
     * @param id 매칭 PK
     * @return 매칭 (requester, requestee 로드 완료)
     */
    @Query("SELECT m FROM Matching m JOIN FETCH m.requester JOIN FETCH m.requestee WHERE m.id = :id")
    Optional<Matching> findByIdWithMembers(@Param("id") Long id);
}
