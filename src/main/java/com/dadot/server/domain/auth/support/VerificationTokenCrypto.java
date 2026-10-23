package com.dadot.server.domain.auth.support;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class VerificationTokenCrypto {

    public static final String KEY_VERSION = "v1";

    private static final int NONCE_BYTES = 12;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec encryptionKey;

    public VerificationTokenCrypto(
            @Value("${app.auth-proof.encryption-key-base64}")
            String encodedKey
    ) {
        byte[] decoded;

        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Auth proof key must be Base64"
            );
        }

        if (decoded.length != 32) {
            Arrays.fill(decoded, (byte) 0);

            throw new IllegalStateException(
                    "Auth proof key must be exactly 32 bytes"
            );
        }

        encryptionKey = new SecretKeySpec(decoded, "AES");
        Arrays.fill(decoded, (byte) 0);
    }

    public String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    public String tokenHash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public boolean matches(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    public String encrypt(String plainText, String context) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);

        try {
            Cipher cipher = cipher(
                    Cipher.ENCRYPT_MODE,
                    nonce,
                    context
            );

            byte[] encrypted = cipher.doFinal(
                    plainText.getBytes(StandardCharsets.UTF_8)
            );

            byte[] packed = new byte[nonce.length + encrypted.length];

            System.arraycopy(
                    nonce, 0,
                    packed, 0,
                    nonce.length
            );

            System.arraycopy(
                    encrypted, 0,
                    packed, nonce.length,
                    encrypted.length
            );

            return Base64.getEncoder().encodeToString(packed);

        } catch (GeneralSecurityException ex) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }

    public String decrypt(
            String encrypted,
            String version,
            String context
    ) {
        if (!KEY_VERSION.equals(version)) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }

        try {
            byte[] packed = Base64.getDecoder().decode(encrypted);

            if (packed.length < NONCE_BYTES + 16) {
                throw new IllegalArgumentException();
            }

            byte[] nonce = Arrays.copyOf(
                    packed,
                    NONCE_BYTES
            );

            byte[] plainText = cipher(
                    Cipher.DECRYPT_MODE,
                    nonce,
                    context
            ).doFinal(
                    packed,
                    NONCE_BYTES,
                    packed.length - NONCE_BYTES
            );

            return new String(
                    plainText,
                    StandardCharsets.UTF_8
            );

        } catch (
                GeneralSecurityException
                | IllegalArgumentException ex
        ) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }

    private Cipher cipher(
            int mode,
            byte[] nonce,
            String context
    ) throws GeneralSecurityException {

        Cipher cipher = Cipher.getInstance(
                "AES/GCM/NoPadding"
        );

        cipher.init(
                mode,
                encryptionKey,
                new GCMParameterSpec(128, nonce)
        );

        cipher.updateAAD(
                (KEY_VERSION + ":" + context)
                        .getBytes(StandardCharsets.UTF_8)
        );

        return cipher;
    }
}