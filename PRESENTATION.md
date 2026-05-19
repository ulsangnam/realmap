# realMap — DID 기반 실시간 지도 서비스 교환 플랫폼

> **2026 블록체인 & AI 해커톤** 출품작  
> OmniOne Chain 연동 (선택과제2, +5% 가산점)

---

## 1. 프로젝트 개요

**realMap**은 Naver Maps 위에서 사용자가 실시간 이벤트 핀을 등록하고,  
DID(분산신원식별자) 기반으로 신뢰할 수 있는 서비스 교환을 중개하는 플랫폼입니다.

### 한 줄 요약
> "지도 위에서 찾고, DID로 신뢰하고, 블록체인으로 증명한다"

### 핵심 가치
| 문제 | realMap 해결책 |
|------|---------------|
| 온라인 서비스 교환의 신뢰 부재 | OmniOne CX(행안부 모바일 신분증) DID 인증 |
| 평가 데이터 위변조 가능성 | SHA-256 + OmniOne Chain 온체인 앵커링 |
| 주변 이벤트/사람 발견의 어려움 | Naver Maps 실시간 핀 시스템 |

---

## 2. 주요 기능

### 2-1. 실시간 이벤트 핀 (지도 핵심 기능)
- 사용자가 지도 위 원하는 위치에 이벤트 핀 등록
- 핀마다 **만료 시간** 설정 (분 단위, 자동 소멸)
- 핀 데이터는 SHA-256 해시로 무결성 보호 → OmniOne Chain 앵커링
- 주최기관명, URL, 이벤트 시작/종료 시각, 대표 이미지 첨부 가능

```
사용자 → 핀 등록 → DB 저장 → 비동기 OmniOne Chain 앵커링
                              └→ chainTxHash 업데이트
```

### 2-2. DID 기반 회원 인증
- **OmniOne CX** (행안부 모바일 신분증) trans API 연동
- DID(`did:omn:...`)가 회원의 유일 식별자
- 이메일+비밀번호 가입도 지원 (DID 미발급 환경 호환)
- JWT 토큰 발급 → 모든 API 인증에 사용

### 2-3. 서비스 교환 & 상호 평가
- 회원 간 매칭(Matching) 생성 → PENDING → ACTIVE → COMPLETED
- 교환 완료 후 1~5점 평가(Sentiment) 제출
- 평가 데이터는 SHA-256 해시 계산 후 OmniOne Chain에 영구 앵커링
- `verifyIntegrity()` 메서드로 언제든 위변조 감지 가능

### 2-4. 평판 시스템
- 누적 평균 평점(1~5점, 소수점 2자리)을 회원 프로필에 표시
- 평판 갱신 시 OmniOne Chain에 `anchorReputation()` 호출
- 체인 데이터: `memberId + scoreScaled(×100) + count`

---

## 3. 기술 스택

### Backend
| 항목 | 선택 |
|------|------|
| 프레임워크 | Spring Boot 3.2.5 |
| 언어 | Java 17 |
| 빌드 | Gradle |
| DB | H2 (인메모리, 데모용) |
| ORM | Spring Data JPA / Hibernate |
| 보안 | Spring Security + JWT (JJWT) |
| 블록체인 | Web3j (OmniOne Chain RPC) |

### Frontend
| 항목 | 선택 |
|------|------|
| 구성 | Vanilla JS SPA (단일 HTML 파일) |
| 지도 | Naver Maps JavaScript API v3 |
| 스타일 | CSS Variables, Glassmorphism 디자인 |
| 통신 | fetch API (JWT Bearer 헤더) |

### 블록체인
| 항목 | 선택 |
|------|------|
| 체인 | OmniOne Chain (이더리움 호환) |
| 스마트 컨트랙트 | Solidity 0.8.20 (SentimentRegistry) |
| 서명 | EOA 개인키 + EIP-155 서명 |
| 인터페이스 | ABI 인코딩 + eth_sendRawTransaction |

---

## 4. 시스템 아키텍처

```
┌─────────────────────────────────────────────────────┐
│                   Frontend (SPA)                    │
│  Naver Maps + Vanilla JS + Glassmorphism UI         │
└──────────────────────┬──────────────────────────────┘
                       │ REST API (JWT)
┌──────────────────────▼──────────────────────────────┐
│              Spring Boot Backend                    │
│                                                     │
│  AuthController   PinController   MatchingController│
│       │                │                 │          │
│  AuthService     PinService       MatchingService   │
│       │                │                 │          │
│  Member(JPA)     Pin(JPA)         Sentiment(JPA)    │
│                        │                 │          │
│              OmniOneChainService (Web3j)             │
└──────────────────────┬──────────────────────────────┘
                       │ eth_sendRawTransaction
┌──────────────────────▼──────────────────────────────┐
│              OmniOne Chain (EVM 호환)                │
│         SentimentRegistry.sol 스마트 컨트랙트        │
└─────────────────────────────────────────────────────┘
```

---

## 5. 블록체인 연동 상세 (선택과제2)

### 스마트 컨트랙트: SentimentRegistry.sol

```solidity
// 핵심 함수
function anchorSentiment(uint256 sentimentId, bytes32 integrityHash) external onlyOwner
function verifySentiment(uint256 sentimentId, bytes32 integrityHash) external view returns (bool)
function isAnchored(uint256 sentimentId) external view returns (bool)
```

- `onlyOwner` 제한 → 서비스 EOA만 기록 가능 (악의적 외부 기록 방지)
- 동일 `sentimentId` 재등록 불가 (불변성 보장)
- 누구나 `verifySentiment()`로 검증 가능 (가스비 없는 `view` 함수)

### 앵커링 흐름

```
1. Sentiment 저장 (DB 트랜잭션 커밋)
2. SHA-256("matchingId|reviewerId|revieweeId|score|comment|timestamp") 계산
3. OmniOneChainService.anchorSentimentAsync() — 비동기 실행
4. Web3j: ABI 인코딩 → RawTransaction 생성 → EIP-155 서명
5. eth_sendRawTransaction → OmniOne Chain
6. txHash 반환 → Sentiment.chainTxHash 업데이트
```

### 앵커링 대상 3종

| 대상 | 컨트랙트 함수 | 데이터 |
|------|-------------|--------|
| 평가(Sentiment) | `anchorSentiment(id, hash)` | SHA-256(6개 필드) |
| 이벤트 핀(Pin) | `anchorPin(id, hash)` | SHA-256(memberId+좌표+내용) |
| 회원 평판 | `anchorReputation(memberId, scoreX100, count)` | 평균점수×100 |

### 비활성화 모드
`omnione.chain.enabled: false` → 체인 호출 건너뜀, 나머지 기능 정상 작동  
(네트워크 미연결 환경 데모 가능)

---

## 6. API 엔드포인트

### 인증
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/auth/register` | 이메일 회원가입 |
| POST | `/api/auth/login` | 로그인 → JWT 발급 |
| POST | `/api/auth/did-login` | OmniOne CX DID 로그인 |

### 지도 핀
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/pins` | 활성 핀 전체 조회 |
| POST | `/api/pins` | 핀 생성 (JWT 필요) |
| PUT | `/api/pins/{id}` | 핀 수정 (소유자만) |
| DELETE | `/api/pins/{id}` | 핀 삭제 (소유자만) |

### 회원
| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/members` | 회원 목록 조회 |
| GET | `/api/members/me` | 내 프로필 |
| PUT | `/api/members/me/location` | 내 위치 업데이트 |

### 서비스 교환
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/matching` | 매칭 요청 생성 |
| PUT | `/api/matching/{id}/accept` | 매칭 수락 |
| PUT | `/api/matching/{id}/complete` | 교환 완료 처리 |

### 평가
| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/sentiments` | 평가 제출 |
| GET | `/api/chain/verify/{id}` | 온체인 무결성 검증 |

---

## 7. 데이터 모델

### Member
```
did (unique, 불변)  ─┐
email (optional)     │ 식별자
nickname             │
region               ┘
serviceDescription   — 제공 서비스 설명
role                 — PROVIDER / RECEIVER / BOTH
latitude, longitude  — 지도 위치
totalSentimentScore  — 평판 누적 합계 (BigDecimal)
sentimentCount       — 평가 횟수
```

### Pin
```
member (FK)          — 등록자
lat, lng             — WGS84 좌표
content              — 이벤트 내용 (500자)
expiresAt            — 만료 시각 (자동 소멸)
integrityHash        — SHA-256(memberId|lat|lng|content)
chainTxHash          — OmniOne Chain TX 해시
organizerName/Url    — 주최기관 (선택)
eventStartTime/End   — 이벤트 시간 (선택)
imageUrl             — 대표 이미지 (선택)
```

### Sentiment
```
matching (FK)        — 해당 교환
reviewer (FK)        — 평가 작성자
reviewee (FK)        — 평가 대상자
score (1~5)          — 평점
comment              — 코멘트 (500자, 선택)
integrityHash        — SHA-256(6개 필드, 불변)
chainTxHash          — OmniOne Chain TX 해시
createdAt            — 제출 일시 (불변)
```

---

## 8. 보안 설계

| 항목 | 구현 |
|------|------|
| 인증 | JWT (HS256, 24시간 만료) |
| 비밀번호 | BCrypt 해싱 |
| DID | OmniOne CX trans API 검증 |
| 핀 소유권 | 삭제/수정 시 memberId 비교 |
| 데이터 무결성 | SHA-256 + 온체인 앵커링 |
| 스마트 컨트랙트 | onlyOwner 제한, 재등록 방지 |
| EIP-155 | chainId 포함 서명 (리플레이 공격 방지) |

---

## 9. 데모 시나리오

```
Step 1. 회원가입 (이메일 또는 OmniOne CX DID)
   └─ JWT 토큰 발급

Step 2. 지도 탭 진입
   └─ 활성 핀 로드 → Naver Maps 마커 렌더링

Step 3. 이벤트 핀 등록
   └─ 위치 선택 → 내용 입력 → 만료 시간 설정
   └─ SHA-256 계산 → OmniOne Chain 비동기 앵커링

Step 4. 회원 탐색 → 서비스 교환 매칭 요청

Step 5. 교환 완료 → 상호 평가 제출
   └─ 평가 데이터 SHA-256 → OmniOne Chain 앵커링

Step 6. /api/chain/verify/{sentimentId} 호출
   └─ 온체인 검증 → "무결성 통과" 확인
```

---

## 10. 프로젝트 구조

```
realmap/
├── contracts/
│   └── SentimentRegistry.sol          # 스마트 컨트랙트
├── src/main/
│   ├── java/com/realmap/
│   │   ├── controller/                # REST 컨트롤러 (6개)
│   │   ├── service/                   # 비즈니스 로직
│   │   │   ├── OmniOneChainService    # Web3j 블록체인 연동
│   │   │   ├── PinService             # 이벤트 핀 + 앵커링
│   │   │   ├── SentimentService       # 평가 + 앵커링
│   │   │   └── MatchingService        # 서비스 교환 매칭
│   │   ├── entity/                    # JPA 엔티티 (5개)
│   │   ├── security/                  # JWT 필터
│   │   └── config/                    # Security, WebMvc 설정
│   └── resources/
│       ├── application.yml            # 설정 (OmniOne, JWT, Naver Maps)
│       └── static/index.html          # Vanilla JS SPA
└── build.gradle
```

---

## 11. 실행 방법

```bash
# 1. 프로젝트 클론 후 realmap 디렉토리로 이동
cd C:\hack\realmap

# 2. 빌드 및 실행
./gradlew bootRun

# 3. 브라우저에서 접속
http://localhost:8080

# 4. H2 콘솔 (DB 확인)
http://localhost:8080/h2-console
# JDBC URL: jdbc:h2:mem:realmapdb
```

### OmniOne Chain 활성화 시
`application.yml`에서 아래 값 교체:
```yaml
omnione:
  chain:
    enabled: true
    rpc-url: <해커톤 발급 RPC URL>
    api-key: <해커톤 발급 API Key>
    contract-address: <배포된 컨트랙트 주소>
    private-key: <서비스 EOA 개인키>
```

---

*realMap — 지도 위에서 연결되고, 블록체인으로 신뢰받다*
