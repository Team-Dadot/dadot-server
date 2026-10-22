package com.dadot.server.domain.auth.support;

import com.dadot.server.global.properties.EmailVerificationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

@Component
public class VerificationCrypto {

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public VerificationCrypto(EmailVerificationProperties properties) {
        byte[] bytes;

        try {
            bytes = Base64.getDecoder()
                    .decode(properties.hmacSecretBase64());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Verification HMAC key must be Base64"
            );
        }

        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "Verification HMAC key must be at least 32 bytes"
            );
        }

        key = new SecretKeySpec(bytes, "HmacSHA256");
        Arrays.fill(bytes, (byte) 0);
    }

    public String generateCode() {
        return String.format(
                Locale.ROOT,
                "%06d",
                random.nextInt(1_000_000)
        );
    }

    public String hmac(String... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);

            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);

                mac.update(
                        ByteBuffer.allocate(4)
                                .putInt(bytes.length)
                                .array()
                );
                mac.update(bytes);
            }

            return HexFormat.of().formatHex(mac.doFinal());

        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC unavailable");
        }
    }

    public String codeDigest(
            String id,
            String email,
            String purpose,
            String code
    ) {
        return hmac(
                "verification-code-v1",
                id,
                email,
                purpose,
                code
        );
    }
}