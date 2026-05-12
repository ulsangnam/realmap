package com.realmap.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/images")
public class ImageController {

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(Paths.get(uploadDir));
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        String orig = file.getOriginalFilename();
        String ext = (orig != null && orig.contains("."))
                ? orig.substring(orig.lastIndexOf('.')) : ".jpg";
        // 허용 확장자만 저장
        if (!ext.matches("\\.(jpg|jpeg|png|gif|webp)")) {
            return ResponseEntity.badRequest().body(Map.of("error", "이미지 파일만 업로드 가능합니다."));
        }
        String filename = UUID.randomUUID() + ext;
        try {
            Path dest = Paths.get(uploadDir).resolve(filename);
            Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
            String url = "/uploads/" + filename;
            log.info("[Image] 업로드 완료: {}", url);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (IOException e) {
            log.error("[Image] 업로드 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "업로드 실패"));
        }
    }
}
