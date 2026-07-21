package cn.datacraft.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class SecretCipher {
    private final String secret;

    public SecretCipher(@Value("${dataforge.crypto-secret}") String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("DATAFORGE_SECRET 至少需要 16 个字符");
        }
        this.secret = secret;
    }

    public String encrypt(String plain) {
        return encrypt(plain, secret);
    }

    public String decrypt(String encoded) {
        return decrypt(encoded, secret);
    }

    public String reencryptFrom(String encoded, String oldSecret) {
        if (encoded == null || encoded.trim().isEmpty()) return encoded;
        return encrypt(decrypt(encoded, oldSecret), secret);
    }

    public static String reencrypt(String encoded, String oldSecret, String newSecret) {
        if (encoded == null || encoded.trim().isEmpty()) return encoded;
        return encrypt(decrypt(encoded, oldSecret), newSecret);
    }

    static String encrypt(String plain, String secret) {
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(secret), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception exception) {
            throw new IllegalStateException("API Key 加密失败", exception);
        }
    }

    static String decrypt(String encoded, String secret) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length < 29) throw new IllegalArgumentException("密文长度不合法");
            byte[] nonce = Arrays.copyOfRange(combined, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(secret), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(Arrays.copyOfRange(combined, 12, combined.length)), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("API Key 解密失败，请确认 DATAFORGE_SECRET 未发生变化", exception);
        }
    }

    private static SecretKeySpec key(String secret) throws NoSuchAlgorithmException {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("加密密钥至少需要 16 个字符");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
