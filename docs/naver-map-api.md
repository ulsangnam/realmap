# Naver Maps API v3 — realMap 연동 가이드

## 1. Client ID 발급 (필수 선행 작업)

1. [네이버 클라우드 플랫폼](https://www.ncloud.com) 가입 + 결제 수단 등록 (무료 티어 있음)
2. Console → **AI·Application Service → Maps → Application** → 애플리케이션 등록
3. 서비스 항목에서 **Web Dynamic Map** 체크
4. 서비스 환경: `http://localhost:8080` 추가 (개발용)
5. 등록 후 **Client ID (ncpKeyId)** 복사

> `application.yml`의 `naver.map.client-id`에 입력하거나 `index.html`의 script src에 직접 삽입

---

## 2. 기본 스크립트 로드

```html
<script src="https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=YOUR_CLIENT_ID&submodules=geocoder,visualization"></script>
```

| submodule | 용도 |
|-----------|------|
| `geocoder` | 주소 ↔ 좌표 변환 |
| `visualization` | 히트맵, 도트맵 |
| `drawing` | 폴리곤·원 드로잉 |

---

## 3. 지도 초기화

```javascript
const map = new naver.maps.Map('map', {
  center: new naver.maps.LatLng(37.5665, 126.9780), // 서울 시청
  zoom: 13,
  mapTypeId: naver.maps.MapTypeId.NORMAL
});
```

---

## 4. 핵심 기능 레퍼런스

### 마커 (Marker)
```javascript
const marker = new naver.maps.Marker({
  position: new naver.maps.LatLng(lat, lng),
  map: map,
  title: '서비스 제공자',
  icon: {
    content: '<div class="custom-marker">...</div>', // HTML 마커
    anchor: new naver.maps.Point(16, 16)
  }
});
```

### 정보창 (InfoWindow)
```javascript
const infoWindow = new naver.maps.InfoWindow({
  content: '<div style="padding:10px"><b>김민준</b><br>React 개발 제공</div>'
});
naver.maps.Event.addListener(marker, 'click', () => {
  infoWindow.open(map, marker);
});
```

### 클릭 이벤트 (위치 핀 등록)
```javascript
naver.maps.Event.addListener(map, 'click', (e) => {
  const { x: lng, y: lat } = e.coord;
  // → POST /api/pins { lat, lng, content }
});
```

### 현재 위치
```javascript
navigator.geolocation.getCurrentPosition(({ coords }) => {
  map.setCenter(new naver.maps.LatLng(coords.latitude, coords.longitude));
});
```

### 주소 → 좌표 (Geocoding)
```javascript
naver.maps.Service.geocode({ query: '강남구 테헤란로' }, (status, res) => {
  if (status === naver.maps.Service.Status.OK) {
    const { x, y } = res.v2.addresses[0];
    map.setCenter(new naver.maps.LatLng(y, x));
  }
});
```

### 레이어 (실시간 교통 등)
```javascript
const trafficLayer = new naver.maps.TrafficLayer();
trafficLayer.setMap(map); // 실시간 교통 레이어 ON
```

---

## 5. realMap 구현 계획

### 핵심 컨셉
> 지도 위에서 **서비스 제공자들의 위치**를 실시간으로 확인하고, 바로 교환 요청

### 구현 순서

#### Phase 1 — 지도 + 회원 핀 표시
- [ ] `index.html`에 지도 화면(`pg-map`) 추가
- [ ] `GET /api/members` 응답에 `lat`, `lng` 필드 추가
- [ ] 지도 로드 시 전체 회원 마커 렌더링
- [ ] 마커 클릭 → InfoWindow에 닉네임·서비스·평점 표시
- [ ] InfoWindow에 **"교환 요청"** 버튼 → 기존 요청 모달 연결

#### Phase 2 — 실시간 업데이트
- [ ] Spring WebSocket (STOMP) 또는 30초 폴링으로 새 핀 반영
- [ ] 회원 위치 등록/수정 API `PUT /api/members/location`

#### Phase 3 — 정보 공유 핀
- [ ] 자유 핀 등록: 지도 클릭 → 내용 입력 → `POST /api/pins`
- [ ] 핀 카테고리: 서비스 요청 / 정보 공유 / 모임
- [ ] 핀 만료 시간 (예: 24시간 후 자동 삭제)

#### Phase 4 — 고급 기능
- [ ] 히트맵: 서비스 활동 밀집 지역 시각화
- [ ] 반경 검색: 현재 위치 기준 N km 이내 회원 필터
- [ ] 교통 레이어 ON/OFF 토글

---

## 6. Member 엔티티 위치 필드 추가 예정

```java
@Column
private Double latitude;

@Column
private Double longitude;
```

---

## 7. 필요한 백엔드 API 추가 목록

| Method | Path | 설명 |
|--------|------|------|
| `PUT` | `/api/members/location` | 내 위치 등록/수정 |
| `GET` | `/api/members/nearby?lat=&lng=&radius=` | 반경 내 회원 조회 |
| `POST` | `/api/pins` | 자유 핀 등록 |
| `GET` | `/api/pins` | 전체 핀 조회 |
| `DELETE` | `/api/pins/{id}` | 핀 삭제 |

---

## 참고 링크

- [공식 문서 (한국어)](https://navermaps.github.io/maps.js.ncp/docs/)
- [예제 모음](https://navermaps.github.io/maps.js.ncp/docs/tutorial-digest.example.html)
- [Client ID 발급 가이드](https://navermaps.github.io/maps.js.ncp/docs/tutorial-1-Getting-Client-ID.html)
- [NCP Console](https://console.ncloud.com)
