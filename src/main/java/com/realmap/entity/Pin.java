package com.realmap.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 이벤트 핀 엔티티.
 *
 * <p>사용자가 지도 위에 등록하는 이벤트 핀을 나타냅니다.
 * 핀에는 만료 시간이 있으며, 만료 후에는 조회에서 제외됩니다.
 * 핀 데이터(memberId, lat, lng, content)의 SHA-256 무결성 해시를
 * OmniOne Chain에 앵커링합니다.</p>
 *
 * <h3>생명 주기</h3>
 * <pre>
 *   1. Pin.create() → PinService.createPin() → PinRepository.save()
 *   2. OmniOne Chain 앵커링 완료 → chainTxHash 저장
 *   3. expiresAt 이후 → findActivePins()에서 제외 (자동 만료)
 * </pre>
 */
@Entity
@Table(name = "pin")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Pin {

    /** DB 기본 키 (자동 증분) */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 핀을 등록한 회원.
     * LAZY 로딩 — findActivePins() 쿼리에서 JOIN FETCH로 함께 로드합니다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    /** 핀 위치 위도 (WGS84) */
    @Column(nullable = false)
    private Double lat;

    /** 핀 위치 경도 (WGS84) */
    @Column(nullable = false)
    private Double lng;

    /** 이벤트 내용 (최대 500자) */
    @Column(nullable = false, length = 500)
    private String content;

    /** 핀 만료 시각 — 이 시각 이후 활성 핀 조회에서 제외됨 */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** 핀 생성 시각 — INSERT 직전 @PrePersist에서 자동 설정 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * SHA-256(memberId|lat|lng|content) 무결성 해시 (소문자 HEX 64자).
     * OmniOne Chain 앵커링에 사용됩니다.
     */
    @Column(length = 64)
    private String integrityHash;

    /**
     * OmniOne Chain 트랜잭션 해시 (0x...).
     * 앵커링 완료 후 비동기로 업데이트됩니다. 앵커링 전/실패 시 null.
     */
    @Column(length = 100)
    private String chainTxHash;

    /** 주최기관 이름 (선택). 예: "서울시 강남구청", "ABC 스타트업" */
    @Column(length = 200)
    private String organizerName;

    /** 주최기관 URL (선택). 예: "https://event.org" */
    @Column(length = 500)
    private String organizerUrl;

    /** 이벤트 시작 시각 (선택, null이면 미정) */
    @Column
    private LocalDateTime eventStartTime;

    /** 이벤트 종료 시각 (선택, null이면 미확인) */
    @Column
    private LocalDateTime eventEndTime;

    /** 핀 대표 이미지 URL (선택). /uploads/uuid.ext 형식 */
    @Column(length = 500)
    private String imageUrl;

    // ── 생명주기 콜백 ──────────────────────────────────────────

    /**
     * JPA INSERT 직전 호출 — createdAt을 현재 시각으로 설정합니다.
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // ── 팩토리 메서드 ──────────────────────────────────────────

    /**
     * 새 핀 인스턴스를 생성합니다.
     *
     * <p>생성자 직접 사용을 막고 팩토리 메서드를 강제하여
     * 불완전한 상태의 핀이 생성되는 것을 방지합니다.</p>
     *
     * @param member    핀을 등록하는 회원
     * @param lat       위도 (WGS84)
     * @param lng       경도 (WGS84)
     * @param content   이벤트 내용 (500자 이내)
     * @param expiresAt 핀 만료 시각
     * @return 초기화된 Pin 인스턴스 (아직 영속화되지 않은 상태)
     */
    public static Pin create(Member member, double lat, double lng,
                             String content, LocalDateTime expiresAt,
                             String organizerName, String organizerUrl,
                             LocalDateTime eventStartTime, LocalDateTime eventEndTime,
                             String imageUrl) {
        Pin pin = new Pin();
        pin.member = member;
        pin.lat = lat;
        pin.lng = lng;
        pin.content = content;
        pin.expiresAt = expiresAt;
        pin.organizerName = organizerName;
        pin.organizerUrl = organizerUrl;
        pin.eventStartTime = eventStartTime;
        pin.eventEndTime = eventEndTime;
        pin.imageUrl = imageUrl;
        return pin;
    }

    /** 핀 내용/주최기관/시간/만료시간/이미지를 수정합니다. 소유자만 호출 가능. */
    public void update(String content, String organizerName, String organizerUrl,
                       LocalDateTime expiresAt, LocalDateTime eventStartTime,
                       LocalDateTime eventEndTime, String imageUrl) {
        if (content != null && !content.isBlank()) this.content = content;
        this.organizerName = organizerName;
        this.organizerUrl = organizerUrl;
        if (expiresAt != null) this.expiresAt = expiresAt;
        this.eventStartTime = eventStartTime;
        this.eventEndTime = eventEndTime;
        if (imageUrl != null) this.imageUrl = imageUrl;
    }

    // ── 상태 변경 메서드 ───────────────────────────────────────

    /**
     * SHA-256 무결성 해시를 설정합니다.
     * PinService에서 핀 저장 전에 호출됩니다.
     *
     * @param hash 소문자 HEX 64자 문자열
     */
    public void setIntegrityHash(String hash) {
        this.integrityHash = hash;
    }

    /**
     * OmniOne Chain 트랜잭션 해시를 설정합니다.
     * 앵커링 성공 후 비동기 콜백에서 호출됩니다.
     *
     * @param txHash 체인 트랜잭션 해시 (0x...)
     */
    public void setChainTxHash(String txHash) {
        this.chainTxHash = txHash;
    }

    // ── 상태 조회 메서드 ───────────────────────────────────────

    /**
     * 핀이 만료됐는지 확인합니다.
     *
     * @return 현재 시각이 expiresAt을 초과했으면 true
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
