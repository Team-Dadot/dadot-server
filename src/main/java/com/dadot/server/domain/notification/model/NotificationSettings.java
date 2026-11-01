package com.dadot.server.domain.notification.model;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;

public final class NotificationSettings {
    public static final long MAX_VERSION = 9_007_199_254_740_991L;
    private static final Set<String> FIELDS =
            Set.of("version", "enabled", "scheduledTime", "timeZone");
    private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private NotificationSettings() {}

    public record Settings(
            boolean enabled,
            String scheduledTime,
            String timeZone,
            long version
    ) {
        public static Settings defaults() {
            return new Settings(false, null, "Asia/Seoul", 1);
        }
    }

    public record Patch(
            long version,
            Boolean enabled,
            boolean timeSpecified,
            LocalTime scheduledTime,
            String timeZone
    ) {
        public Patch {
            if (version < 1 || version > MAX_VERSION
                    || enabled == null && !timeSpecified && timeZone == null
                    || !timeSpecified && scheduledTime != null
                    || scheduledTime != null
                    && (scheduledTime.getSecond() != 0 || scheduledTime.getNano() != 0)
                    || timeZone != null
                    && (timeZone.length() > 64 || !ZONES.contains(timeZone))) {
                throw invalid();
            }
        }

        public static Patch parse(JsonNode body) {
            if (body == null || !body.isObject()
                    || !FIELDS.containsAll(body.propertyNames())) {
                throw invalid();
            }

            JsonNode version = body.get("version");
            if (version == null || !version.isIntegralNumber() || !version.canConvertToLong()) {
                throw invalid();
            }

            Boolean enabled = null;
            if (body.has("enabled")) {
                if (!body.get("enabled").isBoolean()) throw invalid();
                enabled = body.get("enabled").booleanValue();
            }

            LocalTime time = null;
            if (body.has("scheduledTime") && !body.get("scheduledTime").isNull()) {
                String value = text(body.get("scheduledTime"));
                if (!value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) throw invalid();
                time = LocalTime.parse(value, TIME);
            }

            return new Patch(
                    version.longValue(), enabled, body.has("scheduledTime"), time,
                    body.has("timeZone") ? text(body.get("timeZone")) : null
            );
        }

        public Settings apply(Settings current) {
            boolean nextEnabled = enabled == null ? current.enabled() : enabled;
            String nextTime = timeSpecified
                    ? scheduledTime == null ? null : scheduledTime.format(TIME)
                    : current.scheduledTime();

            if (nextEnabled && nextTime == null) throw invalid();

            return new Settings(
                    nextEnabled, nextTime,
                    timeZone == null ? current.timeZone() : timeZone,
                    current.version()
            );
        }
    }

    public static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_INPUT);
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isString()) throw invalid();
        return node.asString();
    }
}