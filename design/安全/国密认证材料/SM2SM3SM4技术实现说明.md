# SM2/SM3/SM4 技术实现说明

> 归属：多平台多租户大数据平台 · 国密认证材料
> 版本：v1.0 ｜ 日期：2026-08-17 ｜ 状态：已完成
> 关联：`design/安全/网络安全机制.md`；`design/安全/国密认证材料/国密合规性自检清单.md`
> 对标：GB/T 32918（SM2）｜ GB/T 32905（SM3）｜ GB/T 32907（SM4）｜ GB/T 38636（TLCP）｜ GM/T 0054-2018
> 优先级：P2（建议补齐）

---

## 1. 国密算法概述

### 1.1 算法清单

| 算法 | 标准 | 类型 | 用途 | 关键参数 |
| --- | --- | --- | --- | --- |
| SM2 | GB/T 32918 | 非对称 | 公钥加密、数字签名、密钥交换 | 256 位椭圆曲线 |
| SM3 | GB/T 32905 | 哈希 | 数据完整性、口令哈希、证书指纹 | 256 位摘要 |
| SM4 | GB/T 32907 | 对称 | 字段级加密、传输加密、存储加密 | 128 位分组 |
| TLCP | GB/T 38636 | 协议 | 传输安全（国密 TLS） | 双证书 |

### 1.2 启用环境

| 环境 | 国密启用 | 说明 |
| --- | --- | --- |
| 信创 | ✅ 强制全启用 | 全栈国密，禁用 RSA/AES/SHA-1 |
| 本地数据中心 | ✅ 推荐 | 按客户要求启用 |
| 公有云 VM | ⚠️ 按需 | 按客户要求启用 |
| 私有云 VM | ⚠️ 按需 | 按客户要求启用 |

---

## 2. SM2 技术实现

### 2.1 用途

- **数字签名**：证书签名、JWT 签名、API 请求签名。
- **公钥加密**：密钥包装、敏感数据加密。
- **密钥交换**：TLS 握手密钥协商。

### 2.2 实现方案

- **算法库**：BouncyCastle FIPS（Java）+ 国密 SDK（信创环境）。
- **曲线**：SM2 推荐曲线 sm2p256v1。
- **签名算法**：SM2withSM3。
- **证书**：商密 CA 签发的 SM2 证书，签名算法 SM2withSM3。

### 2.3 代码示例

```java
// 代码示例：SM2 签名与验签（Java）
import org.bouncycastle.crypto.signers.SM2Signer;
import org.bouncycastle.crypto.params.SM2PrivateKeyParameters;
import org.bouncycastle.crypto.params.SM2PublicKeyParameters;

// 生成 SM2 密钥对
KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
kpg.initialize(new ECNamedCurveGenParameterSpec("sm2p256v1"));
KeyPair keyPair = kpg.generateKeyPair();

// 签名
Signature signer = Signature.getInstance("SM3withSM2", "BC");
signer.initSign(keyPair.getPrivate());
signer.update(data);
byte[] signature = signer.sign();

// 验签
Signature verifier = Signature.getInstance("SM3withSM2", "BC");
verifier.initVerify(keyPair.getPublic());
verifier.update(data);
boolean valid = verifier.verify(signature);
```

### 2.4 互操作测试

- 与商密产品（如华为 GMSSL、信安世纪）互通测试通过。
- 与国密 CA 签发证书互通测试通过。
- 互通测试报告归档至 `design/安全/国密认证材料/`。

---

## 3. SM3 技术实现

### 3.1 用途

- **数据完整性**：文件哈希、表数据哈希校验。
- **口令哈希**：用户口令存储（加盐 + 迭代）。
- **证书指纹**：SM2 证书指纹。
- **消息认证**：HMAC-SM3。

### 3.2 实现方案

- **算法库**：BouncyCastle FIPS + 国密 SDK。
- **摘要长度**：256 位（32 字节）。
- **口令哈希**：SM3 + 加盐（16 字节）+ 迭代 ≥ 10000 次。

### 3.3 代码示例

```java
// 代码示例：SM3 哈希与口令哈希（Java）
import org.bouncycastle.crypto.digests.SM3Digest;

// SM3 哈希
SM3Digest digest = new SM3Digest();
digest.update(data, 0, data.length);
byte[] hash = new byte[digest.getDigestSize()];
digest.doFinal(hash, 0);

// 口令哈希（PBKDF2-SM3）
PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 10000, 256);
SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WITHSM3", "BC");
byte[] hashedPassword = skf.generateSecret(spec).getEncoded();
```

### 3.4 替代关系

| 场景 | 国际算法 | 国密替代 | 启用环境 |
| --- | --- | --- | --- |
| 数据完整性 | SHA-256 | SM3 | 信创强制 |
| 口令哈希 | PBKDF2-SHA256 | PBKDF2-SM3 | 信创强制 |
| 证书指纹 | SHA-256 | SM3 | 信创强制 |
| HMAC | HMAC-SHA256 | HMAC-SM3 | 信创强制 |

---

## 4. SM4 技术实现

### 4.1 用途

- **字段级加密**：敏感字段加密存储（手机号、身份证号、银行卡号）。
- **传输加密**：TLS 数据加密（配合 TLCP）。
- **存储加密**：备份文件加密、日志加密。
- **密钥包装**：包装会话密钥。

### 4.2 实现方案

- **算法库**：BouncyCastle FIPS + 国密 SDK。
- **分组长度**：128 位（16 字节）。
- **密钥长度**：128 位（16 字节）。
- **工作模式**：CBC、GCM（推荐 GCM，含认证）。
- **填充**：PKCS7Padding。
- **密钥管理**：KMS 托管，参见 `design/详细设计/多平台多租户大数据平台_安全脱敏详细设计_v0.1.md`。

### 4.3 代码示例

```java
// 代码示例：SM4-GCM 加密与解密（Java）
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.engines.SM4Engine;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

// SM4-GCM 加密
GCMBlockCipher cipher = new GCMBlockCipher(new SM4Engine());
cipher.init(true, new AEADParameters(new KeyParameter(key), 128, iv, null));
byte[] output = new byte[cipher.getOutputSize(plaintext.length)];
int len = cipher.processBytes(plaintext, 0, plaintext.length, output, 0);
cipher.doFinal(output, len);

// SM4-GCM 解密
cipher.init(false, new AEADParameters(new KeyParameter(key), 128, iv, null));
byte[] decrypted = new byte[cipher.getOutputSize(ciphertext.length)];
len = cipher.processBytes(ciphertext, 0, ciphertext.length, decrypted, 0);
cipher.doFinal(decrypted, len);
```

### 4.4 替代关系

| 场景 | 国际算法 | 国密替代 | 启用环境 |
| --- | --- | --- | --- |
| 字段级加密 | AES-256 | SM4 | 信创强制 |
| 传输加密 | AES-256 | SM4 | 信创强制 |
| 存储加密 | AES-256 | SM4 | 信创强制 |
| 密钥包装 | AES-256 | SM4 | 信创强制 |

---

## 5. TLCP 传输安全

### 5.1 协议概述

- TLCP（传输层密码协议）是国密 TLS 协议，标准 GB/T 38636。
- 使用双证书（签名证书 + 加密证书），均为 SM2 证书。
- 握手使用 SM2 密钥交换，传输使用 SM4 加密，摘要使用 SM3。

### 5.2 实现方案

- **服务端**：Ingress（Nginx/国产替代）启用 TLCP，配置双证书。
- **客户端**：浏览器/SDK 支持 TLCP，信创环境强制。
- **证书**：商密 CA 签发的双证书（签名证书 + 加密证书）。
- **降级**：非信创环境可降级为 TLS1.3。

### 5.3 配置示例

```nginx
# 配置示例：Nginx TLCP 配置
server {
    listen 443 ssl;
    server_name platform.example.com;

    # 国密双证书
    ssl_sign_certificate     /etc/nginx/ssl/sm2_sign.crt;
    ssl_sign_certificate_key /etc/nginx/ssl/sm2_sign.key;
    ssl_enc_certificate      /etc/nginx/ssl/sm2_enc.crt;
    ssl_enc_certificate_key  /etc/nginx/ssl/sm2_enc.key;

    # 启用 TLCP
    ssl_protocols TLCP;
    ssl_ciphers ECDHE-SM2-SM4-SM3;
    ssl_prefer_server_ciphers on;
}
```

---

## 6. 密钥管理

### 6.1 密钥层次

| 层次 | 密钥 | 用途 | 保护 |
| --- | --- | --- | --- |
| L0 | 主密钥（MK） | 加密 L1 密钥 | HSM/KMS 硬件保护 |
| L1 | 数据加密密钥（DEK） | 加密业务数据 | MK 加密 |
| L2 | 会话密钥 | 传输加密 | SM2 协商 |

### 6.2 密钥生命周期

- **生成**：KMS 生成，符合国密标准。
- **分发**：通过安全通道分发，禁止明文传输。
- **使用**：业务按需调用 KMS 接口，KMS 内部解密使用。
- **轮换**：DEK 每年轮换，MK 每 3 年轮换。
- **销毁**：销毁记录留档，密钥不可恢复。

### 6.3 KMS 选型

- 信创环境：国产 KMS（如华为 KMS、信安世纪）。
- 非信创环境：HashiCorp Vault 或云厂商 KMS。

---

## 7. 互操作与合规

### 7.1 互操作测试

- 与商密产品互通测试：SM2/SM3/SM4 + TLCP。
- 与国密 CA 互通测试：证书签发、验证。
- 互通测试报告归档至 `design/安全/国密认证材料/`。

### 7.2 合规自检

- 国密合规自检清单：参见 `design/安全/国密认证材料/国密合规性自检清单.md`。
- 密码产品检测报告：参见 `design/安全/国密认证材料/密码产品检测报告模板.md`。

---

## 8. 参考

- `design/安全/网络安全机制.md`：安全架构与国密落地。
- `design/安全/国密认证材料/国密合规性自检清单.md`：合规自检。
- `design/详细设计/多平台多租户大数据平台_安全脱敏详细设计_v0.1.md`：字段级加密。
- GB/T 32918（SM2）｜ GB/T 32905（SM3）｜ GB/T 32907（SM4）｜ GB/T 38636（TLCP）。
- GM/T 0054-2018《密码应用技术要求》。