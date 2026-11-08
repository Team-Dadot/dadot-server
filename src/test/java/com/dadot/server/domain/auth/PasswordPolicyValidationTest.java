package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.dto.request.EmailLoginRequest;
import com.dadot.server.domain.auth.dto.request.EmailSignupRequest;
import com.dadot.server.domain.auth.dto.request.PasswordChangeRequest;
import com.dadot.server.domain.auth.dto.request.PasswordResetRequest;
import com.dadot.server.domain.member.model.Member;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyValidationTest {
    private static final String EMAIL = "policy@example.invalid";
    private static final String TOKEN = "A".repeat(43);
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setup() {
        factory = Validation.byProvider(HibernateValidator.class)
                .configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void cleanup() {
        if (factory != null) factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Ab12345!",
            "Example123!",
            "ExampleNew123!",
            "Ab12345_",
            "Ab12345한",
            "Ab1234🙂"
    })
    void signupChangeAndResetAcceptMatchingPasswords(String password) {
        for (Object request : requests(password)) {
            assertTrue(validator.validate(request).isEmpty(),
                    request.getClass().getSimpleName());
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "Ab1234!",
            "Abcd1234",
            "1234567!",
            "Abcdefg!",
            "Ab1!a bc",
            " Ab12345!",
            "Ab12345! ",
            "Ab1!ab\tc",
            "Ab1!ab\nc",
            "Ab1!ab\u00a0c",
            "Ab1!ab\u200bc",
            "Ab1!ab\ufeffc"
    })
    void signupChangeAndResetRejectInvalidPasswords(String password) {
        for (Object request : requests(password)) {
            assertFalse(validator.validate(request).isEmpty(),
                    request.getClass().getSimpleName());
        }
    }

    @Test
    void exactly20Utf16CodeUnitsAreAcceptedAnd21AreRejected() {
        String ascii = "Ab1!" + "x".repeat(16);
        String emoji = "Ab1!" + "x".repeat(14) + "🙂";

        assertEquals(20, ascii.length());
        assertEquals(20, emoji.length());

        for (String password : List.of(ascii, emoji)) {
            for (Object request : requests(password)) {
                assertTrue(validator.validate(request).isEmpty());
            }
            for (Object request : requests(password + "x")) {
                assertFalse(validator.validate(request).isEmpty());
            }
        }
    }

    @Test
    void existingPasswordsRemainValidForLoginAndAccountDeletion() {
        String longLegacy = "Ab1" + "x".repeat(61);

        for (String password : List.of("Legacy123", longLegacy)) {
            assertTrue(validator.validate(new EmailLoginRequest(EMAIL, password)).isEmpty());
            assertDoesNotThrow(() -> new Member.Delete(
                    Member.Provider.EMAIL, password, null, null, null, null));
            for (Object request : requests(password)) {
                assertFalse(validator.validate(request).isEmpty());
            }
        }
    }

    private List<Object> requests(String password) {
        return List.of(
                new EmailSignupRequest(EMAIL, password, TOKEN, List.of(1L)),
                new PasswordChangeRequest(TOKEN, password),
                new PasswordResetRequest(TOKEN, password)
        );
    }
}