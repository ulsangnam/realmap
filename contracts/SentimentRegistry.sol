// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * @title SentimentRegistry
 * @notice nearVibe 플랫폼의 상호 평가(Sentiment) 무결성 해시를 OmniOne Chain에 앵커링
 *
 * @dev 해커톤 선택과제2 — OmniOne Chain 활용 (+5% 가산점)
 *
 * 동작 원리:
 *   1. 사용자가 서비스 교환 완료 후 평가(Sentiment)를 제출
 *   2. 백엔드에서 SHA-256(matchingId|reviewerId|revieweeId|score|comment|timestamp) 계산
 *   3. 해당 해시를 이 컨트랙트에 영구 기록 → 이후 위변조 불가
 *   4. 누구나 sentimentId로 해시를 조회하여 DB 데이터와 비교 가능
 *
 * OmniOne Chain은 이더리움 호환 체인이므로 Solidity 스마트 컨트랙트를 그대로 배포 가능.
 * (가이드북 §4.4 선택과제2 참조)
 */
contract SentimentRegistry {

    // ── 상태 변수 ────────────────────────────────────────────────

    /**
     * @dev 컨트랙트 배포자 주소 (nearVibe 서비스 운영자).
     * anchorSentiment는 오직 owner만 호출 가능 — 악의적인 외부 기록 방지.
     */
    address public owner;

    /**
     * @dev sentimentId → SHA-256 integrityHash 매핑.
     * bytes32: SHA-256 해시 크기(32바이트)에 딱 맞는 타입.
     * 한 번 기록되면 덮어쓸 수 없음 (anchorSentiment 내 require로 보장).
     */
    mapping(uint256 => bytes32) private sentimentHashes;

    /**
     * @dev 앵커링된 Sentiment 수 (통계용).
     */
    uint256 public totalAnchored;

    // ── 이벤트 ──────────────────────────────────────────────────

    /**
     * @notice 새로운 평가가 온체인에 앵커링될 때 발생하는 이벤트.
     * indexed 키워드로 sentimentId를 이벤트 로그 필터링에 활용 가능.
     *
     * @param sentimentId   nearVibe DB의 Sentiment PK
     * @param integrityHash SHA-256 무결성 해시 (bytes32)
     * @param anchoredAt    앵커링 블록 타임스탬프 (Unix epoch)
     * @param anchoredBy    앵커링 호출자 주소 (서비스 EOA)
     */
    event SentimentAnchored(
        uint256 indexed sentimentId,
        bytes32 indexed integrityHash,
        uint256 anchoredAt,
        address anchoredBy
    );

    // ── 수정자 ──────────────────────────────────────────────────

    /**
     * @dev owner만 실행 가능한 함수에 붙이는 modifier.
     */
    modifier onlyOwner() {
        require(msg.sender == owner, "SentimentRegistry: caller is not the owner");
        _;
    }

    // ── 생성자 ──────────────────────────────────────────────────

    /**
     * @notice 컨트랙트 배포 시 배포자를 owner로 설정.
     * OmniOne Chain에 배포 시 nearVibe 서비스 EOA 계정으로 배포해야 함.
     */
    constructor() {
        owner = msg.sender;
    }

    // ── 핵심 함수 ────────────────────────────────────────────────

    /**
     * @notice Sentiment 무결성 해시를 온체인에 영구 앵커링.
     *
     * @dev onlyOwner 제한: nearVibe 백엔드 서비스만 호출 가능.
     *      한 번 앵커링된 sentimentId는 재등록 불가 (불변성 보장).
     *
     * @param sentimentId   nearVibe DB의 Sentiment.id 값
     * @param integrityHash Sentiment 데이터의 SHA-256 해시 (bytes32)
     *
     * Emits: {SentimentAnchored}
     */
    function anchorSentiment(uint256 sentimentId, bytes32 integrityHash) external onlyOwner {
        // 동일 sentimentId 재등록 방지 — bytes32 zero value(0x000...0)로 미등록 판별
        require(
            sentimentHashes[sentimentId] == bytes32(0),
            "SentimentRegistry: already anchored"
        );
        // 빈 해시 방지
        require(
            integrityHash != bytes32(0),
            "SentimentRegistry: invalid hash"
        );

        // 해시 영구 저장
        sentimentHashes[sentimentId] = integrityHash;
        totalAnchored++;

        // 이벤트 발생 — 블록 익스플로러 및 클라이언트 구독용
        emit SentimentAnchored(
            sentimentId,
            integrityHash,
            block.timestamp,
            msg.sender
        );
    }

    /**
     * @notice sentimentId에 저장된 해시와 주어진 해시가 일치하는지 검증.
     *         누구나 호출 가능 (view 함수 — 가스비 없음).
     *
     * @param sentimentId   검증할 Sentiment의 ID
     * @param integrityHash 비교할 SHA-256 해시
     * @return true면 데이터 무결성 통과 (앵커링된 해시와 일치)
     */
    function verifySentiment(
        uint256 sentimentId,
        bytes32 integrityHash
    ) external view returns (bool) {
        bytes32 stored = sentimentHashes[sentimentId];
        // 아직 앵커링되지 않았거나 해시 불일치 시 false
        return stored != bytes32(0) && stored == integrityHash;
    }

    /**
     * @notice sentimentId에 앵커링된 해시 원본을 조회.
     *         앵커링되지 않은 경우 bytes32(0) 반환.
     *
     * @param sentimentId 조회할 Sentiment ID
     * @return 저장된 integrityHash. 미앵커링 시 0x000...0
     */
    function getAnchoredHash(uint256 sentimentId) external view returns (bytes32) {
        return sentimentHashes[sentimentId];
    }

    /**
     * @notice 특정 sentimentId가 이미 앵커링되었는지 확인.
     *
     * @param sentimentId 확인할 Sentiment ID
     * @return true면 이미 온체인에 기록됨
     */
    function isAnchored(uint256 sentimentId) external view returns (bool) {
        return sentimentHashes[sentimentId] != bytes32(0);
    }

    // ── 관리 함수 ────────────────────────────────────────────────

    /**
     * @notice owner 권한을 새 주소로 이전.
     * @param newOwner 새 owner 주소 (zero address 불가)
     */
    function transferOwnership(address newOwner) external onlyOwner {
        require(newOwner != address(0), "SentimentRegistry: new owner is zero address");
        owner = newOwner;
    }
}
