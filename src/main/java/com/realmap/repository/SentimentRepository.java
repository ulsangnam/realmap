package com.realmap.repository;

import com.realmap.entity.Matching;
import com.realmap.entity.Member;
import com.realmap.entity.Sentiment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 상호 평가(Sentiment) 데이터 접근 레포지토리.
 *
 * <p>Sentiment 는 불변 엔티티이므로 저장(save) 이후에는 오직 조회 쿼리만 사용합니다.</p>
 *
 * <p>중복 제출 방지 로직:
 * {@link #existsByMatchingAndReviewer} 로 동일 매칭에 동일 리뷰어가 이미 평가했는지 확인한 후
 * {@link SentimentService#submitSentiment} 에서 저장을 허용합니다.</p>
 */
public interface SentimentRepository extends JpaRepository<Sentiment, Long> {

    /**
     * 특정 매칭에 대해 특정 reviewer 가 이미 평가를 제출했는지 확인합니다.
     * 상호 평가 중복 제출을 방지하는 핵심 쿼리입니다.
     *
     * <p>각 매칭에서 requester 와 requestee 는 각각 한 번씩만 평가를 제출할 수 있습니다.
     * 이 메서드로 중복 여부를 확인한 후 서비스 레이어에서 예외를 발생시킵니다.</p>
     *
     * @param matching 대상 매칭
     * @param reviewer 평가 제출 시도자
     * @return 이미 제출한 경우 true
     */
    boolean existsByMatchingAndReviewer(Matching matching, Member reviewer);

    /**
     * 특정 회원이 받은 모든 평가를 조회합니다. 리뷰어 정보를 함께 로딩합니다.
     *
     * <p>JOIN FETCH 로 reviewer 를 미리 로딩합니다.
     * 평가 목록 화면에서 "누가 평가했는지"를 표시할 때 N+1 문제가 발생하지 않도록 합니다.</p>
     *
     * @param member 평가 대상 회원 (reviewee)
     * @return 최신 순으로 정렬된 수신 평가 목록
     */
    @Query("SELECT s FROM Sentiment s JOIN FETCH s.reviewer WHERE s.reviewee = :member ORDER BY s.createdAt DESC")
    List<Sentiment> findAllByRevieweeWithReviewer(@Param("member") Member member);

    /**
     * 특정 매칭에 제출된 모든 평가를 조회합니다.
     * 매칭 완료 후 양측 평가 현황(0/1/2) 확인에 사용합니다.
     *
     * @param matching 대상 매칭
     * @return 해당 매칭의 평가 목록 (최대 2개)
     */
    List<Sentiment> findAllByMatching(Matching matching);
}
