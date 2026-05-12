package com.realmap.repository;

import com.realmap.entity.Pin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Pin 엔티티 JPA 리포지토리.
 *
 * <p>핀 CRUD 및 만료 기반 조회를 담당합니다.</p>
 */
public interface PinRepository extends JpaRepository<Pin, Long> {

    /**
     * 만료되지 않은 활성 핀 전체를 조회합니다.
     *
     * <p>JOIN FETCH를 사용해 member를 함께 로드함으로써
     * N+1 쿼리 문제를 방지합니다. PinService.toMap()에서
     * member.getNickname() 등을 안전하게 접근할 수 있습니다.</p>
     *
     * @param now 기준 시각 (보통 LocalDateTime.now())
     * @return expiresAt이 now 이후인 핀 목록
     */
    @Query("SELECT p FROM Pin p JOIN FETCH p.member WHERE p.expiresAt > :now")
    List<Pin> findActivePins(@Param("now") LocalDateTime now);

    /**
     * 특정 회원이 등록한 핀 전체를 조회합니다.
     *
     * @param memberId 조회할 회원의 DB PK
     * @return 해당 회원의 핀 목록 (만료 포함)
     */
    List<Pin> findByMemberId(Long memberId);
}
