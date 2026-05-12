package com.realmap.repository;

import com.realmap.entity.Member;
import com.realmap.entity.MemberRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 회원 데이터 접근 레포지토리.
 *
 * <p>DID 기반 회원 조회 및 지역/역할 기반 서비스 교환 상대방 탐색 기능을 제공합니다.</p>
 *
 * <p>Spring Data JPA 가 런타임에 인터페이스 구현체를 자동으로 생성합니다.
 * 메서드 이름으로 쿼리를 자동 생성합니다 (Query Method Naming Convention).</p>
 */
public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * DID 로 회원을 조회합니다.
     * OmniOne CX 인증 완료 후 기존 회원 여부 확인에 사용합니다.
     *
     * @param did OmniOne CX trans API 에서 반환된 DID 식별자
     * @return 회원이 존재하면 Optional<Member>, 없으면 Optional.empty()
     */
    Optional<Member> findByDid(String did);

    /**
     * DID 에 해당하는 회원이 이미 가입되어 있는지 확인합니다.
     * findByDid 보다 경량 쿼리 (EXISTS 사용).
     *
     * @param did DID 식별자
     * @return 존재하면 true
     */
    boolean existsByDid(String did);

    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * 지역과 역할 목록으로 서비스 교환 상대방을 검색합니다.
     *
     * <p>사용 예시:
     * 사용자가 "서울" 지역에서 PROVIDER 또는 BOTH 역할인 회원을 찾으려면:
     * {@code findByRegionContainingAndRoleIn("서울", List.of(MemberRole.PROVIDER, MemberRole.BOTH))}
     * </p>
     *
     * <p>CONTAINING 을 사용하므로 "서울특별시 강남구" 를 입력해도 "서울" 로 검색 가능합니다.</p>
     *
     * @param region 검색할 지역 키워드 (부분 일치)
     * @param roles  포함할 역할 목록 (예: [PROVIDER, BOTH])
     * @return 조건에 맞는 회원 목록
     */
    List<Member> findByRegionContainingAndRoleIn(String region, List<MemberRole> roles);
}
