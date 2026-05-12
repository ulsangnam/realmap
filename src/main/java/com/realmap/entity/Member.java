package com.realmap.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * realMap 플랫폼 회원 엔티티.
 *
 * <p>회원은 OmniOne CX(행안부 모바일 신분증) 인증을 통해 DID(분산신원식별자)를 기반으로 가입됩니다.
 * 별도의 아이디/비밀번호 없이 DID 가 회원의 고유 식별자 역할을 합니다.</p>
 *
 * <p>평판 시스템:
 * 서비스 교환이 완료될 때마다 상대방으로부터 Sentiment(평가)를 받고,
 * {@code totalSentimentScore} 와 {@code sentimentCount} 가 누적됩니다.
 * {@link #getAverageSentimentScore()} 로 현재 평균 평점을 조회합니다.</p>
 */
@Entity
@Table(name = "member")
@Getter
// 기본 생성자를 PROTECTED 로 막아 반드시 정적 팩토리 메서드 create() 를 통해 생성하도록 강제
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    // ─── 식별자 ──────────────────────────────────────────────

    /** 내부 DB PK (자동 증가). 외부에 노출하는 식별자는 did 를 사용할 것 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── DID 정보 (OmniOne CX 에서 수신) ─────────────────────

    /**
     * DID(분산신원식별자).
     * OmniOne CX trans API 응답의 "did" 필드 값.
     * 예: "did:omn:abcdef1234567890"
     * 회원을 유일하게 식별하는 불변 값이므로 UNIQUE 제약.
     */
    @Column(nullable = false, unique = true)
    private String did;

    /**
     * 이메일 (이메일 가입 회원만 사용).
     * DID 전용 회원은 null.
     */
    @Column(unique = true)
    private String email;

    /** BCrypt 해시된 비밀번호 (이메일 가입 회원만 사용) */
    @Column
    private String passwordHash;

    /**
     * 닉네임.
     * 신규 가입 시 사용자가 입력. 플랫폼 내 표시 이름으로 사용.
     */
    @Column(nullable = false)
    private String nickname;

    /**
     * 지역 정보.
     * OmniOne CX trans API 응답의 "address" 필드에서 파싱.
     * 상대방 검색 시 같은 지역 필터링에 활용.
     * 예: "서울특별시 강남구"
     */
    @Column
    private String region;

    // ─── 서비스 교환 정보 ─────────────────────────────────────

    /**
     * 본인이 제공할 수 있는 서비스 설명.
     * 상대방이 이 회원을 탐색할 때 표시되는 핵심 정보.
     * 예: "React + TypeScript 프론트엔드 개발", "영어 ↔ 한국어 번역 (비즈니스 문서 전문)"
     */
    @Column(length = 1000)
    private String serviceDescription;

    /**
     * 서비스 교환 역할.
     * {@link MemberRole} 참조.
     * 검색 필터: PROVIDER 를 찾는 사람은 PROVIDER 또는 BOTH 회원을 탐색.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberRole role;

    // ─── 지도 위치 ───────────────────────────────────────────

    /** 위도 (Naver Maps 마커용, null이면 지도에 미표시) */
    @Column
    private Double latitude;

    /** 경도 */
    @Column
    private Double longitude;

    public void updateLocation(double lat, double lng) {
        this.latitude = lat;
        this.longitude = lng;
    }

    // ─── 평판 점수 ────────────────────────────────────────────

    /**
     * 수신한 Sentiment 점수의 누적 합계.
     * 평균 계산 시: totalSentimentScore / sentimentCount
     * BigDecimal 로 정밀도 유지 (소수점 이하 2자리).
     */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalSentimentScore = BigDecimal.ZERO;

    /**
     * 수신한 Sentiment(평가) 횟수.
     * 평균 점수 계산 분모로 사용.
     */
    @Column(nullable = false)
    private Integer sentimentCount = 0;

    // ─── 시간 정보 ────────────────────────────────────────────

    /** 회원 가입 일시 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 회원 정보 최종 수정 일시 */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // ─── JPA 라이프사이클 콜백 ────────────────────────────────

    /** 최초 저장 시 createdAt 과 updatedAt 을 현재 시각으로 초기화 */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** 수정 저장 시 updatedAt 을 현재 시각으로 갱신 */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ─── 정적 팩토리 메서드 ───────────────────────────────────

    /**
     * 신규 회원을 생성하는 정적 팩토리 메서드.
     * OmniOne CX 인증 완료 후 최초 로그인 시 호출됨.
     *
     * @param did                DID 식별자 (OmniOne CX trans API 응답)
     * @param nickname           사용자가 입력한 닉네임
     * @param region             주소/지역 (OmniOne CX address 클레임)
     * @param serviceDescription 본인이 제공 가능한 서비스 설명
     * @param role               서비스 교환 역할 (PROVIDER / RECEIVER / BOTH)
     * @return 초기 평판 점수가 0 으로 설정된 새 Member 인스턴스
     */
    public static Member create(String did, String nickname, String region,
                                String serviceDescription, MemberRole role) {
        Member member = new Member();
        member.did = did;
        member.nickname = nickname;
        member.region = region;
        member.serviceDescription = serviceDescription;
        member.role = role;
        // 신규 가입 시 평판 점수는 0 으로 초기화
        member.totalSentimentScore = BigDecimal.ZERO;
        member.sentimentCount = 0;
        return member;
    }

    /** 이메일 + 비밀번호로 신규 회원을 생성하는 팩토리 메서드. */
    public static Member createWithEmail(String email, String passwordHash,
                                         String nickname, String serviceDescription,
                                         MemberRole role) {
        Member m = new Member();
        m.email = email;
        m.passwordHash = passwordHash;
        // 이메일 주소를 DID 대신 사용 (플랫폼 내 고유 식별자)
        m.did = "did:email:" + email;
        m.nickname = nickname;
        m.region = "";
        m.serviceDescription = serviceDescription;
        m.role = role;
        m.totalSentimentScore = java.math.BigDecimal.ZERO;
        m.sentimentCount = 0;
        return m;
    }

    // ─── 도메인 메서드 ────────────────────────────────────────

    /**
     * 새로운 Sentiment(평가) 점수를 누적합니다.
     * 서비스 교환이 완료된 후 상대방이 평가를 제출할 때 호출됩니다.
     *
     * @param score 1~5 범위의 정수 점수
     * @throws IllegalArgumentException score 가 1~5 범위 밖인 경우
     */
    public void addSentimentScore(int score) {
        // 점수 유효성 검사 — 악의적인 직접 호출 방지
        if (score < 1 || score > 5) {
            throw new IllegalArgumentException(
                    "평가 점수는 1~5 사이여야 합니다. 입력값: " + score);
        }
        // 누적 합계와 횟수 갱신
        this.totalSentimentScore = this.totalSentimentScore.add(BigDecimal.valueOf(score));
        this.sentimentCount++;
    }

    /**
     * 현재 평균 평판 점수를 반환합니다.
     * 아직 평가를 받은 적 없으면 BigDecimal.ZERO 를 반환합니다.
     *
     * @return 평균 점수 (소수점 2자리, HALF_UP 반올림), 평가 없으면 0.00
     */
    public BigDecimal getAverageSentimentScore() {
        if (sentimentCount == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        // totalSentimentScore / sentimentCount, 소수점 2자리 반올림
        return totalSentimentScore.divide(
                BigDecimal.valueOf(sentimentCount), 2, RoundingMode.HALF_UP);
    }
}
