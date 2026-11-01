package com.dadot.server.domain.notification;

import com.dadot.server.domain.notification.model.NotificationSettings;
import com.dadot.server.domain.notification.repository.NotificationSettingsRepository;
import com.dadot.server.global.security.AccessTokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.account-deletion.worker-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationSettingsIntegrationTest {
    private static final String PATH = "/v1/users/me/notification-settings";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired AccessTokenIssuer tokens;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean NotificationSettingsRepository repository;
    private final List<Long> users = new ArrayList<>();
    private long user;
    private String token;
    private String session;
    private Instant now;
    private boolean safe;

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        safe = true;
        reset(clock, repository);
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        doReturn(now).when(clock).instant();
        users.clear();
        user = account();
        token = login(user);
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;
        reset(repository);
        for (long id : users) {
            for (String table : List.of("notification_settings", "user_onboarding",
                    "auth_sessions", "auth_identities")) {
                jdbc.update("delete from " + table + " where user_id = ?", id);
            }
            jdbc.update("delete from users where id = ?", id);
        }
        reset(clock);
    }

    @Test
    void getReturnsVirtualDefaultsWithoutWritingOrExposingDeviceFields() throws Exception {
        JsonNode body = data(read(token).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")));
        assertEquals(4, body.size());
        assertFalse(body.get("enabled").booleanValue());
        assertTrue(body.get("scheduledTime").isNull());
        assertEquals("Asia/Seoul", body.get("timeZone").asString());
        assertEquals(1, body.get("version").longValue());
        assertFalse(body.has("currentDevice"));
        assertFalse(body.has("deliveryPolicyStatus"));
        assertEquals(0, count());
    }

    @Test
    void saveDisableReenableClearAndNoChangePreserveContract() throws Exception {
        change(token, "{\"version\":1,\"enabled\":true,\"scheduledTime\":\"09:00\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        change(token, "{\"version\":2,\"enabled\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduledTime").value("09:00"))
                .andExpect(jsonPath("$.data.version").value(3));
        change(token, "{\"version\":3,\"enabled\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(4));
        change(token, "{\"version\":4,\"enabled\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(4));
        change(token, "{\"version\":4,\"scheduledTime\":null}")
                .andExpect(status().isBadRequest());
        assertEquals(4, storedVersion());
        change(token, "{\"version\":4,\"enabled\":false,\"scheduledTime\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduledTime").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.version").value(5));
        change(token, "{\"version\":5,\"enabled\":true}")
                .andExpect(status().isBadRequest());
        assertEquals(5, storedVersion());
    }

    @Test
    void firstNoChangeDoesNotCreateRowAndTimeZoneIsPreserved() throws Exception {
        change(token, "{\"version\":1,\"enabled\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        assertEquals(0, count());
        change(token, "{\"version\":1,\"scheduledTime\":\"23:59\",\"timeZone\":\"America/New_York\"}")
                .andExpect(status().isOk());
        assertEquals(2, storedVersion());
        assertEquals("23:59", data(read(token)).get("scheduledTime").asString());
        assertEquals("America/New_York", data(read(token)).get("timeZone").asString());
    }

    @Test
    void memberAndOnboardingReadTheSameSavedValues() throws Exception {
        change(token, "{\"version\":1,\"enabled\":true,\"scheduledTime\":\"00:00\",\"timeZone\":\"UTC\"}")
                .andExpect(status().isOk());
        for (String path : List.of("/v1/users/me", "/v1/users/me/onboarding")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.notificationSettings.enabled").value(true))
                    .andExpect(jsonPath("$.data.notificationSettings.scheduledTime").value("00:00"))
                    .andExpect(jsonPath("$.data.notificationSettings.timeZone").value("UTC"))
                    .andExpect(jsonPath("$.data.notificationSettings.version").value(2));
        }
    }

    @Test
    void oldVersionConflictsEvenWhenValuesAreEqualAndOtherAccountIsIndependent() throws Exception {
        change(token, "{\"version\":1,\"scheduledTime\":\"09:00\"}").andExpect(status().isOk());
        change(token, "{\"version\":1,\"scheduledTime\":\"09:00\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        String other = login(account());
        read(other).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        change(other, "{\"version\":1,\"scheduledTime\":\"18:00\"}").andExpect(status().isOk());
        read(token).andExpect(status().isOk()).andExpect(jsonPath("$.data.scheduledTime").value("09:00"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentChangesHaveOneWinnerIncludingFirstInsert(boolean existing) throws Exception {
        long version = 1;
        if (existing) {
            change(token, "{\"version\":1,\"scheduledTime\":\"08:00\"}").andExpect(status().isOk());
            version = 2;
        }
        final long expected = version;
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return change(token, "{\"version\":" + expected + ",\"scheduledTime\":\"09:00\"}")
                        .andReturn().getResponse().getStatus();
            });
            var second = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return change(token, "{\"version\":" + expected + ",\"scheduledTime\":\"10:00\"}")
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            var statuses = new ArrayList<>(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 409), statuses);
        }
        assertEquals(expected + 1, storedVersion());
        assertEquals(1, count());
    }

    @Test
    void storageFailureAfterWriteRollsBackAndRetryCanUseSameVersion() throws Exception {
        doAnswer(call -> {
            call.callRealMethod();
            throw new TransientDataAccessResourceException("private-SQL-marker");
        }).when(repository).save(anyLong(), any(), anyLong(), anyBoolean(), any());
        String body = "{\"version\":1,\"scheduledTime\":\"09:00\"}";
        change(token, body).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
        assertEquals(0, count());
        reset(repository);
        change(token, body).andExpect(status().isOk());
    }

    @Test
    void sessionInvalidatedDuringWriteRollsBackSettings() throws Exception {
        doAnswer(call -> {
            call.callRealMethod();
            jdbc.update("update auth_sessions set revoked_at = ? where id = ?", utc(now), session);
            return null;
        }).when(repository).save(anyLong(), any(), anyLong(), anyBoolean(), any());
        change(token, "{\"version\":1,\"scheduledTime\":\"09:00\"}")
                .andExpect(status().isUnauthorized());
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoked", "expired", "deleting", "authVersion"})
    void invalidAccountsCannotReadOrWrite(String kind) throws Exception {
        switch (kind) {
            case "revoked" -> jdbc.update("update auth_sessions set revoked_at = ? where id = ?", utc(now), session);
            case "expired" -> doReturn(now.plusSeconds(3601)).when(clock).instant();
            case "deleting" -> jdbc.update("update users set status = 'DELETING' where id = ?", user);
            case "authVersion" -> jdbc.update("update users set auth_version = auth_version + 1 where id = ?", user);
        }
        read(token).andExpect(status().isUnauthorized());
        change(token, "{\"version\":1,\"enabled\":false}").andExpect(status().isUnauthorized());
        assertEquals(0, count());
    }

    @Test
    void authenticationAndTransportAreStrict() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(patch(PATH).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token).content("{}"))
                .andExpect(status().isBadRequest());
        for (String query : List.of("?installationId=" + UUID.randomUUID(), "?userId=" + user, "?x=1&x=2")) {
            mvc.perform(get(PATH + query).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
            mvc.perform(patch(PATH + query).header("Authorization", "Bearer " + token)
                            .contentType("application/json").content("{\"version\":1,\"enabled\":false}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(patch(PATH).header("Authorization", "Bearer " + token)
                        .contentType("text/plain").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        change(token, " ".repeat(4097)).andExpect(status().isContentTooLarge());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "[]", "{}", "{", "{\"version\":1}",
            "{\"version\":1,\"enabled\":true}",
            "{\"version\":1,\"enabled\":null}",
            "{\"version\":1,\"enabled\":false,\"enabled\":true}",
            "{\"version\":1,\"enabled\":false} {}",
            "{\"version\":1,\"device\":{}}",
            "{\"version\":1,\"pushToken\":\"secret\"}",
            "{\"version\":1,\"userId\":1,\"enabled\":false}"
    })
    void invalidRequestsDoNotWrite(String body) throws Exception {
        change(token, body).andExpect(status().isBadRequest());
        assertEquals(0, count());
    }

    @Test
    void versionNeverOverflowsSafeInteger() throws Exception {
        jdbc.update("insert into notification_settings(user_id, version) values (?, ?)",
                user, NotificationSettings.MAX_VERSION);
        change(token, "{\"version\":" + NotificationSettings.MAX_VERSION + ",\"enabled\":false}")
                .andExpect(status().isOk());
        change(token, "{\"version\":" + NotificationSettings.MAX_VERSION + ",\"scheduledTime\":\"09:00\"}")
                .andExpect(status().isServiceUnavailable());
        assertEquals(NotificationSettings.MAX_VERSION, storedVersion());
    }

    private long account() {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> connection.prepareStatement(
                "insert into users(nickname) values ('알림테스트')", Statement.RETURN_GENERATED_KEYS), holder);
        long id = Objects.requireNonNull(holder.getKey()).longValue();
        users.add(id);
        jdbc.update("insert into auth_identities(user_id, provider, provider_subject) values (?, 'KAKAO', ?)",
                id, UUID.randomUUID().toString());
        jdbc.update("insert into user_onboarding(user_id) values (?)", id);
        return id;
    }

    private String login(long id) {
        session = UUID.randomUUID().toString();
        jdbc.update("insert into auth_sessions(id, user_id, created_at, expires_at) values (?, ?, ?, ?)",
                session, id, utc(now), utc(now.plusSeconds(3600)));
        return tokens.issue(id, session, 0, now, now.plusSeconds(3600));
    }

    private ResultActions read(String bearer) throws Exception {
        return mvc.perform(get(PATH).header("Authorization", "Bearer " + bearer));
    }

    private ResultActions change(String bearer, String body) throws Exception {
        return mvc.perform(patch(PATH).header("Authorization", "Bearer " + bearer)
                .contentType("application/json").content(body));
    }

    private JsonNode data(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsByteArray()).get("data");
    }

    private long count() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) from notification_settings where user_id = ?", Long.class, user));
    }

    private long storedVersion() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "select version from notification_settings where user_id = ?", Long.class, user));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}