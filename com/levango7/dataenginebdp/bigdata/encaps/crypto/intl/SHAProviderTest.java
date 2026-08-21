package com.shuqing.bigdata.encaps.crypto.intl;

import com.shuqing.bigdata.encaps.crypto.CryptoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SHAProvider} 单元测试。
 *
 * <p>使用 NIST FIPS 180-4 标准已知测试向量验证 SHA-256/384/512 实现正确性。</p>
 *
 * <h3>NIST FIPS 180-4 测试向量来源</h3>
 * <ul>
 *   <li>FIPS 180-4 Appendix B — SHA-256/384/512 测试向量</li>
 *   <li>NIST CSRC Example Hashes</li>
 * </ul>
 *
 * <p>所有测试向量均由 JDK {@link MessageDigest} 验证确认。</p>
 */
class SHAProviderTest {

    private SHAProvider shaProvider;

    @BeforeEach
    void setUp() {
        shaProvider = new SHAProvider();
    }

    // ===== NIST FIPS 180-4 已知测试向量（SHA-256） =====

    @Test
    @DisplayName("NIST FIPS 180-4 — SHA-256(\"\") = e3b0c442...")
    void nist_sha256_emptyString() {
        byte[] data = new byte[0];
        String hex = shaProvider.digestHex(data, "SHA-256");

        assertThat(hex).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("NIST FIPS 180-4 — SHA-256(\"abc\") = ba7816bf...")
    void nist_sha256_abc() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        String hex = shaProvider.digestHex(data, "SHA-256");

        assertThat(hex).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("NIST FIPS 180-4 — SHA-256(56字节消息) = 248d6a61...")
    void nist_sha256_56byteMessage() {
        // NIST FIPS 180-4 第二个测试向量：56字节消息
        byte[] data = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".getBytes(StandardCharsets.UTF_8);
        String hex = shaProvider.digestHex(data, "SHA-256");

        assertThat(hex).isEqualTo("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
    }

    @Test
    @DisplayName("NIST FIPS 180-4 — SHA-256(1百万个'a') = cdc76e5c...")
    void nist_sha256_millionA() {
        // NIST FIPS 180-4 第三个测试向量：1百万个'a'
        byte[] data = new byte[1_000_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = 'a';
        }
        String hex = shaProvider.digestHex(data, "SHA-256");

        assertThat(hex).isEqualTo("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
    }

    // ===== NIST FIPS 180-4 已知测试向量（SHA-384） =====

    @Test
    @DisplayName("NIST FIPS 180-4 — SHA-384(\"\") = 38b060a7...")
    void nist_sha384_emptyString() {
        byte[] data = new byte[0];
        String hex = shaProvider.digestHex(data, "SHA-384");

        assertThat(hex).isEqualTo("38b060a751ac96384cd9327eb1b1e36a21fdb71114be07434c0cc7bf63f6e1da"
                + "274edebfe76f65fbd51ad2f14898b95b");
    }

    @Test
    @DisplayName("NIST FIPS 180-4 — SHA-384(\"abc\") = cb00753f...")
    void nist_sha384_abc() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        String hex = shaProvider.digestHex(data, "SHA-384");

        assertThat(hex).isEqualTo("cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed"
                + "8086072ba1e7cc2358baeca134c825a7");
    }

    // ===== NIST FIPS 180-4 已知测试向量（SHA-512） =====

    @Test
    @DisplayName("NIST FIPS 180-4 — SHA-512(\"\") = cf83e135...")
    void nist_sha512_emptyString() {
        byte[] data = new byte[0];
        String hex = shaProvider.digestHex(data, "SHA-512");

        assertThat(hex).isEqualTo("cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce"
                + "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e");
    }

    @Test
    @DisplayName("NIST FIPS 180-4 — SHA-512(\"abc\") = ddaf35a1...")
    void nist_sha512_abc() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        String hex = shaProvider.digestHex(data, "SHA-512");

        assertThat(hex).isEqualTo("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f");
    }

    // ===== JDK 一致性验证（使用JDK MessageDigest作为参考） =====

    @Test
    @DisplayName("JDK一致性 — SHA-256 与 JDK MessageDigest 结果一致")
    void jdkConsistency_sha256() throws Exception {
        byte[] data = "consistency-test-256".getBytes(StandardCharsets.UTF_8);
        byte[] ourDigest = shaProvider.digest(data, "SHA-256");
        byte[] jdkDigest = MessageDigest.getInstance("SHA-256").digest(data);
        assertThat(ourDigest).isEqualTo(jdkDigest);
    }

    @Test
    @DisplayName("JDK一致性 — SHA-384 与 JDK MessageDigest 结果一致")
    void jdkConsistency_sha384() throws Exception {
        byte[] data = "consistency-test-384".getBytes(StandardCharsets.UTF_8);
        byte[] ourDigest = shaProvider.digest(data, "SHA-384");
        byte[] jdkDigest = MessageDigest.getInstance("SHA-384").digest(data);
        assertThat(ourDigest).isEqualTo(jdkDigest);
    }

    @Test
    @DisplayName("JDK一致性 — SHA-512 与 JDK MessageDigest 结果一致")
    void jdkConsistency_sha512() throws Exception {
        byte[] data = "consistency-test-512".getBytes(StandardCharsets.UTF_8);
        byte[] ourDigest = shaProvider.digest(data, "SHA-512");
        byte[] jdkDigest = MessageDigest.getInstance("SHA-512").digest(data);
        assertThat(ourDigest).isEqualTo(jdkDigest);
    }

    // ===== 输出长度验证 =====

    @Test
    @DisplayName("SHA-256 输出长度 = 32 字节")
    void sha256_outputLength_shouldBe32Bytes() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        byte[] digest = shaProvider.digest(data, "SHA-256");
        assertThat(digest).hasSize(32);
    }

    @Test
    @DisplayName("SHA-384 输出长度 = 48 字节")
    void sha384_outputLength_shouldBe48Bytes() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        byte[] digest = shaProvider.digest(data, "SHA-384");
        assertThat(digest).hasSize(48);
    }

    @Test
    @DisplayName("SHA-512 输出长度 = 64 字节")
    void sha512_outputLength_shouldBe64Bytes() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        byte[] digest = shaProvider.digest(data, "SHA-512");
        assertThat(digest).hasSize(64);
    }

    @Test
    @DisplayName("getDigestLength — 返回正确长度")
    void getDigestLength_shouldReturnCorrectLength() {
        assertThat(shaProvider.getDigestLength("SHA-256")).isEqualTo(32);
        assertThat(shaProvider.getDigestLength("SHA-384")).isEqualTo(48);
        assertThat(shaProvider.getDigestLength("SHA-512")).isEqualTo(64);
    }

    // ===== 确定性 =====

    @Test
    @DisplayName("确定性 — 相同输入产生相同输出")
    void digest_shouldBeDeterministic() {
        byte[] data = "deterministic-test".getBytes(StandardCharsets.UTF_8);

        byte[] d1 = shaProvider.digest(data, "SHA-256");
        byte[] d2 = shaProvider.digest(data, "SHA-256");
        assertThat(d1).isEqualTo(d2);

        byte[] d3 = shaProvider.digest(data, "SHA-384");
        byte[] d4 = shaProvider.digest(data, "SHA-384");
        assertThat(d3).isEqualTo(d4);

        byte[] d5 = shaProvider.digest(data, "SHA-512");
        byte[] d6 = shaProvider.digest(data, "SHA-512");
        assertThat(d5).isEqualTo(d6);
    }

    @Test
    @DisplayName("雪崩效应 — 不同输入产生不同摘要")
    void digest_differentInput_shouldProduceDifferentDigest() {
        byte[] data1 = "input1".getBytes(StandardCharsets.UTF_8);
        byte[] data2 = "input2".getBytes(StandardCharsets.UTF_8);

        assertThat(shaProvider.digest(data1, "SHA-256")).isNotEqualTo(shaProvider.digest(data2, "SHA-256"));
        assertThat(shaProvider.digest(data1, "SHA-384")).isNotEqualTo(shaProvider.digest(data2, "SHA-384"));
        assertThat(shaProvider.digest(data1, "SHA-512")).isNotEqualTo(shaProvider.digest(data2, "SHA-512"));
    }

    @Test
    @DisplayName("默认算法 — digest(data) 使用 SHA-256")
    void digest_default_shouldUseSha256() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] defaultDigest = shaProvider.digest(data);
        byte[] sha256Digest = shaProvider.digest(data, "SHA-256");
        assertThat(defaultDigest).isEqualTo(sha256Digest);
    }

    // ===== 异常分支 =====

    @Test
    @DisplayName("digest — null入参抛CryptoException")
    void digest_nullInput_shouldThrow() {
        assertThatThrownBy(() -> shaProvider.digest((byte[]) null, "SHA-256"))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> shaProvider.digest((byte[]) null))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("digest — 不支持的算法抛CryptoException")
    void digest_unsupportedAlgorithm_shouldThrow() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> shaProvider.digest(data, "SHA-128"))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> shaProvider.digest(data, "MD5"))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("getDigestLength — 不支持的算法抛CryptoException")
    void getDigestLength_unsupported_shouldThrow() {
        assertThatThrownBy(() -> shaProvider.getDigestLength("SHA-128"))
                .isInstanceOf(CryptoException.class);
    }

    // ===== 算法名变体支持 =====

    @Test
    @DisplayName("算法名变体 — SHA256/SHA-256/sha-256 均可识别")
    void algorithmNameVariants_shouldWork() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] expected = shaProvider.digest(data, "SHA-256");

        assertThat(shaProvider.digest(data, "SHA256")).isEqualTo(expected);
        assertThat(shaProvider.digest(data, "sha-256")).isEqualTo(expected);
        assertThat(shaProvider.digest(data, "sha256")).isEqualTo(expected);
        assertThat(shaProvider.digest(data, "SHA_256")).isEqualTo(expected);
    }

    @Test
    @DisplayName("isSupported — 正确判断算法支持")
    void isSupported_shouldWork() {
        assertThat(shaProvider.isSupported("SHA-256")).isTrue();
        assertThat(shaProvider.isSupported("SHA-384")).isTrue();
        assertThat(shaProvider.isSupported("SHA-512")).isTrue();
        assertThat(shaProvider.isSupported("MD5")).isFalse();
        assertThat(shaProvider.isSupported(null)).isFalse();
        assertThat(shaProvider.isSupported("")).isFalse();
    }

    // ===== 流式更新 =====

    @Test
    @DisplayName("流式更新 — newDigest + update + digest 等价于一次性digest")
    void streaming_shouldEqualOneShot() {
        byte[] data = "streaming-test-data".getBytes(StandardCharsets.UTF_8);

        // 一次性
        byte[] oneShot = shaProvider.digest(data, "SHA-256");

        // 流式
        MessageDigest md = shaProvider.newDigest("SHA-256");
        int mid = data.length / 2;
        md.update(data, 0, mid);
        md.update(data, mid, data.length - mid);
        byte[] streaming = md.digest();

        assertThat(streaming).isEqualTo(oneShot);
    }

    @Test
    @DisplayName("流式更新 — chunks 数组方式")
    void streaming_chunks_shouldWork() {
        byte[] chunk1 = "chunk1-".getBytes(StandardCharsets.UTF_8);
        byte[] chunk2 = "chunk2-".getBytes(StandardCharsets.UTF_8);
        byte[] chunk3 = "chunk3".getBytes(StandardCharsets.UTF_8);
        byte[][] chunks = {chunk1, chunk2, chunk3};

        byte[] streamingDigest = shaProvider.digest(chunks, "SHA-256");
        byte[] oneShotDigest = shaProvider.digest("chunk1-chunk2-chunk3".getBytes(StandardCharsets.UTF_8), "SHA-256");

        assertThat(streamingDigest).isEqualTo(oneShotDigest);
    }

    @Test
    @DisplayName("newDigest — 不支持的算法抛CryptoException")
    void newDigest_unsupported_shouldThrow() {
        assertThatThrownBy(() -> shaProvider.newDigest("MD5"))
                .isInstanceOf(CryptoException.class);
    }

    @Test
    @DisplayName("digest(chunks) — null入参抛CryptoException")
    void digestChunks_null_shouldThrow() {
        assertThatThrownBy(() -> shaProvider.digest((byte[][]) null, "SHA-256"))
                .isInstanceOf(CryptoException.class);
    }

    // ===== digestHex =====

    @Test
    @DisplayName("digestHex — 返回正确长度 hex 字符串")
    void digestHex_shouldReturnCorrectLength() {
        byte[] data = "hex-test".getBytes(StandardCharsets.UTF_8);

        assertThat(shaProvider.digestHex(data, "SHA-256")).hasSize(64);
        assertThat(shaProvider.digestHex(data, "SHA-384")).hasSize(96);
        assertThat(shaProvider.digestHex(data, "SHA-512")).hasSize(128);
    }

    @Test
    @DisplayName("digestHex — 默认 SHA-256")
    void digestHex_default_shouldUseSha256() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        String hex = shaProvider.digestHex(data);
        assertThat(hex).hasSize(64);
        assertThat(hex).matches("[0-9a-f]{64}");
    }

    // ===== hex 工具方法 =====

    @Test
    @DisplayName("toHex/fromHex — 往返转换")
    void toHexFromHex_shouldRoundTrip() {
        byte[] original = {0x00, 0x01, (byte) 0xff, (byte) 0xab, (byte) 0xcd};
        String hex = SHAProvider.toHex(original);
        byte[] restored = SHAProvider.fromHex(hex);
        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("toHex(null) — 返回 \"null\"")
    void toHex_null_shouldReturnNullString() {
        assertThat(SHAProvider.toHex(null)).isEqualTo("null");
    }

    @Test
    @DisplayName("fromHex — 无效输入抛CryptoException")
    void fromHex_invalid_shouldThrow() {
        assertThatThrownBy(() -> SHAProvider.fromHex(null))
                .isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> SHAProvider.fromHex("abc"))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("Invalid hex string");
        assertThatThrownBy(() -> SHAProvider.fromHex("xyz0"))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("Invalid hex character");
    }

    // ===== 大数据测试 =====

    @Test
    @DisplayName("大数据 — 1MB 数据 SHA-256 摘要与 JDK 一致")
    void largeData_sha256_shouldMatchJdk() {
        byte[] data = new byte[1024 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i & 0xff);
        }

        byte[] ourDigest = shaProvider.digest(data, "SHA-256");
        assertThat(ourDigest).hasSize(32);

        try {
            byte[] jdkDigest = MessageDigest.getInstance("SHA-256").digest(data);
            assertThat(ourDigest).isEqualTo(jdkDigest);
        } catch (Exception e) {
            throw new CryptoException("JDK comparison failed", e);
        }
    }
}
