package com.realmap.service;

import com.realmap.entity.Member;
import com.realmap.entity.Pin;
import com.realmap.repository.MemberRepository;
import com.realmap.repository.PinRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 이벤트 핀 비즈니스 로직 서비스.
 *
 * <h3>주요 기능</h3>
 * <ul>
 *   <li>활성 핀 조회 (만료 핀 자동 제외)</li>
 *   <li>핀 생성 + SHA-256 무결성 해시 + OmniOne Chain 비동기 앵커링</li>
 *   <li>본인 핀 삭제</li>
 * </ul>
 *
 * <h3>앵커링 흐름</h3>
 * <pre>
 *   1. 핀 저장 (DB 트랜잭션 커밋)
 *   2. anchorPinAsync() 비동기 호출 → 트랜잭션과 독립적으로 실행
 *   3. 앵커링 성공 시 thenAccept()에서 chainTxHash 업데이트
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PinService {

    private final PinRepository pinRepository;
    private final MemberRepository memberRepository;
    private final OmniOneChainService chainService;

    // ── 조회 ─────────────────────────────────────────────────────

    /**
     * 만료되지 않은 활성 핀 전체를 조회합니다.
     *
     * <p>readOnly 트랜잭션으로 불필요한 dirty checking을 방지합니다.</p>
     *
     * @return 활성 핀 데이터 맵 리스트 (클라이언트 응답 형식)
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getActivePins() {
        return pinRepository.findActivePins(LocalDateTime.now())
                .stream()
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    // ── 생성 ─────────────────────────────────────────────────────

    /**
     * 새 이벤트 핀을 생성하고 OmniOne Chain에 비동기 앵커링합니다.
     *
     * <p>SHA-256 해시는 memberId|lat|lng|content의 조합으로 계산합니다.
     * createdAt은 @PrePersist에서 설정되므로 해시 계산에서 제외합니다.</p>
     *
     * @param auth            Spring Security 인증 객체 (DID가 name으로 설정됨)
     * @param lat             핀 위도 (WGS84)
     * @param lng             핀 경도 (WGS84)
     * @param content         이벤트 내용 (500자 이내)
     * @param durationMinutes 핀 유지 시간 (분 단위, 예: 60 → 1시간 후 만료)
     * @return 생성된 핀 데이터 맵
     * @throws IllegalStateException 인증된 사용자가 DB에 없을 경우
     */
    @Transactional
    public Map<String, Object> createPin(Authentication auth, double lat, double lng,
                                         String content, int durationMinutes,
                                         String organizerName, String organizerUrl,
                                         LocalDateTime eventStartTime, LocalDateTime eventEndTime,
                                         String imageUrl) {
        Member member = memberRepository.findByDid(auth.getName())
                .orElseThrow(() -> new IllegalStateException("회원을 찾을 수 없습니다."));

        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(durationMinutes);
        Pin pin = Pin.create(member, lat, lng, content, expiresAt, organizerName, organizerUrl,
                eventStartTime, eventEndTime, imageUrl);

        // SHA-256(memberId|lat|lng|content|organizerName) — 주최기관 포함
        String rawData = member.getId() + "|" + lat + "|" + lng + "|" + content
                + "|" + (organizerName != null ? organizerName : "");
        String integrityHash = sha256(rawData);
        pin.setIntegrityHash(integrityHash);

        // --- DB 저장 ---
        Pin saved = pinRepository.save(pin);
        log.info("[Pin] 핀 생성 완료. memberId={}, pinId={}, expiresAt={}",
                member.getId(), saved.getId(), expiresAt);

        // --- OmniOne Chain 비동기 앵커링 ---
        // DB 저장과 독립적으로 실행 — 앵커링 실패 시에도 핀은 정상 등록됨
        chainService.anchorPinAsync(saved.getId(), integrityHash)
                .thenAccept(txHash -> {
                    if (txHash != null) {
                        // 앵커링 성공 시 새 트랜잭션에서 chainTxHash 업데이트
                        // findById → save 패턴: 영속성 컨텍스트가 이미 닫혔으므로 명시적 save 필요
                        pinRepository.findById(saved.getId()).ifPresent(p -> {
                            p.setChainTxHash(txHash);
                            pinRepository.save(p);
                        });
                    }
                });

        return toMap(saved);
    }

    // ── 수정 ─────────────────────────────────────────────────────

    /** 핀 소유자가 내용/주최기관/시간/유지시간/이미지를 수정합니다. */
    @Transactional
    public Map<String, Object> updatePin(Authentication auth, Long pinId,
                                         String content, String organizerName,
                                         String organizerUrl, Integer durationMinutes,
                                         LocalDateTime eventStartTime, LocalDateTime eventEndTime,
                                         String imageUrl) {
        Member member = memberRepository.findByDid(auth.getName())
                .orElseThrow(() -> new IllegalStateException("회원을 찾을 수 없습니다."));
        Pin pin = pinRepository.findById(pinId)
                .orElseThrow(() -> new IllegalArgumentException("핀을 찾을 수 없습니다."));
        if (!pin.getMember().getId().equals(member.getId())) {
            throw new SecurityException("본인 핀만 수정할 수 있습니다.");
        }

        LocalDateTime newExpiry = durationMinutes != null
                ? LocalDateTime.now().plusMinutes(durationMinutes) : null;
        pin.update(content, organizerName, organizerUrl, newExpiry, eventStartTime, eventEndTime, imageUrl);
        return toMap(pinRepository.save(pin));
    }

    // ── 삭제 ─────────────────────────────────────────────────────

    /**
     * 본인이 등록한 핀을 삭제합니다.
     *
     * <p>다른 사람의 핀을 삭제하려 하면 SecurityException을 던집니다.</p>
     *
     * @param auth  Spring Security 인증 객체
     * @param pinId 삭제할 핀의 DB PK
     * @throws IllegalStateException  인증된 사용자가 DB에 없을 경우
     * @throws IllegalArgumentException 존재하지 않는 핀 ID일 경우
     * @throws SecurityException      본인 핀이 아닌 경우
     */
    @Transactional
    public void deletePin(Authentication auth, Long pinId) {
        // --- 본인 확인 ---
        Member member = memberRepository.findByDid(auth.getName())
                .orElseThrow(() -> new IllegalStateException("회원을 찾을 수 없습니다."));

        // --- 핀 존재 확인 ---
        Pin pin = pinRepository.findById(pinId)
                .orElseThrow(() -> new IllegalArgumentException("핀을 찾을 수 없습니다."));

        // --- 소유권 확인 (본인 핀만 삭제 가능) ---
        if (!pin.getMember().getId().equals(member.getId())) {
            throw new SecurityException("본인 핀만 삭제할 수 있습니다.");
        }

        pinRepository.delete(pin);
        log.info("[Pin] 핀 삭제. memberId={}, pinId={}", member.getId(), pinId);
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────

    /**
     * Pin 엔티티를 클라이언트 응답용 Map으로 변환합니다.
     *
     * <p>remainMs: 현재 시각 기준 남은 밀리초. 이미 만료된 핀은 0으로 처리합니다.</p>
     *
     * @param p 변환할 Pin 엔티티
     * @return 클라이언트에 직접 직렬화되는 Map
     */
    private Map<String, Object> toMap(Pin p) {
        // 만료까지 남은 밀리초 계산 — 음수 방지를 위해 max(0, remainMs) 처리
        long remainMs = Math.max(0,
                Duration.between(LocalDateTime.now(), p.getExpiresAt()).toMillis());

        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("memberId", p.getMember().getId());
        m.put("nickname", p.getMember().getNickname());
        m.put("lat", p.getLat());
        m.put("lng", p.getLng());
        m.put("content", p.getContent());
        m.put("expiresAt", p.getExpiresAt().toString());
        m.put("remainMs", remainMs);
        m.put("integrityHash", p.getIntegrityHash());
        m.put("chainTxHash", p.getChainTxHash());
        m.put("organizerName", p.getOrganizerName());
        m.put("organizerUrl", p.getOrganizerUrl());
        m.put("eventStartTime", p.getEventStartTime() != null ? p.getEventStartTime().toString() : null);
        m.put("eventEndTime", p.getEventEndTime() != null ? p.getEventEndTime().toString() : null);
        m.put("imageUrl", p.getImageUrl());
        return m;
    }

    /**
     * 입력 문자열의 SHA-256 해시를 소문자 HEX 64자 문자열로 반환합니다.
     *
     * @param input UTF-8로 인코딩할 원본 문자열
     * @return 소문자 HEX 64자 SHA-256 해시
     * @throws RuntimeException MessageDigest 초기화 실패 시 (사실상 발생하지 않음)
     */
    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // 각 바이트를 2자리 소문자 HEX로 변환하여 이어붙임
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 해시 실패", e);
        }
    }
}
