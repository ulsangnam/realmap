package com.realmap.controller;

import com.realmap.service.PinService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 이벤트 핀 REST 컨트롤러.
 *
 * <h3>엔드포인트 요약</h3>
 * <pre>
 *   GET    /api/pins        — 활성 핀 전체 조회 (인증 불필요 — SecurityConfig 기본값: authenticated)
 *   POST   /api/pins        — 핀 생성 (JWT 인증 필요)
 *   DELETE /api/pins/{id}   — 본인 핀 삭제 (JWT 인증 필요)
 * </pre>
 *
 * <h3>요청 Body 예시 (POST /api/pins)</h3>
 * <pre>
 * {
 *   "lat": 37.5665,
 *   "lng": 126.9780,
 *   "content": "여기서 기타 연주 중! 같이 들으실 분",
 *   "durationMinutes": 60
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/pins")
@RequiredArgsConstructor
public class PinController {

    private final PinService pinService;

    // ── 조회 ─────────────────────────────────────────────────────

    /**
     * 만료되지 않은 활성 핀 전체를 반환합니다.
     *
     * @return 200 OK + 활성 핀 목록 (빈 리스트 가능)
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getActivePins() {
        return ResponseEntity.ok(pinService.getActivePins());
    }

    // ── 생성 ─────────────────────────────────────────────────────

    /**
     * 새 이벤트 핀을 생성합니다.
     *
     * <p>요청 Body에서 lat, lng, content, durationMinutes를 읽어
     * PinService로 위임합니다. 체인 앵커링은 비동기로 처리됩니다.</p>
     *
     * @param body           요청 Body (lat, lng, content, durationMinutes)
     * @param authentication Spring Security 인증 객체 (JWT에서 추출됨)
     * @return 200 OK + 생성된 핀 데이터
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPin(
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        // Number 타입으로 캐스팅 후 변환 — JSON 파싱 시 Integer/Double 혼재 방지
        double lat = ((Number) body.get("lat")).doubleValue();
        double lng = ((Number) body.get("lng")).doubleValue();
        String content = (String) body.get("content");
        int durationMinutes = ((Number) body.get("durationMinutes")).intValue();
        String organizerName = (String) body.getOrDefault("organizerName", null);
        String organizerUrl  = (String) body.getOrDefault("organizerUrl", null);
        String imageUrl      = (String) body.getOrDefault("imageUrl", null);
        LocalDateTime eventStartTime = parseDateTime(body.get("eventStartTime"));
        LocalDateTime eventEndTime   = parseDateTime(body.get("eventEndTime"));

        return ResponseEntity.ok(
                pinService.createPin(authentication, lat, lng, content, durationMinutes,
                        organizerName, organizerUrl, eventStartTime, eventEndTime, imageUrl));
    }

    // ── 수정 ─────────────────────────────────────────────────────

    /** 핀 소유자가 내용·주최기관·유지시간을 수정합니다. */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updatePin(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        String content = (String) body.getOrDefault("content", null);
        String organizerName = (String) body.getOrDefault("organizerName", null);
        String organizerUrl  = (String) body.getOrDefault("organizerUrl", null);
        String imageUrl      = (String) body.getOrDefault("imageUrl", null);
        Integer durationMinutes = body.get("durationMinutes") != null
                ? ((Number) body.get("durationMinutes")).intValue() : null;
        LocalDateTime eventStartTime = parseDateTime(body.get("eventStartTime"));
        LocalDateTime eventEndTime   = parseDateTime(body.get("eventEndTime"));
        return ResponseEntity.ok(
                pinService.updatePin(authentication, id, content, organizerName, organizerUrl,
                        durationMinutes, eventStartTime, eventEndTime, imageUrl));
    }

    // ── 유틸 ─────────────────────────────────────────────────────

    /** "2026-05-11T15:00" 형식 문자열 → LocalDateTime, null이면 null 반환 */
    private LocalDateTime parseDateTime(Object val) {
        if (val == null || val.toString().isBlank()) return null;
        return LocalDateTime.parse(val.toString());
    }

    // ── 삭제 ─────────────────────────────────────────────────────

    /**
     * 본인이 등록한 핀을 삭제합니다.
     *
     * <p>다른 사람의 핀 삭제 시도 시 PinService에서 SecurityException이 발생하며
     * Spring의 기본 예외 처리에 의해 403으로 응답됩니다.</p>
     *
     * @param id             삭제할 핀의 DB PK
     * @param authentication Spring Security 인증 객체
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePin(
            @PathVariable Long id,
            Authentication authentication) {
        pinService.deletePin(authentication, id);
        return ResponseEntity.noContent().build();
    }
}
