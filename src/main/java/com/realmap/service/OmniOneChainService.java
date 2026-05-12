package com.realmap.service;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.utils.Numeric;

import jakarta.annotation.PostConstruct;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * OmniOne Chain 연동 서비스 (선택과제2 — 가산점 +5%)
 *
 * <h3>역할</h3>
 * realMap의 평가(Sentiment) 무결성 해시를 OmniOne Chain의
 * SentimentRegistry 스마트 컨트랙트에 영구 앵커링합니다.
 *
 * <h3>OmniOne Chain 개요 (가이드북 §4.4)</h3>
 * <ul>
 *   <li>이더리움 호환 체인 — Solidity 컨트랙트 배포 가능</li>
 *   <li>REST API + API Key 방식으로 트랜잭션 제출</li>
 *   <li>EOA(Externally Owned Account) 개인키로 트랜잭션 서명</li>
 *   <li>eth_sendRawTransaction: 서명된 트랜잭션 제출</li>
 *   <li>eth_call: 컨트랙트 상태 조회 (가스비 없음)</li>
 * </ul>
 *
 * <h3>앵커링 흐름</h3>
 * <pre>
 *   1. SentimentService가 평가 저장 후 anchorSentiment() 비동기 호출
 *   2. sentimentId(uint256) + integrityHash(bytes32) → ABI 인코딩
 *   3. EOA 개인키로 RawTransaction 서명
 *   4. OmniOne Chain RPC에 eth_sendRawTransaction 제출
 *   5. 트랜잭션 해시(txHash) 반환 → Sentiment.chainTxHash에 저장
 * </pre>
 *
 * <h3>비활성화 모드</h3>
 * {@code omnione.chain.enabled: false} 설정 시 체인 호출을 건너뛰고
 * 나머지 서비스는 정상 작동합니다 (네트워크 미연결 환경 데모용).
 */
@Slf4j
@Service
public class OmniOneChainService {

    // ── 설정값 (application.yml) ─────────────────────────────────

    /** OmniOne Chain RPC URL (이더리움 호환 JSON-RPC 엔드포인트) */
    @Value("${omnione.chain.rpc-url}")
    private String rpcUrl;

    /** OmniOne Chain API Key (요청 헤더 X-API-Key에 포함) */
    @Value("${omnione.chain.api-key}")
    private String apiKey;

    /** 배포된 SentimentRegistry 컨트랙트 주소 (0x...) */
    @Value("${omnione.chain.contract-address}")
    private String contractAddress;

    /** 트랜잭션 서명용 EOA 개인키 (0x... 형식, 운영에서는 Vault 관리) */
    @Value("${omnione.chain.private-key}")
    private String privateKey;

    /** 체인 연동 활성화 여부 (false면 앵커링 스킵) */
    @Value("${omnione.chain.enabled:false}")
    private boolean enabled;

    /** OmniOne Chain ID (이더리움 네트워크 구분자) */
    @Value("${omnione.chain.chain-id:1007}")
    private long chainId;

    // ── 내부 상태 ────────────────────────────────────────────────

    /** Web3j 인스턴스 (OmniOne Chain RPC에 연결) */
    private Web3j web3j;

    /** 트랜잭션 서명용 자격증명 (EOA 개인키 → 주소) */
    private Credentials credentials;

    // ── 초기화 ──────────────────────────────────────────────────

    /**
     * 빈 초기화 시 Web3j 클라이언트와 Credentials를 설정합니다.
     * enabled=false이면 초기화를 건너뜁니다.
     */
    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[OmniOne Chain] 비활성화 상태 — 체인 앵커링 건너뜁니다. " +
                     "(omnione.chain.enabled=false)");
            return;
        }

        try {
            // OkHttpClient에 API Key 헤더를 인터셉터로 주입
            // 가이드북: OmniOne Chain은 API Key 인증 방식 사용
            OkHttpClient httpClient = new OkHttpClient.Builder()
                    .addInterceptor(chain -> {
                        okhttp3.Request original = chain.request();
                        okhttp3.Request request = original.newBuilder()
                                .header("X-API-Key", apiKey)  // OmniOne Chain API Key
                                .build();
                        return chain.proceed(request);
                    })
                    .build();

            // Web3j를 OmniOne Chain RPC URL에 연결
            web3j = Web3j.build(new HttpService(rpcUrl, httpClient));

            // EOA 개인키로 Credentials 생성 (트랜잭션 서명에 사용)
            credentials = Credentials.create(privateKey);

            log.info("[OmniOne Chain] 초기화 완료. 계정주소: {}, 컨트랙트: {}",
                     credentials.getAddress(), contractAddress);

        } catch (Exception e) {
            // 체인 초기화 실패 시 서비스 전체가 멈추지 않도록 경고만 출력
            log.error("[OmniOne Chain] 초기화 실패 — 체인 기능이 비활성화됩니다: {}", e.getMessage());
            enabled = false;
        }
    }

    // ── 핵심 공개 API ────────────────────────────────────────────

    /**
     * Sentiment 무결성 해시를 OmniOne Chain에 비동기로 앵커링합니다.
     *
     * <p>SentimentService에서 평가 저장 직후 호출됩니다.
     * 체인 트랜잭션이 실패해도 평가 저장 자체에는 영향을 주지 않습니다.</p>
     *
     * @param sentimentId   앵커링할 Sentiment의 DB PK
     * @param integrityHash SHA-256 무결성 해시 (HEX 문자열, 64자)
     * @return 트랜잭션 해시 (CompletableFuture) — 실패 시 null 반환
     */
    public CompletableFuture<String> anchorSentimentAsync(Long sentimentId, String integrityHash) {
        if (!enabled) {
            log.debug("[OmniOne Chain] 비활성화 — sentimentId={} 앵커링 스킵", sentimentId);
            return CompletableFuture.completedFuture(null);
        }

        // 비동기 실행: 체인 트랜잭션은 DB 저장과 독립적으로 처리
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doAnchorSentiment(sentimentId, integrityHash);
            } catch (Exception e) {
                log.error("[OmniOne Chain] 앵커링 실패. sentimentId={}, 원인: {}",
                          sentimentId, e.getMessage(), e);
                return null;
            }
        });
    }

    /**
     * Pin 무결성 해시를 OmniOne Chain에 비동기로 앵커링합니다.
     *
     * <p>PinService에서 핀 저장 직후 호출됩니다.
     * 체인 트랜잭션이 실패해도 핀 저장 자체에는 영향을 주지 않습니다.</p>
     *
     * <p>체인 데이터: SHA-256(memberId|lat|lng|content)의 64자 HEX 해시</p>
     *
     * @param pinId         앵커링할 Pin의 DB PK
     * @param integrityHash SHA-256 무결성 해시 (HEX 문자열, 64자)
     * @return 트랜잭션 해시 (CompletableFuture) — 실패 시 null 반환
     */
    public CompletableFuture<String> anchorPinAsync(Long pinId, String integrityHash) {
        if (!enabled) {
            log.debug("[OmniOne Chain] 비활성화 — pinId={} 앵커링 스킵", pinId);
            return CompletableFuture.completedFuture(null);
        }

        // 비동기 실행: 체인 트랜잭션은 DB 저장과 독립적으로 처리
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. ABI 인코딩 — PinRegistry.anchorPin(uint256, bytes32)
                // anchorSentiment와 동일한 컨트랙트 패턴, 함수명만 "anchorPin"으로 변경
                Function function = new Function(
                        "anchorPin",
                        Arrays.asList(
                                new Uint256(pinId),
                                new Bytes32(hexToBytes32(integrityHash))  // 64자 HEX → 32바이트
                        ),
                        Collections.emptyList()  // 반환값 없음 (nonpayable)
                );
                String encodedFunction = FunctionEncoder.encode(function);

                // 2. 현재 계정의 nonce 조회 (중복 제출 방지)
                EthGetTransactionCount ethGetTransactionCount = web3j.ethGetTransactionCount(
                        credentials.getAddress(), DefaultBlockParameterName.LATEST).send();
                BigInteger nonce = ethGetTransactionCount.getTransactionCount();

                // 3. RawTransaction 생성 (anchorSentiment와 동일한 가스 설정)
                RawTransaction rawTransaction = RawTransaction.createTransaction(
                        nonce,
                        BigInteger.valueOf(20_000_000_000L),  // gasPrice: 20 Gwei
                        BigInteger.valueOf(100_000L),          // gasLimit: anchorPin 실행에 충분한 가스
                        contractAddress,
                        BigInteger.ZERO,    // ETH 전송 없음 (nonpayable)
                        encodedFunction
                );

                // 4. EIP-155 서명 후 eth_sendRawTransaction으로 OmniOne Chain에 제출
                byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, chainId, credentials);
                String hexValue = Numeric.toHexString(signedMessage);

                EthSendTransaction ethSendTransaction = web3j.ethSendRawTransaction(hexValue).send();
                if (ethSendTransaction.hasError()) {
                    throw new RuntimeException(
                            "OmniOne Chain 오류: " + ethSendTransaction.getError().getMessage());
                }

                String txHash = ethSendTransaction.getTransactionHash();
                log.info("[OmniOne Chain] Pin 앵커링 성공. pinId={}, txHash={}", pinId, txHash);
                return txHash;

            } catch (Exception e) {
                log.error("[OmniOne Chain] Pin 앵커링 실패. pinId={}: {}", pinId, e.getMessage());
                return null;
            }
        });
    }

    /**
     * 회원의 평판(reputation) 상태를 OmniOne Chain에 비동기로 앵커링합니다.
     *
     * <p>SentimentService에서 평가 저장 후 reviewee의 평균 점수가 갱신될 때 호출됩니다.
     * 체인 데이터: memberId(uint256) + scoreScaled(uint256, 평균점수×100) + count(uint256)</p>
     *
     * @param memberId    평판이 갱신된 회원의 DB PK
     * @param scoreScaled 평균 점수 × 100 정수 (예: 4.50 → 450)
     * @param count       누적 평가 횟수
     * @return 트랜잭션 해시 (CompletableFuture) — 실패 시 null 반환
     */
    public CompletableFuture<String> anchorReputationAsync(Long memberId, int scoreScaled, int count) {
        if (!enabled) {
            log.debug("[OmniOne Chain] 비활성화 — memberId={} 평판 앵커링 스킵", memberId);
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                Function function = new Function(
                        "anchorReputation",
                        Arrays.asList(
                                new Uint256(memberId),
                                new Uint256(scoreScaled),
                                new Uint256(count)
                        ),
                        Collections.emptyList()
                );
                String encodedFunction = FunctionEncoder.encode(function);

                EthGetTransactionCount ethGetTransactionCount = web3j.ethGetTransactionCount(
                        credentials.getAddress(), DefaultBlockParameterName.LATEST).send();
                BigInteger nonce = ethGetTransactionCount.getTransactionCount();

                RawTransaction rawTransaction = RawTransaction.createTransaction(
                        nonce,
                        BigInteger.valueOf(20_000_000_000L),
                        BigInteger.valueOf(100_000L),
                        contractAddress,
                        BigInteger.ZERO,
                        encodedFunction
                );

                byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, chainId, credentials);
                String hexValue = Numeric.toHexString(signedMessage);

                EthSendTransaction ethSendTransaction = web3j.ethSendRawTransaction(hexValue).send();
                if (ethSendTransaction.hasError()) {
                    throw new RuntimeException(
                            "OmniOne Chain 오류: " + ethSendTransaction.getError().getMessage());
                }

                String txHash = ethSendTransaction.getTransactionHash();
                log.info("[OmniOne Chain] 평판 앵커링 성공. memberId={}, score={}/100, count={}, txHash={}",
                         memberId, scoreScaled, count, txHash);
                return txHash;

            } catch (Exception e) {
                log.error("[OmniOne Chain] 평판 앵커링 실패. memberId={}: {}", memberId, e.getMessage());
                return null;
            }
        });
    }

    /**
     * OmniOne Chain에서 Sentiment 해시를 조회하여 무결성을 검증합니다.
     *
     * <p>eth_call 사용 — 가스비 없이 컨트랙트 상태만 읽습니다.</p>
     *
     * @param sentimentId   검증할 Sentiment ID
     * @param integrityHash DB에 저장된 SHA-256 해시
     * @return 온체인 해시와 일치하면 true (앵커링 미완료 시 false)
     */
    public boolean verifyOnChain(Long sentimentId, String integrityHash) {
        if (!enabled) {
            log.debug("[OmniOne Chain] 비활성화 — 온체인 검증 불가");
            return false;
        }

        try {
            // SentimentRegistry.verifySentiment(uint256, bytes32) 함수 호출 인코딩
            Function function = new Function(
                    "verifySentiment",
                    Arrays.asList(
                            new Uint256(sentimentId),
                            new Bytes32(hexToBytes32(integrityHash))
                    ),
                    Collections.singletonList(new TypeReference<Bool>() {})
            );

            String encodedFunction = FunctionEncoder.encode(function);

            // eth_call로 컨트랙트 읽기 (트랜잭션 없이 조회)
            EthCall response = web3j.ethCall(
                    Transaction.createEthCallTransaction(
                            credentials.getAddress(),  // from (조회자 주소)
                            contractAddress,           // to (컨트랙트 주소)
                            encodedFunction            // data (ABI 인코딩된 함수 호출)
                    ),
                    DefaultBlockParameterName.LATEST
            ).send();

            if (response.hasError()) {
                log.warn("[OmniOne Chain] eth_call 오류: {}", response.getError().getMessage());
                return false;
            }

            // 반환값 디코딩 (bool)
            List<Type> decoded = FunctionReturnDecoder.decode(
                    response.getValue(),
                    function.getOutputParameters()
            );

            if (decoded.isEmpty()) {
                return false;
            }

            boolean result = (Boolean) decoded.get(0).getValue();
            log.debug("[OmniOne Chain] 온체인 검증 결과. sentimentId={}, result={}", sentimentId, result);
            return result;

        } catch (Exception e) {
            log.error("[OmniOne Chain] 온체인 검증 실패. sentimentId={}: {}", sentimentId, e.getMessage());
            return false;
        }
    }

    /**
     * 특정 sentimentId가 체인에 앵커링되었는지 확인합니다.
     *
     * @param sentimentId 확인할 Sentiment ID
     * @return 앵커링 완료되면 true
     */
    public boolean isAnchoredOnChain(Long sentimentId) {
        if (!enabled) return false;

        try {
            Function function = new Function(
                    "isAnchored",
                    Collections.singletonList(new Uint256(sentimentId)),
                    Collections.singletonList(new TypeReference<Bool>() {})
            );

            String encodedFunction = FunctionEncoder.encode(function);

            EthCall response = web3j.ethCall(
                    Transaction.createEthCallTransaction(
                            credentials.getAddress(),
                            contractAddress,
                            encodedFunction
                    ),
                    DefaultBlockParameterName.LATEST
            ).send();

            if (response.hasError()) return false;

            List<Type> decoded = FunctionReturnDecoder.decode(
                    response.getValue(), function.getOutputParameters());

            return !decoded.isEmpty() && (Boolean) decoded.get(0).getValue();

        } catch (Exception e) {
            log.error("[OmniOne Chain] isAnchored 조회 실패. sentimentId={}: {}", sentimentId, e.getMessage());
            return false;
        }
    }

    /** 체인 연동 활성화 여부 반환 */
    public boolean isEnabled() {
        return enabled;
    }

    // ── 내부 구현 ────────────────────────────────────────────────

    /**
     * SentimentRegistry.anchorSentiment(uint256, bytes32)를 실제로 호출합니다.
     *
     * <h3>트랜잭션 구성 과정</h3>
     * <ol>
     *   <li>ABI 인코딩: 함수명 + 파라미터 → calldata 바이트열</li>
     *   <li>논스(nonce) 조회: 계정의 현재 트랜잭션 수 (리플레이 공격 방지)</li>
     *   <li>RawTransaction 생성: nonce, gasPrice, gasLimit, to, data</li>
     *   <li>EOA 개인키로 EIP-155 서명 (chainId 포함)</li>
     *   <li>eth_sendRawTransaction으로 OmniOne Chain에 제출</li>
     * </ol>
     *
     * @param sentimentId   앵커링할 Sentiment ID
     * @param integrityHash HEX 형식의 SHA-256 해시 (64자)
     * @return 제출된 트랜잭션 해시 (0x...)
     */
    private String doAnchorSentiment(Long sentimentId, String integrityHash) throws Exception {
        log.debug("[OmniOne Chain] 앵커링 시작. sentimentId={}", sentimentId);

        // 1. ABI 인코딩 — SentimentRegistry.anchorSentiment(uint256, bytes32)
        Function function = new Function(
                "anchorSentiment",
                Arrays.asList(
                        new Uint256(sentimentId),
                        new Bytes32(hexToBytes32(integrityHash))  // 64자 HEX → 32바이트
                ),
                Collections.emptyList()  // 반환값 없음 (nonpayable)
        );
        String encodedFunction = FunctionEncoder.encode(function);

        // 2. 현재 계정의 nonce 조회 (중복 제출 방지)
        EthGetTransactionCount ethGetTransactionCount = web3j.ethGetTransactionCount(
                credentials.getAddress(),
                DefaultBlockParameterName.LATEST
        ).send();
        BigInteger nonce = ethGetTransactionCount.getTransactionCount();

        // 3. 가스 설정
        // gasPrice: OmniOne Chain 권장값 (실제 값은 eth_gasPrice로 조회 가능)
        BigInteger gasPrice = BigInteger.valueOf(20_000_000_000L);  // 20 Gwei
        // gasLimit: anchorSentiment 실행에 충분한 가스 (SSTORE 연산 포함)
        BigInteger gasLimit = BigInteger.valueOf(100_000L);

        // 4. RawTransaction 생성 (컨트랙트 함수 호출용 — value=0)
        RawTransaction rawTransaction = RawTransaction.createTransaction(
                nonce,
                gasPrice,
                gasLimit,
                contractAddress,    // SentimentRegistry 주소
                BigInteger.ZERO,    // ETH 전송 없음 (nonpayable)
                encodedFunction     // ABI 인코딩된 anchorSentiment(sentimentId, hash) 호출
        );

        // 5. EIP-155 서명 (chainId 포함 — 다른 네트워크 리플레이 방지)
        byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, chainId, credentials);
        String hexValue = Numeric.toHexString(signedMessage);

        // 6. eth_sendRawTransaction으로 OmniOne Chain에 제출
        EthSendTransaction ethSendTransaction = web3j.ethSendRawTransaction(hexValue).send();

        if (ethSendTransaction.hasError()) {
            throw new RuntimeException(
                    "OmniOne Chain 트랜잭션 오류: " + ethSendTransaction.getError().getMessage());
        }

        String txHash = ethSendTransaction.getTransactionHash();
        log.info("[OmniOne Chain] 앵커링 성공. sentimentId={}, txHash={}", sentimentId, txHash);
        return txHash;
    }

    // ── 유틸 ─────────────────────────────────────────────────────

    /**
     * 64자 HEX 문자열 SHA-256 해시를 Solidity bytes32 형식의 byte[32]로 변환합니다.
     *
     * <p>Sentiment.integrityHash는 소문자 HEX 64자입니다.
     * Solidity bytes32는 32바이트 배열이므로 HEX 디코딩이 필요합니다.</p>
     *
     * @param hexHash 64자 HEX 문자열 (예: "a3f2e1...")
     * @return 32바이트 배열
     */
    private byte[] hexToBytes32(String hexHash) {
        // "0x" 접두사가 있으면 제거
        String clean = hexHash.startsWith("0x") ? hexHash.substring(2) : hexHash;
        byte[] bytes = Numeric.hexStringToByteArray(clean);

        // bytes32는 정확히 32바이트여야 함 — 부족하면 앞을 0으로 패딩
        if (bytes.length == 32) return bytes;

        byte[] padded = new byte[32];
        int offset = 32 - bytes.length;
        System.arraycopy(bytes, 0, padded, offset, bytes.length);
        return padded;
    }

    /**
     * 앵커링 결과를 담는 레코드.
     * OmniOneChainService → SentimentService로 전달됩니다.
     *
     * @param txHash      OmniOne Chain 트랜잭션 해시 (0x...)
     * @param anchored    앵커링 성공 여부
     * @param errorMessage 실패 시 오류 메시지
     */
    public record AnchorResult(String txHash, boolean anchored, String errorMessage) {
        public static AnchorResult success(String txHash) {
            return new AnchorResult(txHash, true, null);
        }
        public static AnchorResult failure(String reason) {
            return new AnchorResult(null, false, reason);
        }
        public static AnchorResult skipped() {
            return new AnchorResult(null, false, "chain disabled");
        }
    }
}
