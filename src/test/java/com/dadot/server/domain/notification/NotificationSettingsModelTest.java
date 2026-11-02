package com.dadot.server.domain.notification;

import com.dadot.server.domain.notification.model.NotificationSettings.Patch;
import com.dadot.server.domain.notification.model.NotificationSettings.Settings;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class NotificationSettingsModelTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"enabled\":false}",
            "{\"version\":null,\"enabled\":false}",
            "{\"version\":\"1\",\"enabled\":false}",
            "{\"version\":1.0,\"enabled\":false}",
            "{\"version\":true,\"enabled\":false}",
            "{\"version\":0,\"enabled\":false}",
            "{\"version\":-1,\"enabled\":false}",
            "{\"version\":9007199254740992,\"enabled\":false}",
            "{\"version\":9223372036854775808,\"enabled\":false}",
            "{\"version\":1,\"enabled\":\"true\"}",
            "{\"version\":1,\"enabled\":1}",
            "{\"version\":1,\"timeZone\":null}",
            "{\"version\":1,\"timeZone\":\"\"}",
            "{\"version\":1,\"timeZone\":\" Asia/Seoul\"}",
            "{\"version\":1,\"timeZone\":\"+09:00\"}",
            "{\"version\":1,\"timeZone\":\"Mars/Base\"}",
            "{\"version\":1,\"timeZone\":9}",
            "{\"version\":1,\"scheduledTime\":9}",
            "{\"version\":1,\"scheduledTime\":\"9:00\"}",
            "{\"version\":1,\"scheduledTime\":\"24:00\"}",
            "{\"version\":1,\"scheduledTime\":\"23:60\"}",
            "{\"version\":1,\"scheduledTime\":\"09:00:00\"}",
            "{\"version\":1,\"scheduledTime\":\"09:00Z\"}",
            "{\"version\":1,\"scheduledTime\":\"09:00 \"}",
            "{\"version\":1,\"scheduledTime\":\"０９:００\"}"
    })
    void rejectsInvalidTypesRangesAndFormats(String value) {
        assertEquals(ErrorCode.INVALID_INPUT, assertThrows(BusinessException.class,
                () -> Patch.parse(json.readTree(value))).getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"00:00", "09:00", "23:59"})
    void validMinuteTimesCanEnableNotifications(String time) {
        var patch = Patch.parse(json.readTree(
                "{\"version\":1,\"enabled\":true,\"scheduledTime\":\"" + time + "\"}"));
        assertEquals(new Settings(true, time, "Asia/Seoul", 1), patch.apply(Settings.defaults()));
    }

    @Test
    void omissionAndExplicitNullHaveDifferentMeanings() {
        var stored = new Settings(false, "09:00", "UTC", 3);
        var omitted = Patch.parse(json.readTree("{\"version\":3,\"enabled\":false}"));
        var cleared = Patch.parse(json.readTree("{\"version\":3,\"scheduledTime\":null}"));
        assertEquals(stored, omitted.apply(stored));
        assertEquals(new Settings(false, null, "UTC", 3), cleared.apply(stored));
    }

    @Test
    void directConstructionCannotBypassValidation() {
        assertThrows(BusinessException.class, () -> new Patch(0, false, false, null, null));
        assertThrows(BusinessException.class, () -> new Patch(1, null, false, null, null));
        assertThrows(BusinessException.class, () -> new Patch(1, false, false, LocalTime.NOON, null));
        assertThrows(BusinessException.class, () -> new Patch(1, false, true, LocalTime.of(9, 0, 1), null));
        assertThrows(BusinessException.class, () -> new Patch(1, false, false, null, "+09:00"));
    }
}