package com.realmap.controller;

import com.realmap.entity.Member;
import com.realmap.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberRepository memberRepository;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllMembers() {
        return ResponseEntity.ok(memberRepository.findAll().stream().map(this::toMap).toList());
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getMe(Authentication authentication) {
        Member member = getMember(authentication);
        return ResponseEntity.ok(toMap(member));
    }

    @PutMapping("/location")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateLocation(
            @RequestBody Map<String, Double> body,
            Authentication authentication) {
        Member member = getMember(authentication);
        member.updateLocation(body.get("lat"), body.get("lng"));
        memberRepository.save(member);
        return ResponseEntity.ok(Map.of("lat", member.getLatitude(), "lng", member.getLongitude()));
    }

    private Member getMember(Authentication authentication) {
        return memberRepository.findByDid(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("회원을 찾을 수 없습니다."));
    }

    private Map<String, Object> toMap(Member m) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", m.getId());
        map.put("nickname", m.getNickname());
        map.put("region", m.getRegion() != null ? m.getRegion() : "");
        map.put("role", m.getRole().name());
        map.put("serviceDescription", m.getServiceDescription() != null ? m.getServiceDescription() : "");
        map.put("averageSentimentScore", m.getAverageSentimentScore());
        map.put("sentimentCount", m.getSentimentCount());
        map.put("lat", m.getLatitude());
        map.put("lng", m.getLongitude());
        return map;
    }
}
