package com.bemodel.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感字段加解密（AES-256-GCM）：密钥读 env APP_SECRET_KEY，
 * 缺失时用固定 dev key 并告警（仅演示环境）。密文格式 ENC:Base64(iv‖密文)。
 * decrypt 对非 ENC: 前缀的明文原样返回（兼容历史数据）。
 */
@Slf4j
@Component
public class CryptoService {

    public static final String PREFIX = "ENC:";
    private static final String DEV_KEY = "bemodel-dev-app-secret-do-not-use-in-prod";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(@Value("${app.secret-key:${APP_SECRET_KEY:}}") String secret) throws Exception {
        String s = (secret == null || secret.isBlank()) ? DEV_KEY : secret;
        if (DEV_KEY.equals(s)) {
            log.warn("APP_SECRET_KEY 未配置，使用内置开发密钥（仅限演示环境）");
        }
        // 任意长度口令 → 32 字节 AES-256 密钥（SHA-256 派生，跨重启确定）
        this.key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8)), "AES");
    }

    public String encrypt(String plain) {
        if (plain == null || plain.startsWith(PREFIX)) {
            return plain;
        }
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密；明文（无 ENC: 前缀）原样返回 */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[12];
            System.arraycopy(raw, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败（密钥不匹配或数据损坏）: " + e.getMessage(), e);
        }
    }
}
