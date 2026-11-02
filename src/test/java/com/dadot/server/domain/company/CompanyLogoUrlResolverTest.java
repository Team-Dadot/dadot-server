package com.dadot.server.domain.company;

import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CompanyLogoUrlResolverTest {

    @Test
    void absentLogoRemainsNullWithoutStorageConfiguration() {
        assertNull(
                new CompanyLogoUrlResolver("").resolve(null)
        );
    }

    @Test
    void joinsAndEncodesObjectKeySegments() {
        var resolver = new CompanyLogoUrlResolver(
                "https://cdn.example.invalid/assets/"
        );

        assertEquals(
                "https://cdn.example.invalid/assets/company/"
                        + "%EC%82%BC%EC%84%B1%20%EB%A1%9C%EA%B3%A0.png",
                resolver.resolve("company/삼성 로고.png")
        );

        assertEquals(
                "https://cdn.example.invalid/assets/company/a%3Fb%23c.png",
                resolver.resolve("company/a?b#c.png")
        );
    }

    @Test
    void doesNotTurnAnObjectKeyIntoAnotherHostOrDecodedTraversal() {
        var resolver = new CompanyLogoUrlResolver(
                "https://cdn.example.invalid/assets"
        );

        assertEquals(
                "https://cdn.example.invalid/assets/%252e%252e/logo.png",
                resolver.resolve("%2e%2e/logo.png")
        );

        assertThrows(
                IllegalStateException.class,
                () -> resolver.resolve(
                        "https://other.example.invalid/logo.png"
                )
        );
    }

    @Test
    void configuredLogoWithoutStorageBaseIsUnavailable() {
        BusinessException error = assertThrows(
                BusinessException.class,
                () -> new CompanyLogoUrlResolver("")
                        .resolve("company/logo.png")
        );

        assertEquals(
                ErrorCode.SERVICE_UNAVAILABLE,
                error.getErrorCode()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://cdn.example.invalid",
            "//cdn.example.invalid",
            "/assets",
            "https://user:password@cdn.example.invalid",
            "https://cdn.example.invalid?token=value",
            "https://cdn.example.invalid#fragment"
    })
    void rejectsUnsafeBaseUrls(String baseUrl) {
        assertThrows(
                IllegalStateException.class,
                () -> new CompanyLogoUrlResolver(baseUrl)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "/logo.png",
            "../logo.png",
            "company/./logo.png",
            "company//logo.png",
            "company/",
            "company\\logo.png"
    })
    void rejectsInvalidObjectKeys(String key) {
        var resolver = new CompanyLogoUrlResolver(
                "https://cdn.example.invalid"
        );

        assertThrows(
                IllegalStateException.class,
                () -> resolver.resolve(key)
        );
    }
}