package com.realmap.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 서비스 교환 후 제출하는 상호 평가 엔티티.
 *
 * <p><b>불변 설계 원칙:</b><br>
 * Sentiment 는 한 번 제출되면 절대로 수정할 수 없습니다.
 * 평가 데이터의 무결성을 보장하기 위해 다음 두 가지 메커니즘을 사용합니다:
 * <ol>
 *   <li>{@code integrityHash}: 제출 시점의 내용을 SHA-256 으로 해싱 → {@link #verifyIntegrity()} 로 위변조 감지</li>
 *   <li>{@code chainTxHash}: OmniOne Chain에 해시를 앵커링한 TX 해시 → 블록체인 수준 불변 증명</li>
 * </ol>
 * ※ chainTxHash는 앵커링 완료 후 1회 업데이트되므로 @Immutable 미사용.
 *   실질적 불변성은 SHA-256 integrityHash가 보장합니다.
 * </p>
 *
 * <h3>무결성 해시 구조</h3>
 * <pre>
 *   SHA-256( "matchingId|reviewerId|revieweeId|score|comment|timestamp" )
 * </pre>
 * 이 해시는 블록체인 해커톤 선택과제의 "데이터 무결성 증명" 요건을 충족합니다.
 */
@Entity
@Table(name = "sentiment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sentiment {

    // ─── 식별자 ──────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ─── 연관 엔티티 ──────────────────────────────────────────

    /** 이 평가가 속하는 서비스 교환 매칭 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matching_id", nullable = false)
    private Matching matching;

    /** 평가를 작성한 사람 (서비스 교환 참여자 중 한 명) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private Member reviewer;

    /** 평가를 받는 사람 (상대방 참여자) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewee_id", nullable = false)
    private Member reviewee;

    // ─── 평가 내용 ────────────────────────────────────────────

    /**
     * 평점 (1~5).
     * 1: 매우 불만족, 3: 보통, 5: 매우 만족
     * Bean Validation 은 DTO 에서 처리하지만, 엔티티 레벨에서도 저장 전 검증.
     */
    @Column(nullable = false)
    private Integer score;

    /**
     * 텍스트 코멘트 (최대 500자).
     * 평가 내용을 자세히 기술. 선택 입력.
     */
    @Column(length = 500)
    private String comment;

    // ─── 무결성 해시 ──────────────────────────────────────────

    /**
     * SHA-256 무결성 해시.
     * create() 호출 시 자동 계산됨.
     * 해시 원문: "matchingId|reviewerId|revieweeId|score|comment|createdAt"
     *
     * 용도:
     *   - 평가 데이터가 DB 조작 등으로 변조되었는지 검증 ({@link #verifyIntegrity()})
     *   - 블록체인 해커톤 선택과제: 데이터 불변성 증명
     */
    @Column(nullable = false, length = 64)
    private String integrityHash;

    // ─── OmniOne Chain 앵커링 ─────────────────────────────────

    /**
     * OmniOne Chain 트랜잭션 해시 (선택과제2).
     *
     * SentimentRegistry.anchorSentiment() 호출 후 반환되는 TX 해시 (0x...).
     * null 이면 아직 체인에 앵커링되지 않은 상태.
     * 앵커링 완료 후 {@link #anchorToChain(String)} 으로 1회 업데이트됨.
     *
     * 블록체인 상에서 이 해시로 integrityHash 가 변조 없이 기록되었음을 증명 가능.
     */
    @Column(length = 66)  // "0x" + 64자 HEX = 66자
    private String chainTxHash;

    // ─── 시간 정보 ────────────────────────────────────────────

    /** 평가 제출 일시. 한 번 저장되면 변경 불가 (updatable = false) */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ─── JPA 라이프사이클 콜백 ────────────────────────────────

    /** 최초 저장 시 createdAt 초기화 */
    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /** 수정 저장 시 updatedAt 갱신 (chainTxHash 앵커링 업데이트 허용) */
    @PreUpdate
    protected void onUpdate() {
        // chainTxHash 업데이트(앵커링)만 허용 — 다른 필드는 서비스 레이어에서 보호
    }

    // ─── 정적 팩토리 메서드 ───────────────────────────────────

    /**
     * 새로운 평가를 생성하고 무결성 해시를 계산합니다.
     *
     * <p>해시 계산에 사용되는 원문 형식:
     * {@code "matchingId|reviewerId|revieweeId|score|comment|timestamp"}
     * </p>
     *
     * @param matching  평가가 속하는 매칭 (COMPLETED 상태여야 함)
     * @param reviewer  평가 작성자
     * @param reviewee  평가 대상자
     * @param score     평점 1~5
     * @param comment   코멘트 (null 가능, null 이면 "" 로 처리)
     * @return 무결성 해시가 계산된 불변 Sentiment 인스턴스
     * @throws RuntimeException SHA-256 알고리즘을 사용할 수 없는 경우 (JVM 환경 문제)
     */
    public static Sentiment create(Matching matching, Member reviewer, Member reviewee,
                                   int score, String comment) {
        Sentiment sentiment = new Sentiment();
        sentiment.matching = matching;
        sentiment.reviewer = reviewer;
        sentiment.reviewee = reviewee;
        sentiment.score = score;
        // null 코멘트는 빈 문자열로 정규화 (해시 계산 일관성 유지)
        sentiment.comment = (comment != null) ? comment : "";
        // 저장 전 시각을 확정하여 해시에 포함 (PrePersist 와 일치)
        sentiment.createdAt = LocalDateTime.now();
        // SHA-256 무결성 해시 계산
        sentiment.integrityHash = computeHash(
                matching.getId(),
                reviewer.getId(),
                reviewee.getId(),
                score,
                sentiment.comment,
                sentiment.createdAt
        );
        return sentiment;
    }

    // ─── 도메인 메서드 ────────────────────────────────────────

    /**
     * OmniOne Chain 앵커링 완료 후 TX 해시를 기록합니다.
     * 이 메서드는 OmniOneChainService 에서만 호출되어야 합니다.
     * chainTxHash 가 이미 설정된 경우 재설정을 방지합니다 (1회성).
     *
     * @param txHash OmniOne Chain 트랜잭션 해시 (0x...)
     */
    public void anchorToChain(String txHash) {
        if (this.chainTxHash != null) {
            throw new IllegalStateException(
                    "이미 체인에 앵커링된 평가입니다. sentimentId=" + this.id);
        }
        this.chainTxHash = txHash;
    }

    /** 온체인 앵커링 완료 여부 */
    public boolean isAnchored() {
        return this.chainTxHash != null && !this.chainTxHash.isBlank();
    }

    /**
     * 저장된 무결성 해시를 재계산하여 데이터 변조 여부를 검증합니다.
     *
     * <p>동일한 입력값으로 SHA-256 을 다시 계산하고, 저장된 해시와 비교합니다.
     * 두 값이 다르면 DB 수준에서 데이터가 변조되었음을 의미합니다.</p>
     *
     * @return 무결성 검증 통과(데이터 변조 없음) 시 true, 변조 감지 시 false
     */
    public boolean verifyIntegrity() {
        // 현재 필드값으로 해시를 재계산
        String recomputedHash = computeHash(
                matching.getId(),
                reviewer.getId(),
                reviewee.getId(),
                score,
                comment != null ? comment : "",
                createdAt
        );
        // 저장된 해시와 비교: 동일하면 무결성 유지
        return integrityHash.equals(recomputedHash);
    }

    // ─── 내부 해시 계산 유틸리티 ──────────────────────────────

    /**
     * SHA-256 해시를 계산하는 내부 메서드.
     * 해시 원문 형식: "matchingId|reviewerId|revieweeId|score|comment|timestamp"
     *
     * @param matchingId  매칭 ID
     * @param reviewerId  평가 작성자 ID
     * @param revieweeId  평가 대상자 ID
     * @param score       평점
     * @param comment     코멘트 텍스트
     * @param timestamp   평가 제출 일시
     * @return 16진수 소문자로 표현된 SHA-256 해시 문자열 (64자)
     */
    private static String computeHash(Long matchingId, Long reviewerId, Long revieweeId,
                                       int score, String comment, LocalDateTime timestamp) {
        // 해시 원문 조립: 각 필드를 '|' 로 구분
        String raw = matchingId + "|"
                + reviewerId + "|"
                + revieweeId + "|"
                + score + "|"
                + comment + "|"
                + timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        try {
            // SHA-256 MessageDigest 인스턴스 획득
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // UTF-8 인코딩으로 바이트 배열 변환 후 해시 계산
            byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            // 바이트 배열을 16진수 문자열로 변환
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                // 각 바이트를 2자리 16진수(소문자)로 변환, 앞 자리 0 패딩
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 Java SE 표준 알고리즘으로 반드시 지원됨 — 이 예외는 실제로 발생하지 않음
            throw new RuntimeException("SHA-256 알고리즘을 사용할 수 없습니다. JVM 환경을 확인하세요.", e);
        }
    }
}
