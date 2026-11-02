package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.dto.request.VerifyEmailVerificationRequest;
import com.dadot.server.domain.auth.dto.response.VerifyEmailVerificationResponse;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class VerificationTokenCryptoTest {

    private final VerificationTokenCrypto crypto = new VerificationTokenCrypto(
            Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void encryptionUsesFreshNoncesAndBindsResponseToRequest() {
        String first = crypto.encrypt("sensitive", "request-one");
        String second = crypto.encrypt("sensitive", "request-one");
        assertNotEquals(first, second);
        assertEquals("sensitive", crypto.decrypt(first, "v1", "request-one"));
        assertThrows(BusinessException.class, () -> crypto.decrypt(first, "v1", "request-two"));
        assertThrows(BusinessException.class, () -> crypto.decrypt(first, "v2", "request-one"));
        byte[] damaged = Base64.getDecoder().decode(first);
        damaged[damaged.length - 1] ^= 1;
        assertThrows(BusinessException.class, () -> crypto.decrypt(
                Base64.getEncoder().encodeToString(damaged), "v1", "request-one"));
    }

    @Test
    void invalidKeysFailAtStartup() {
        assertThrows(IllegalStateException.class, () -> new VerificationTokenCrypto("not-base64"));
        assertThrows(IllegalStateException.class, () -> new VerificationTokenCrypto(
                Base64.getEncoder().encodeToString(new byte[16])));
    }

    @Test
    void secretsAreRedactedFromDtoStrings() {
        assertFalse(new VerifyEmailVerificationRequest("123456").toString().contains("123456"));
        assertFalse(new VerifyEmailVerificationResponse("sensitive-token", Instant.EPOCH)
                .toString().contains("sensitive-token"));
    }
}
