package com.realmap.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 서비스 교환 매칭 엔티티.
 *
 * <p>realMap 의 핵심 도메인 객체입니다. 두 회원이 상호 무페이 서비스 교환을 합의하고
 * 진행하는 전체 과정을 하나의 Matching 으로 표현합니다.</p>
 *
 * <h3>상호 무페이 서비스 교환 라이프사이클</h3>
 * <pre>
 *   1. requester 가 requestee 에게 교환 제안 → REQUESTED
 *      (serviceOfferedByRequester: "나는 이걸 해드릴게요")
 *
 *   2. requestee 가 수락 → ACCEPTED
 *      (serviceOfferedByRequestee: "그럼 저는 이걸 해드릴게요")
 *
 *   3. 실제 서비스 교환 수행 후 완료 처리 → COMPLETED
 *
 *   4. COMPLETED 상태에서 양측 각 1회씩 Sentiment 제출
 *      → 플랫폼 평판 데이터 축적
 * </pre>
 *
 * <h3>상태 전이 규칙</h3>
 * <ul>
 *   <li>REQUESTED → ACCEPTED: requestee 만 수락 가능</li>
 *   <li>ACCEPTED → COMPLETED: requester 또는 requestee 둘 다 완료 처리 가능</li>
 *   <li>REQUESTED|ACCEPTED → CANCELLED: 양측 누구나 취소 가능</li>
 *   <li>COMPLETED → 변경 불가 (불변 상태)</li>
 * </ul>
 */
@Entity
@Table(name = "matching")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Matching {

    // ─── 식별자 ──────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── 참여자 ───────────────────────────────────────────────

    /**
     * 교환을 제안한 사람 (요청자).
     * LAZY 로딩으로 N+1 방지. fetch join 쿼리는 MatchingRepository 에 정의.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = false)
    private Member requester;

    /**
     * 교환 제안을 받은 사람 (수신자).
     * LAZY 로딩으로 N+1 방지.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requestee_id", nullable = false)
    private Member requestee;

    // ─── 교환 서비스 설명 ─────────────────────────────────────

    /**
     * requester 가 제공하겠다고 제안한 서비스 내용.
     * 교환 요청(REQUESTED) 단계에서 입력됨.
     * 예: "React 컴포넌트 설계 및 개발 도움 (3시간)"
     */
    @Column(nullable = false, length = 1000)
    private String serviceOfferedByRequester;

    /**
     * requestee 가 제공하겠다고 확정한 서비스 내용.
     * 교환 수락(ACCEPTED) 단계에서 입력됨.
     * REQUESTED 상태에서는 null 일 수 있음.
     * 예: "UI/UX 디자인 피드백 및 Figma 시안 제작 (2회)"
     */
    @Column(length = 1000)
    private String serviceOfferedByRequestee;

    // ─── 상태 ─────────────────────────────────────────────────

    /**
     * 현재 매칭 상태.
     * STRING 으로 저장하여 DB 에서 가독성 보장.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchingStatus status;

    // ─── 일정 정보 ────────────────────────────────────────────

    /** 교환 예정 일시 (requester 가 요청 시 선택적으로 설정) */
    @Column
    private LocalDateTime scheduledAt;

    /** 실제 교환 완료 일시 (COMPLETED 전이 시 자동 설정) */
    @Column
    private LocalDateTime completedAt;

    // ─── 연관 평가 목록 ───────────────────────────────────────

    /**
     * 이 매칭에 제출된 Sentiment(평가) 목록.
     * 최대 2개 (requester 의 평가 1개 + requestee 의 평가 1개).
     * COMPLETED 상태에서만 제출 가능.
     * CascadeType.ALL 로 매칭 삭제 시 평가도 함께 삭제 (데모용; 운영에서는 신중히 설정).
     */
    @OneToMany(mappedBy = "matching", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Sentiment> sentiments = new ArrayList<>();

    // ─── 시간 정보 ────────────────────────────────────────────

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // ─── JPA 라이프사이클 콜백 ────────────────────────────────

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ─── 정적 팩토리 메서드 ───────────────────────────────────

    /**
     * 새로운 서비스 교환 요청을 생성합니다.
     * 초기 상태는 항상 REQUESTED 입니다.
     *
     * @param requester                 교환을 제안하는 회원
     * @param requestee                 교환 제안을 받는 회원
     * @param serviceOfferedByRequester requester 가 제공할 서비스 설명
     * @param scheduledAt               예정 일시 (null 가능)
     * @return REQUESTED 상태의 새 Matching 인스턴스
     */
    public static Matching createRequest(Member requester, Member requestee,
                                         String serviceOfferedByRequester,
                                         LocalDateTime scheduledAt) {
        Matching matching = new Matching();
        matching.requester = requester;
        matching.requestee = requestee;
        matching.serviceOfferedByRequester = serviceOfferedByRequester;
        matching.scheduledAt = scheduledAt;
        // 최초 생성 시 상태는 반드시 REQUESTED
        matching.status = MatchingStatus.REQUESTED;
        return matching;
    }

    // ─── 상태 머신 메서드 ─────────────────────────────────────

    /**
     * 서비스 교환 제안을 수락합니다 (REQUESTED → ACCEPTED).
     * requestee 가 본인이 제공할 서비스를 명시하며 수락합니다.
     *
     * @param serviceOfferedByRequestee requestee 가 제공할 서비스 설명
     * @throws IllegalStateException REQUESTED 상태가 아닐 때
     */
    public void accept(String serviceOfferedByRequestee) {
        // 상태 검증: REQUESTED 상태에서만 수락 가능
        if (this.status != MatchingStatus.REQUESTED) {
            throw new IllegalStateException(
                    "REQUESTED 상태의 매칭만 수락할 수 있습니다. 현재 상태: " + this.status);
        }
        this.serviceOfferedByRequestee = serviceOfferedByRequestee;
        this.status = MatchingStatus.ACCEPTED;
    }

    /**
     * 서비스 교환이 실제로 이루어졌음을 완료 처리합니다 (ACCEPTED → COMPLETED).
     * 완료 후에는 양측이 Sentiment 를 제출할 수 있습니다.
     *
     * @throws IllegalStateException ACCEPTED 상태가 아닐 때
     */
    public void complete() {
        // 상태 검증: 수락된 매칭만 완료 처리 가능
        if (this.status != MatchingStatus.ACCEPTED) {
            throw new IllegalStateException(
                    "ACCEPTED 상태의 매칭만 완료 처리할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = MatchingStatus.COMPLETED;
        // 완료 일시를 현재 시각으로 기록
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 매칭을 취소합니다 (REQUESTED|ACCEPTED → CANCELLED).
     * 이미 완료된 교환은 취소할 수 없습니다.
     *
     * @throws IllegalStateException 이미 COMPLETED 또는 CANCELLED 상태일 때
     */
    public void cancel() {
        // 완료되거나 이미 취소된 매칭은 취소 불가
        if (this.status == MatchingStatus.COMPLETED) {
            throw new IllegalStateException("완료된 교환은 취소할 수 없습니다.");
        }
        if (this.status == MatchingStatus.CANCELLED) {
            throw new IllegalStateException("이미 취소된 매칭입니다.");
        }
        this.status = MatchingStatus.CANCELLED;
    }

    // ─── 비즈니스 규칙 메서드 ─────────────────────────────────

    /**
     * 특정 회원이 이 매칭에 대해 Sentiment(평가)를 제출할 수 있는지 확인합니다.
     *
     * <p>제출 가능 조건:
     * <ol>
     *   <li>매칭 상태가 COMPLETED 여야 함 (완료된 교환만 평가 가능)</li>
     *   <li>reviewer 가 requester 또는 requestee 중 하나여야 함</li>
     *   <li>이미 평가를 제출한 경우 중복 제출 불가 (호출측에서 SentimentRepository 로 확인)</li>
     * </ol>
     * </p>
     *
     * @param reviewer 평가를 제출하려는 회원
     * @return 제출 가능하면 true
     */
    public boolean canSubmitSentiment(Member reviewer) {
        // 조건 1: 반드시 COMPLETED 상태여야 함
        if (this.status != MatchingStatus.COMPLETED) {
            return false;
        }
        // 조건 2: 해당 매칭의 참여자(requester 또는 requestee)여야 함
        return reviewer.getId().equals(this.requester.getId())
                || reviewer.getId().equals(this.requestee.getId());
    }
}
