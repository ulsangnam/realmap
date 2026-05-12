# realMap — 프로젝트 정리 문서

> 2026 블록체인 & AI 해커톤 출품작  
> **"지도 위에서 만나고, 신뢰로 교환하는 플랫폼"**

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [핵심 가치 제안](#2-핵심-가치-제안)
3. [전체 시스템 아키텍처](#3-전체-시스템-아키텍처)
4. [기능 목록](#4-기능-목록)
5. [데이터 모델](#5-데이터-모델)
6. [API 명세](#6-api-명세)
7. [OmniOne 연동 상세](#7-omnione-연동-상세)
8. [블록체인 앵커링 흐름](#8-블록체인-앵커링-흐름)
9. [화면 구성 (UI/UX)](#9-화면-구성-uiux)
10. [기술 스택 상세](#10-기술-스택-상세)
11. [시연 시나리오](#11-시연-시나리오)
12. [해커톤 과제 대응 현황](#12-해커톤-과제-대응-현황)

---

## 1. 프로젝트 개요

### 한 줄 정의
> **"내 주변 실시간 이벤트를 지도에서 발견하고, 신뢰 기반으로 서비스를 교환하는 플랫폼"**

### 배경 & 문제 정의

| 문제 | 설명 |
|------|------|
| 지역 이벤트 정보 단절 | 주변에서 일어나는 소규모 이벤트·공연·행사 정보가 SNS에 파편화 |
| 서비스 교환의 신뢰 부재 | 지인 아닌 사람과의 서비스 교환(재능 나눔, 물물교환 등)은 신뢰 기반이 없어 성사 어려움 |
| 평판 조작 가능성 | 중앙화된 플랫폼의 리뷰·평점은 삭제·조작 가능 |

### 해결 방안

```
지도 위 실시간 핀  →  현장 이벤트 발견
DID 기반 신원    →  익명성 없는 신뢰 있는 교환
OmniOne Chain   →  평판 데이터 블록체인 영구 기록
```

---

## 2. 핵심 가치 제안

### 3가지 핵심 가치

```
┌─────────────────────────────────────────────────────┐
│  1. DISCOVER   지도에서 지금 당장 주변 이벤트 발견       │
│  2. EXCHANGE   신뢰 기반 무페이 서비스 교환              │
│  3. TRUST      블록체인에 기록된 변조 불가 평판           │
└─────────────────────────────────────────────────────┘
```

### 차별점

| 구분 | 기존 플랫폼 | realMap |
|------|-----------|---------|
| 이벤트 탐색 | 앱 내 텍스트 목록 | **지도 위 실시간 핀 마커** |
| 신원 확인 | ID/Password | **DID (행안부 모바일 신분증)** |
| 평판 신뢰성 | 중앙 DB, 조작 가능 | **OmniOne Chain 영구 앵커링** |
| 이미지 표현 | 텍스트 위주 | **이미지가 직접 지도 위 마커로** |

---

## 3. 전체 시스템 아키텍처

```
┌──────────────────────────────────────────────────────────────┐
│                      CLIENT (Mobile Browser)                  │
│                   Vanilla JS SPA / index.html                 │
│          Naver Maps v3  ·  REST API  ·  JWT Auth              │
└───────────────────┬──────────────────────────────────────────┘
                    │ HTTP / JSON
┌───────────────────▼──────────────────────────────────────────┐
│                  Spring Boot 3.2.5 (Java 17)                  │
│                                                               │
│  ┌─────────────┐  ┌────────────┐  ┌─────────────────────┐   │
│  │  Auth Layer  │  │  REST API  │  │  Security Filter     │   │
│  │  JWT + DID   │  │  Controllers│  │  JWT + Spring Sec 6  │   │
│  └─────────────┘  └──────┬─────┘  └─────────────────────┘   │
│                           │                                   │
│  ┌────────────────────────▼────────────────────────────────┐ │
│  │                   Service Layer                          │ │
│  │  PinService · SentimentService · MatchingService         │ │
│  │  OmniOneChainService · DidVerificationService            │ │
│  └────────────────────────┬────────────────────────────────┘ │
│                            │                                  │
│  ┌─────────────────────────▼───────────────────────────────┐ │
│  │              JPA / H2 In-Memory DB (demo)                │ │
│  │  Member · Pin · Matching · Sentiment                     │ │
│  └─────────────────────────────────────────────────────────┘ │
└──────────────┬───────────────────────────────────────────────┘
               │
    ┌──────────┴──────────┐
    │                     │
┌───▼────────────┐  ┌─────▼───────────────┐
│  OmniOne CX    │  │  OmniOne Chain       │
│  (행안부 DID)   │  │  (Ethereum 호환 체인)  │
│  mock-mode: on │  │  enabled: false      │
└────────────────┘  └─────────────────────┘
```

---

## 4. 기능 목록

### 4.1 인증 시스템

| 기능 | 설명 | 구현 상태 |
|------|------|---------|
| 이메일 회원가입/로그인 | BCrypt 해싱 + JWT 발급 | ✅ 완료 |
| OmniOne CX (DID 인증) | 행안부 모바일 신분증 연동 | ✅ 완료 (mock 가능) |
| JWT 자동 갱신 | 401 응답 시 자동 로그아웃 + 안내 | ✅ 완료 |

### 4.2 지도 & 핀 시스템 ⭐ 핵심 기능

| 기능 | 설명 | 구현 상태 |
|------|------|---------|
| 네이버 지도 표시 | 회원 위치 실시간 마커 | ✅ 완료 |
| 이벤트 핀 등록 | 지도 클릭 → 좌표 선택 → 내용 입력 | ✅ 완료 |
| 핀 자동 만료 | 설정 시간(30분~24시간) 후 자동 삭제 | ✅ 완료 |
| 이미지 핀 마커 | 이미지 업로드 시 지도에 이미지 그대로 표시 | ✅ 완료 |
| 주최기관 정보 | 기관명 + URL 첨부, 인포윈도우에 링크 표시 | ✅ 완료 |
| 이벤트 시작/종료 시간 | 종료시간 "미확인" 옵션 지원 | ✅ 완료 |
| 핀 수정/삭제 | 소유자만 가능 | ✅ 완료 |
| 실시간 교통 정보 | 네이버 지도 교통 레이어 토글 | ✅ 완료 |
| 주소 검색 | Geocoder API로 장소명 검색 이동 | ✅ 완료 |
| 30초 자동 갱신 | `setInterval`로 핀 마커 자동 동기화 | ✅ 완료 |

### 4.3 서비스 교환 시스템

| 기능 | 설명 | 구현 상태 |
|------|------|---------|
| 회원 탐색 | 전체 회원 목록, 평판 점수 표시 | ✅ 완료 |
| 교환 요청 | 서비스 설명 + 예약 일시 포함 | ✅ 완료 |
| 수락/취소 | 상대방 서비스 제안 명시 | ✅ 완료 |
| 완료 처리 | ACCEPTED → COMPLETED 전환 | ✅ 완료 |
| 매칭 상태 추적 | REQUESTED→ACCEPTED→COMPLETED→CANCELLED | ✅ 완료 |

### 4.4 평판 시스템

| 기능 | 설명 | 구현 상태 |
|------|------|---------|
| 상호 평가 제출 | 교환 완료 후 1~5점 + 코멘트 | ✅ 완료 |
| SHA-256 무결성 해시 | `matchingId|reviewerId|revieweeId|score|comment|timestamp` | ✅ 완료 |
| 무결성 검증 | 해시 재계산으로 DB 변조 감지 | ✅ 완료 |
| 평균 점수 누적 | 실시간 평균 = 총점 / 평가 수 | ✅ 완료 |
| 받은 평가 목록 | 내가 받은 평가 전체 조회 | ✅ 완료 |

### 4.5 블록체인 앵커링

| 기능 | 설명 | 구현 상태 |
|------|------|---------|
| 평가 해시 앵커링 | `anchorSentiment(sentimentId, bytes32)` | ✅ 완료 |
| 핀 해시 앵커링 | `anchorPin(pinId, bytes32)` | ✅ 완료 |
| **평판 앵커링** | `anchorReputation(memberId, scoreScaled, count)` | ✅ 완료 |
| 온체인 검증 | `verifySentiment()` eth_call 조회 | ✅ 완료 |
| 비활성화 모드 | `enabled: false` → 체인 스킵, 나머지 정상 작동 | ✅ 완료 |

---

## 5. 데이터 모델

### ERD (Entity Relationship Diagram)

```
┌──────────────────────┐         ┌──────────────────────┐
│       Member         │         │         Pin          │
├──────────────────────┤         ├──────────────────────┤
│ id (PK)              │◄────────│ member_id (FK)       │
│ did (UNIQUE)         │         │ lat, lng             │
│ email                │         │ content (500자)       │
│ passwordHash         │         │ expiresAt            │
│ nickname             │         │ createdAt            │
│ region               │         │ organizerName        │
│ serviceDescription   │         │ organizerUrl         │
│ role (ENUM)          │         │ eventStartTime       │
│ latitude, longitude  │         │ eventEndTime         │
│ totalSentimentScore  │         │ imageUrl             │
│ sentimentCount       │         │ integrityHash        │
│ createdAt, updatedAt │         │ chainTxHash          │
└──────────────────────┘         └──────────────────────┘

┌──────────────────────┐         ┌──────────────────────┐
│       Matching       │         │      Sentiment       │
├──────────────────────┤         ├──────────────────────┤
│ id (PK)              │◄────────│ matching_id (FK)     │
│ requester_id (FK)    │         │ reviewer_id (FK)     │
│ requestee_id (FK)    │         │ reviewee_id (FK)     │
│ serviceOfferedBy     │         │ score (1~5)          │
│   Requester          │         │ comment (500자)       │
│ serviceOfferedBy     │         │ integrityHash        │
│   Requestee          │         │ chainTxHash          │
│ status (ENUM)        │         │ createdAt            │
│ scheduledAt          │         └──────────────────────┘
│ completedAt          │
│ createdAt, updatedAt │
└──────────────────────┘
```

### Matching 상태 머신

```
       교환 요청
           │
           ▼
      [REQUESTED]
     ╱            ╲
  수락              취소
    │                │
    ▼                ▼
[ACCEPTED]     [CANCELLED]
     │
   완료
     │
     ▼
[COMPLETED] ──► 양측 Sentiment 제출 가능
```

### MemberRole 열거형

| 값 | 설명 |
|----|------|
| `PROVIDER` | 주로 서비스를 제공하는 사람 |
| `RECEIVER` | 주로 서비스를 받고 싶은 사람 |
| `BOTH` | 제공도 하고 받기도 하는 사람 |

---

## 6. API 명세

### 6.1 인증 (`/api/auth`)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/auth/register` | ❌ | 이메일 회원가입 → JWT 반환 |
| POST | `/api/auth/login` | ❌ | 이메일 로그인 → JWT 반환 |
| POST | `/api/auth/did-verify` | ❌ | OmniOne CX DID 인증 → JWT 반환 |

**회원가입 요청 예시:**
```json
{
  "email": "user@example.com",
  "password": "password123",
  "nickname": "홍길동",
  "role": "BOTH",
  "serviceDescription": "프론트엔드 개발, UI 디자인 피드백"
}
```

**응답:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "memberId": 1,
  "nickname": "홍길동",
  "role": "BOTH"
}
```

---

### 6.2 핀 (`/api/pins`)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| GET | `/api/pins` | ✅ | 만료 안 된 활성 핀 전체 조회 |
| POST | `/api/pins` | ✅ | 핀 생성 |
| PUT | `/api/pins/{id}` | ✅ | 핀 수정 (소유자만) |
| DELETE | `/api/pins/{id}` | ✅ | 핀 삭제 (소유자만) |

**핀 생성 요청:**
```json
{
  "lat": 37.5665,
  "lng": 126.9780,
  "content": "여기서 버스킹 중입니다! 🎸",
  "durationMinutes": 120,
  "organizerName": "홍대 인디씬",
  "organizerUrl": "https://instagram.com/hongdae_indie",
  "eventStartTime": "2026-05-12T18:00",
  "eventEndTime": "2026-05-12T21:00",
  "imageUrl": "/uploads/abc123.jpg"
}
```

**핀 조회 응답:**
```json
[
  {
    "id": 1,
    "memberId": 3,
    "nickname": "기타리스트",
    "lat": 37.5665,
    "lng": 126.9780,
    "content": "여기서 버스킹 중입니다! 🎸",
    "expiresAt": "2026-05-12T20:00:00",
    "remainMs": 7200000,
    "imageUrl": "/uploads/abc123.jpg",
    "organizerName": "홍대 인디씬",
    "organizerUrl": "https://instagram.com/hongdae_indie",
    "eventStartTime": "2026-05-12T18:00:00",
    "eventEndTime": "2026-05-12T21:00:00",
    "integrityHash": "9724614e4abe30fb...",
    "chainTxHash": null
  }
]
```

---

### 6.3 이미지 업로드 (`/api/images`)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/images/upload` | ✅ | 이미지 업로드 → URL 반환 |

- **허용 확장자:** `.jpg`, `.jpeg`, `.png`, `.gif`, `.webp`
- **파일명:** UUID 자동 생성 (충돌 방지)
- **서빙 경로:** `/uploads/{uuid}.{ext}` (인증 불필요 접근 가능)

```json
{ "url": "/uploads/550e8400-e29b-41d4-a716-446655440000.jpg" }
```

---

### 6.4 매칭 (`/api/matching`)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/matching/request` | 교환 요청 |
| PUT | `/api/matching/{id}/accept` | 수락 |
| PUT | `/api/matching/{id}/complete` | 완료 처리 |
| PUT | `/api/matching/{id}/cancel` | 취소 |
| GET | `/api/matching/my` | 내 매칭 목록 |
| GET | `/api/matching/{id}` | 매칭 상세 |

---

### 6.5 평가 (`/api/sentiment`)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/sentiment/submit` | 평가 제출 |
| GET | `/api/sentiment/received/{memberId}` | 받은 평가 목록 |
| GET | `/api/sentiment/by-matching/{matchingId}` | 매칭별 평가 |

---

### 6.6 회원 (`/api/members`)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/members` | 전체 회원 목록 |
| GET | `/api/members/me` | 내 정보 |
| PUT | `/api/members/location` | 내 위치 등록/갱신 |

---

## 7. OmniOne 연동 상세

### 7.1 OmniOne CX (행안부 모바일 신분증)

```
사용자                realMap              OmniOne CX 서버
  │                     │                       │
  │  DID 인증 요청       │                       │
  │────────────────────►│                       │
  │                     │  trans API 호출        │
  │                     │──────────────────────►│
  │                     │                       │
  │                     │  ◄── DID + 주소 반환   │
  │                     │                       │
  │  ◄─── JWT 토큰 ─────│                       │
```

- **mock-mode: true** 설정 시 OmniOne 서버 없이 더미 DID 반환 (데모/개발용)
- 실제 연동 시 행사 당일 발급받은 API 키로 교체

### 7.2 DID 구조

| 유형 | DID 형식 | 예시 |
|------|---------|------|
| OmniOne CX (모바일 신분증) | `did:omn:{주소}` | `did:omn:abcdef1234` |
| 이메일 가입 (해커톤 데모) | `did:email:{이메일}` | `did:email:user@example.com` |

---

## 8. 블록체인 앵커링 흐름

### 8.1 앵커링 아키텍처 원칙

```
DB 저장  (동기, 트랜잭션)
    │
    │ 커밋 성공 후
    ▼
체인 앵커링  (비동기, 독립적)
    │
    ├─► 성공: chainTxHash를 DB에 업데이트
    └─► 실패: 로그만 남김 (서비스에 영향 없음)
```

- 체인 앵커링 실패가 핀/평가 저장 롤백을 유발하지 않음
- `enabled: false` 설정 시 전체 체인 기능 스킵

### 8.2 3가지 앵커링 유형

#### ① 평가 앵커링 (`anchorSentiment`)
```
입력: sentimentId (uint256) + SHA-256 해시 (bytes32)
목적: 평가 데이터의 제출 시점 상태를 블록체인에 영구 기록
검증: verifySentiment(id, hash) → bool
```

#### ② 핀 앵커링 (`anchorPin`)
```
입력: pinId (uint256) + SHA-256 해시 (bytes32)
해시 원문: memberId | lat | lng | content | organizerName
목적: 이벤트 등록 사실과 내용을 블록체인에 기록
```

#### ③ 평판 앵커링 (`anchorReputation`)
```
입력: memberId (uint256) + scoreScaled (uint256) + count (uint256)
      scoreScaled = 평균점수 × 100  (예: 4.50점 → 450)
목적: 회원의 평판 점수를 체인에 누적 기록
트리거: 매 평가 제출 시 reviewee의 점수가 갱신될 때마다 호출
```

### 8.3 트랜잭션 구성

```
1. ABI 인코딩:  FunctionEncoder.encode(function)
2. Nonce 조회:  ethGetTransactionCount (리플레이 공격 방지)
3. Raw Tx 생성: gasPrice=20Gwei, gasLimit=100,000
4. EIP-155 서명: TransactionEncoder.signMessage(tx, chainId=1007, credentials)
5. 제출:        ethSendRawTransaction(hexValue)
```

---

## 9. 화면 구성 (UI/UX)

### 9.1 모바일 퍼스트 설계

- **PC 완전 배제**, 스마트폰 기준 설계
- 하단 탭 바 (safe-area 고려, iOS notch 대응)
- 모달은 모두 **바텀 시트** 방식 (아래에서 슬라이드업)
- 모든 터치 타겟 **최소 44px** (Apple HIG 기준)

### 9.2 화면 목록

| 화면 | 설명 |
|------|------|
| **로그인/회원가입** | 탭 전환 방식, 이메일 또는 DID 인증 |
| **지도 (메인)** | 네이버 지도 전체 화면, 회원/이벤트 마커 |
| **홈 (대시보드)** | 평판 점수, 매칭 수, 교환 완료 수 통계 |
| **회원 찾기** | 전체 회원 카드 그리드, 평점 표시 |
| **내 매칭** | 매칭 목록 + 상태 배지 + 상세 보기 |
| **받은 평가** | SHA-256 해시 포함 평가 카드 목록 |

### 9.3 지도 화면 인터랙션

```
지도 진입
    │
    ├─► 📍 내 위치: 현재 좌표로 지도 이동
    ├─► 🚗 교통: 실시간 교통 정보 레이어 on/off
    ├─► 🔄 새로고침: 마커 수동 갱신
    ├─► 📢 핀 추가 모드 ON
    │       └─► 지도 클릭 → 좌표 캡처 → 핀 작성 모달
    │               └─► 이미지 업로드 (선택)
    │               └─► 내용·주최기관·시간 입력
    │               └─► 핀 유지 시간 선택
    │               └─► 등록 → OmniOne Chain 앵커링 시작
    │
    └─► 마커 클릭 → 인포윈도우
            ├─► 이벤트 정보 (내용, 시간, 주최기관)
            ├─► 남은 시간
            ├─► 체인 해시 (일부 표시)
            └─► [교환 요청] 또는 [수정/삭제] (본인 핀)
```

### 9.4 핀 마커 유형

| 상황 | 마커 형태 |
|------|---------|
| 이미지 없음 | 주황 📢 아이콘 (회전된 물방울 형태) |
| 이미지 있음 | **업로드 이미지 그대로** 46×46px 썸네일 |
| 회원 마커 | 파란/보라 원형, 이모지 아이콘 |
| 내 위치 마커 | 하이라이트 색상 (cyan) |

---

## 10. 기술 스택 상세

### Backend

| 항목 | 내용 |
|------|------|
| Language | Java 17 (Eclipse Adoptium JDK 17.0.19) |
| Framework | Spring Boot 3.2.5 |
| Build | Gradle 8.7 |
| DB | H2 In-Memory (demo), `create-drop` 모드 |
| ORM | Spring Data JPA + Hibernate 6 |
| 인증 | Spring Security 6, JJWT 0.11.5, BCrypt |
| 블록체인 | Web3j (OmniOne Chain JSON-RPC) |
| HTTP Client | OkHttp 4 (체인 API 키 인터셉터) |
| 파일 업로드 | Spring MultipartFile + ResourceHandler |

### Frontend

| 항목 | 내용 |
|------|------|
| 방식 | Vanilla JS SPA (단일 HTML 파일) |
| 지도 | Naver Maps JavaScript API v3 (submodules=geocoder) |
| 스타일 | CSS Variables, 다크 그라디언트 테마 |
| 통신 | `fetch()` + JWT Bearer Token |
| 이미지 업로드 | `FormData` + `multipart/form-data` |

### 보안

| 항목 | 내용 |
|------|------|
| 인증 | JWT (HS256, 24시간 만료) |
| 비밀번호 | BCryptPasswordEncoder |
| API 보호 | Spring Security 6 필터 체인 |
| 파일 검증 | 확장자 화이트리스트 (`jpg/jpeg/png/gif/webp`) |
| 경로 보안 | UUID 파일명으로 경로 추측 방지 |
| CSRF | 비활성화 (REST API + JWT = 쿠키 미사용) |
| 세션 | STATELESS (JWT 기반) |

### OmniOne Chain 설정 (해커톤 당일 교체 필요)

```yaml
omnione:
  cx:
    api-key: [행사 당일 발급]
    mock-mode: true  # 데모 시 true 유지
  chain:
    rpc-url: [행사 당일 확인]
    api-key: [행사 당일 발급]
    contract-address: [컨트랙트 배포 후 입력]
    private-key: [서비스 EOA 키]
    chain-id: 1007
    enabled: false  # 체인 연동 시 true로 변경
```

---

## 11. 시연 시나리오

### 시나리오 A: 이벤트 핀 등록 & 발견

```
1. 앱 실행 → 로그인 (이메일 또는 DID)
2. 지도 화면 자동 진입
3. [📢 핀 추가] 버튼 클릭 → 핀 모드 ON
4. 지도에서 원하는 위치 클릭 → 좌표 캡처
5. 핀 작성 모달 (바텀 시트 슬라이드업):
   - 이벤트 내용 입력: "지금 홍대 앞 광장에서 버스킹 중! 🎸"
   - 이미지 선택 (공연 사진)
   - 주최기관: "홍대 인디씬" + URL
   - 시작: 오늘 18:00 / 종료: 미확인
   - 유지 시간: 3시간
6. [📌 핀 등록] → 지도에 이미지 마커 즉시 표시
7. 토스트: "⛓ OmniOne Chain 앵커링 중..."
8. 다른 사용자 → 지도에서 이미지 마커 클릭 → 인포윈도우 확인
9. [교환 요청] 클릭 → 서비스 교환 요청
```

### 시나리오 B: 서비스 교환 & 평판 앵커링

```
1. 회원 찾기 → 상대방 카드 클릭
2. 교환 요청 모달: 내가 제공할 서비스 입력
3. 상대방: 매칭 목록 → [✅ 수락하기] → 본인 서비스 입력
4. 교환 실시 후: [🏁 교환 완료]
5. 양측 각 [⭐ 평가하기] → 1~5점 + 코멘트
6. 백그라운드 (자동):
   - SHA-256(matchingId|reviewerId|revieweeId|score|comment|timestamp) 계산
   - DB 저장 후 트랜잭션 커밋
   - afterCommit(): anchorSentiment() → OmniOne Chain TX
   - afterCommit(): anchorReputation(memberId, 점수×100, 횟수) → Chain TX
7. 받은 평가 화면 → 해시값 + 체인 TX 확인 가능
```

---

## 12. 해커톤 과제 대응 현황

### 필수 과제

| 과제 | 구현 내용 | 상태 |
|------|---------|------|
| OmniOne CX 인증 | trans API 연동, DID 기반 회원 식별 | ✅ |
| 서비스 개발 | 지도 + 핀 + 서비스 교환 + 평판 SPA | ✅ |

### 선택 과제 (+5점)

| 과제 | 구현 내용 | 상태 |
|------|---------|------|
| OmniOne Chain 연동 | 평가·핀·평판 3종 앵커링, ABI 인코딩, EIP-155 서명 | ✅ |

### 가산점 포인트 요약

```
✅ DID 기반 신원 확인 (행안부 모바일 신분증 연동)
✅ SHA-256 데이터 무결성 해시 (평가 변조 탐지)
✅ OmniOne Chain: 평가 해시 앵커링
✅ OmniOne Chain: 이벤트 핀 해시 앵커링
✅ OmniOne Chain: 회원 평판 점수 앵커링 (신규)
✅ 모바일 최적화 UI (하단 탭, 바텀 시트)
✅ 이미지 핀 마커 (지도 위 이미지 직접 표시)
```

---

## 부록 A: 프로젝트 구조

```
realmap/
├── src/main/
│   ├── java/com/realmap/
│   │   ├── RealMapApplication.java
│   │   ├── config/
│   │   │   ├── SecurityConfig.java        # Spring Security 6 설정
│   │   │   ├── WebMvcConfig.java          # /uploads/** 정적 서빙
│   │   │   └── GlobalExceptionHandler.java # 401/403/400 매핑
│   │   ├── controller/
│   │   │   ├── AuthController.java
│   │   │   ├── MemberController.java
│   │   │   ├── PinController.java
│   │   │   ├── ImageController.java       # 이미지 업로드
│   │   │   ├── MatchingController.java
│   │   │   ├── SentimentController.java
│   │   │   └── ChainController.java
│   │   ├── entity/
│   │   │   ├── Member.java
│   │   │   ├── Pin.java                   # imageUrl 포함
│   │   │   ├── Matching.java
│   │   │   ├── Sentiment.java
│   │   │   ├── MatchingStatus.java
│   │   │   └── MemberRole.java
│   │   ├── service/
│   │   │   ├── PinService.java
│   │   │   ├── MatchingService.java
│   │   │   ├── SentimentService.java      # 평판 앵커링 포함
│   │   │   ├── OmniOneChainService.java   # 3종 앵커링
│   │   │   └── DidVerificationService.java
│   │   ├── security/
│   │   │   ├── JwtTokenProvider.java
│   │   │   └── JwtAuthenticationFilter.java
│   │   └── repository/ (JPA)
│   └── resources/
│       ├── application.yml
│       └── static/index.html              # Vanilla JS SPA (전체 UI)
├── uploads/                               # 업로드 이미지 저장 디렉터리
├── docs/
│   └── realmap-project-overview.md       # 이 문서
└── gradle.properties                      # JDK 17 경로 지정
```

---

## 부록 B: 로컬 실행 방법

```bash
# JDK 17 필요
"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe" \
  -jar build/libs/realmap-0.0.1-SNAPSHOT.jar

# 접속: http://localhost:8080
# H2 콘솔: http://localhost:8080/h2-console
#   JDBC URL: jdbc:h2:mem:realmapdb
```

**주의사항:**
- H2 in-memory DB → 재시작 시 모든 데이터 초기화 (데모용)
- JWT는 24시간 유효, 앱 재시작 시 기존 토큰 무효화
- `omnione.chain.enabled: false` 기본값 → 체인 연동 없이 모든 기능 정상 작동

---

*최종 업데이트: 2026-05-12*
