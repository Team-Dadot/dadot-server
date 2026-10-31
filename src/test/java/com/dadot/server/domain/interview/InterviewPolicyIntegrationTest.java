package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.repository.InterviewPolicyRepository;
import com.dadot.server.global.security.AccessTokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
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
import tools.jackson.databind.node.ObjectNode;

import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.dart.worker-enabled=false", "app.dart.originals.enabled=false",
        "app.quiz-generation.enabled=false", "app.quiz-generation.worker-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InterviewPolicyIntegrationTest {

    private static final String PATH = "/v1/admin/interview-policies";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired Clock clock;
    @Autowired AccessTokenIssuer tokens;
    @MockitoSpyBean InterviewPolicyRepository repository;

    private final List<Long> users = new ArrayList<>();
    private final ConcurrentSkipListSet<Long> policies = new ConcurrentSkipListSet<>();
    private long user;
    private long company;
    private long category;
    private long job;
    private String token;
    private boolean safe;

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        safe = true;
        reset(repository);
        users.clear();
        policies.clear();
        user = account("ADMIN");
        token = login(user);
        company = insert("insert into companies(dart_corp_code, name) values ('99004600', '정책테스트')");
        category = insert("insert into job_categories(name) values (?)", "정책-" + key());
        job = insert("insert into jobs(category_id, name) values (?, '정책직무')", category);
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;
        reset(repository);
        for (long id : policies) {
            jdbc.update("update interview_policies set active_version_no = null where id = ?", id);
            jdbc.update("delete c from evaluation_criteria c join interview_policy_versions v"
                    + " on v.id = c.policy_version_id where v.policy_id = ?", id);
            jdbc.update("delete from interview_policy_versions where policy_id = ?", id);
            jdbc.update("delete from interview_policies where id = ?", id);
        }
        for (long id : users) {
            jdbc.update("delete from admin_audit_events where actor_user_id = ?", id);
            jdbc.update("delete from api_idempotency_requests where user_id = ?", id);
            jdbc.update("delete from auth_sessions where user_id = ?", id);
            jdbc.update("delete from users where id = ?", id);
        }
        jdbc.update("delete from jobs where id = ?", job);
        jdbc.update("delete from job_categories where id = ?", category);
        jdbc.update("delete from companies where id = ?", company);
    }

    @Test
    void registersEvaluationAndPersistsExperienceOnlyConfig() throws Exception {
        JsonNode value = data(create(evaluation(), key(), token).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store")));
        assertEquals(4, value.get("criteria").size());
        assertEquals(InterviewPolicy.AGGREGATION, value.get("aggregationRule").asText());
        assertTrue(value.get("reviewNote").isNull());
        var config = mapper.readTree(jdbc.queryForObject(
                "select rule_config from interview_policy_versions where id = ?", String.class,
                value.get("policyVersionId").longValue()));
        assertEquals("EXPERIENCE", config.get("materialTypes").get(0).asText());
        assertEquals(1, config.get("materialTypes").size());
    }

    @Test
    void newContentVersionPreservesOldContentAndAdvancesManagementVersion() throws Exception {
        JsonNode first = draft();
        ObjectNode body = generation();
        body.put("sourcePolicyId", first.get("policyId").longValue()).put("rules", "새 지침");
        JsonNode next = data(create(body, key(), token).andExpect(status().isCreated()));
        assertEquals(first.get("policyId"), next.get("policyId"));
        assertEquals(2, next.get("version").intValue());
        assertEquals(2, scalar("select version_no from interview_policy_versions where id = ?",
                next.get("policyVersionId").longValue()));
        assertEquals("생성 지침", jdbc.queryForObject(
                "select prompt_template from interview_policy_versions where id = ?", String.class,
                first.get("policyVersionId").longValue()));
    }

    @Test
    void duplicateScopeRequiresSourceAndSourceMustMatch() throws Exception {
        JsonNode first = draft();
        error(create(generation(), key(), token), 409, "POLICY_ALREADY_EXISTS");
        ObjectNode changed = generation();
        changed.put("sourcePolicyId", first.get("policyId").longValue());
        ((ObjectNode) changed.get("scope")).put("jobId", job);
        error(create(changed, key(), token), 400, "INVALID_INPUT");
        assertEquals(1, scalar("select count(*) from interview_policy_versions where policy_id = ?",
                first.get("policyId").longValue()));
    }

    @Test
    void commonNullScopeIsUnique() throws Exception {
        ObjectNode common = generation();
        ((ObjectNode) common.get("scope")).putNull("companyId");
        create(common, key(), token).andExpect(status().isCreated());
        error(create(common, key(), token), 409, "POLICY_ALREADY_EXISTS");
    }

    @Test
    void replayReturnsOriginalSnapshotAndRejectsChangedOrExpiredBody() throws Exception {
        String key = key();
        ObjectNode body = generation();
        JsonNode first = data(create(body, key, token).andExpect(status().isCreated()));
        change(first, "IN_REVIEW");
        assertEquals(first, data(create(body, key, token).andExpect(status().isCreated())));
        ObjectNode changed = body.deepCopy();
        changed.put("rules", "변경");
        error(create(changed, key, token), 409, "IDEMPOTENCY_CONFLICT");
        changed = body.deepCopy();
        changed.putNull("sourcePolicyId");
        error(create(changed, key, token), 409, "IDEMPOTENCY_CONFLICT");
        jdbc.update("update api_idempotency_requests set created_at = '2000-01-01', expires_at = '2000-01-02'"
                + " where user_id = ?", user);
        error(create(body, key, token), 409, "IDEMPOTENCY_KEY_EXPIRED");
    }

    @Test
    void objectKeyOrderDoesNotChangeIdempotency() throws Exception {
        String key = key();
        JsonNode first = data(create(generation(), key, token));
        String reversed = "{\"aggregationRule\":null,\"criteria\":[],\"rules\":\"생성 지침\","
                + "\"scope\":{\"jobId\":null,\"companyId\":" + company + "},\"type\":\"GENERATION\"}";
        assertEquals(first, data(create(mapper.readTree(reversed), key, token).andExpect(status().isCreated())));
    }

    @Test
    void reviewPublicationSuspensionAndReReviewAreRequired() throws Exception {
        JsonNode value = draft();
        error(update(value, "PUBLISHED", token), 409, "INVALID_STATUS_TRANSITION");
        value = publish(value);
        long versionId = value.get("policyVersionId").longValue();
        value = change(value, "SUSPENDED");
        assertNull(jdbc.queryForObject("select active_version_no from interview_policies where id = ?",
                Integer.class, value.get("policyId").longValue()));
        error(update(value, "PUBLISHED", token), 409, "INVALID_STATUS_TRANSITION");
        value = publish(value);
        assertEquals("PUBLISHED", value.get("status").asText());
        assertNotNull(jdbc.queryForObject("select published_at from interview_policy_versions where id = ?",
                LocalDateTime.class, versionId));
    }

    @Test
    void newerPublicationSupersedesPreviousVersionAndRejectsOlderUnpublishedVersion() throws Exception {
        JsonNode old = draft();
        ObjectNode body = generation();
        body.put("sourcePolicyId", old.get("policyId").longValue()).put("rules", "두 번째");
        JsonNode newer = publish(data(create(body, key(), token)));
        old = refresh(old);
        old = change(change(old, "IN_REVIEW"), "APPROVED");
        error(update(old, "PUBLISHED", token), 409, "INVALID_STATUS_TRANSITION");
        newer = refresh(newer);
        body.put("rules", "세 번째");
        JsonNode third = publish(data(create(body, key(), token)));
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "select status from interview_policy_versions where id = ?", String.class,
                newer.get("policyVersionId").longValue()));
        assertEquals(3, scalar("select active_version_no from interview_policies where id = ?",
                third.get("policyId").longValue()));
    }

    @Test
    void rejectsStaleRevisionAndWrongParentVersion() throws Exception {
        JsonNode first = draft();
        change(first, "IN_REVIEW");
        error(update(first, "IN_REVIEW", token), 409, "VERSION_CONFLICT");
        JsonNode other = data(create(evaluation(), key(), token));
        ObjectNode body = patchBody(refresh(first), "APPROVED");
        body.put("policyVersionId", other.get("policyVersionId").longValue());
        mvc.perform(patch(PATH + "/" + first.get("policyId").longValue())
                .header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(body))).andExpect(status().isNotFound());
    }

    @Test
    void corruptEvaluationCannotBePublished() throws Exception {
        JsonNode value = data(create(evaluation(), key(), token));
        value = change(change(value, "IN_REVIEW"), "APPROVED");
        jdbc.update("delete from evaluation_criteria where policy_version_id = ? and position = 4",
                value.get("policyVersionId").longValue());
        error(update(value, "PUBLISHED", token), 409, "POLICY_NOT_CONFIGURED");
    }

    @Test
    void publicationHistorySurvivesSuspensionAndReReview() throws Exception {
        JsonNode older = draft();
        ObjectNode body = generation();
        body.put("sourcePolicyId", older.get("policyId").longValue());
        JsonNode newer = publish(data(create(body, key(), token)));
        newer = change(change(newer, "SUSPENDED"), "IN_REVIEW");
        older = change(change(refresh(older), "IN_REVIEW"), "APPROVED");
        error(update(older, "PUBLISHED", token), 409, "INVALID_STATUS_TRANSITION");
        assertNotNull(jdbc.queryForObject("select published_at from interview_policy_versions where id = ?",
                LocalDateTime.class, newer.get("policyVersionId").longValue()));
    }

    @Test
    void auditFailureRollsBackBusinessDataAndIdempotency() throws Exception {
        doThrow(new TransientDataAccessResourceException("test"))
                .when(repository).audit(anyLong(), anyString(), anyLong(), nullable(String.class), any());
        String key = key();
        error(create(generation(), key, token), 503, "SERVICE_UNAVAILABLE");
        assertEquals(0, scalar("select count(*) from interview_policies where company_id = ?", company));
        assertEquals(0, scalar("select count(*) from api_idempotency_requests where user_id = ?", user));
        reset(repository);
        create(generation(), key, token).andExpect(status().isCreated());
    }

    @Test
    void scopeFiltersAndPaginationUseContentVersions() throws Exception {
        JsonNode first = draft();
        ObjectNode body = generation();
        body.put("sourcePolicyId", first.get("policyId").longValue());
        JsonNode next = data(create(body, key(), token));
        jdbc.update("update interview_policy_versions set created_at = '2026-01-01' where policy_id = ?",
                first.get("policyId").longValue());
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token)
                        .param("companyId", Long.toString(company)).param("type", "GENERATION").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2)).andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.content[0].policyVersionId").value(next.get("policyVersionId").longValue()));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token)
                        .param("companyId", Long.toString(company)).param("page", "99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token).param("jobId", Long.toString(job)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void unauthenticatedNormalUserAndRevokedAdminCannotAccess() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        String normal = login(account("USER"));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + normal)).andExpect(status().isForbidden());
        error(create(generation(), key(), normal), 403, "FORBIDDEN");
        jdbc.update("update auth_sessions set revoked_at = current_timestamp(6) where user_id = ?", user);
        error(create(generation(), key(), token), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "companyId=0", "type=OTHER",
            "page=9999999999999999", "unknown=x", "page=1&page=2"})
    void invalidQueryIsRejected(String query) throws Exception {
        mvc.perform(get(PATH + "?" + query).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void databaseProtectsScopeKeyAndFixedCriterionPairs() throws Exception {
        JsonNode value = data(create(evaluation(), key(), token));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update interview_policies set policy_key = 'invalid' where id = ?",
                value.get("policyId").longValue()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update evaluation_criteria set max_score = 24 where policy_version_id = ?",
                value.get("policyVersionId").longValue()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update evaluation_criteria set name = '다른 항목' where policy_version_id = ? and position = 1",
                value.get("policyVersionId").longValue()));
    }

    @Test
    void concurrentSameKeyCreatesOneVersionAndOneAudit() throws Exception {
        String key = key();
        var values = concurrent(() -> data(create(generation(), key, token).andExpect(status().isCreated())),
                () -> data(create(generation(), key, token).andExpect(status().isCreated())));
        assertEquals(values.get(0), values.get(1));
        assertEquals(1, scalar("select count(*) from admin_audit_events where actor_user_id = ?", user));
    }

    @Test
    void concurrentDifferentAdminsCannotDuplicateCommonScope() throws Exception {
        String other = login(account("ADMIN"));
        ObjectNode common = generation();
        ((ObjectNode) common.get("scope")).putNull("companyId");
        var statuses = concurrent(() -> create(common, key(), token).andReturn().getResponse().getStatus(),
                () -> create(common, key(), other).andReturn().getResponse().getStatus());
        assertEquals(List.of(201, 409), statuses.stream().sorted().toList());
    }

    @Test
    void concurrentVersionRegistrationAllocatesConsecutiveNumbers() throws Exception {
        JsonNode first = draft();
        String other = login(account("ADMIN"));
        ObjectNode body = generation();
        body.put("sourcePolicyId", first.get("policyId").longValue());
        concurrent(() -> data(create(body, key(), token).andExpect(status().isCreated())),
                () -> data(create(body, key(), other).andExpect(status().isCreated())));
        assertEquals(List.of(1, 2, 3), jdbc.queryForList(
                "select version_no from interview_policy_versions where policy_id = ? order by version_no",
                Integer.class, first.get("policyId").longValue()));
    }

    @Test
    void concurrentPublicationAcceptsOnlyOneExpectedRevision() throws Exception {
        JsonNode first = change(change(draft(), "IN_REVIEW"), "APPROVED");
        ObjectNode body = generation();
        body.put("sourcePolicyId", first.get("policyId").longValue());
        JsonNode second = change(change(data(create(body, key(), token)), "IN_REVIEW"), "APPROVED");
        first = refresh(first);
        String other = login(account("ADMIN"));
        JsonNode target = first;
        var statuses = concurrent(() -> update(target, "PUBLISHED", token).andReturn().getResponse().getStatus(),
                () -> update(second, "PUBLISHED", other).andReturn().getResponse().getStatus());
        assertEquals(List.of(200, 409), statuses.stream().sorted().toList());
        assertEquals(1, scalar("select count(*) from interview_policy_versions where policy_id = ? and status = 'PUBLISHED'",
                first.get("policyId").longValue()));
    }

    private ObjectNode generation() {
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "GENERATION").put("rules", "생성 지침").putNull("aggregationRule");
        body.putObject("scope").put("companyId", company).putNull("jobId");
        body.putArray("criteria");
        return body;
    }

    private ObjectNode evaluation() {
        ObjectNode body = generation();
        body.put("type", "EVALUATION").put("aggregationRule", InterviewPolicy.AGGREGATION);
        var criteria = body.putArray("criteria");
        for (int i = 0; i < 4; i++) criteria.addObject().put("code", InterviewPolicy.CODES.get(i))
                .put("label", InterviewPolicy.LABELS.get(i)).put("maxScore", 25).put("description", "점수대별 기준");
        return body;
    }

    private ResultActions create(JsonNode body, String key, String credential) throws Exception {
        ResultActions result = mvc.perform(post(PATH).header("Authorization", "Bearer " + credential)
                .header("Idempotency-Key", key).contentType("application/json").content(mapper.writeValueAsString(body)));
        if (result.andReturn().getResponse().getStatus() == 201) policies.add(data(result).get("policyId").longValue());
        return result;
    }

    private JsonNode draft() throws Exception { return data(create(generation(), key(), token).andExpect(status().isCreated())); }
    private JsonNode change(JsonNode value, String status) throws Exception { return data(update(value, status, token).andExpect(status().isOk())); }
    private JsonNode publish(JsonNode value) throws Exception { return change(change(change(value, "IN_REVIEW"), "APPROVED"), "PUBLISHED"); }

    private ResultActions update(JsonNode value, String state, String credential) throws Exception {
        return mvc.perform(patch(PATH + "/" + value.get("policyId").longValue())
                .header("Authorization", "Bearer " + credential).contentType("application/json")
                .content(mapper.writeValueAsString(patchBody(value, state))));
    }

    private ObjectNode patchBody(JsonNode value, String state) {
        return mapper.createObjectNode().put("expectedVersion", value.get("version").longValue())
                .put("policyVersionId", value.get("policyVersionId").longValue()).put("status", state).put("reviewNote", "검수 사유");
    }

    private JsonNode refresh(JsonNode value) {
        ObjectNode copy = (ObjectNode) value.deepCopy();
        copy.put("version", scalar("select version from interview_policies where id = ?", value.get("policyId").longValue()));
        return copy;
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
    }

    private void error(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    private long account(String role) {
        long id = insert("insert into users(nickname, role) values ('정책테스트', ?)", role);
        users.add(id);
        return id;
    }

    private String login(long id) {
        String session = key();
        var issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var expires = issued.plusSeconds(3600);
        jdbc.update("insert into auth_sessions(id, user_id, created_at, expires_at) values (?, ?, ?, ?)",
                session, id, LocalDateTime.ofInstant(issued, ZoneOffset.UTC), LocalDateTime.ofInstant(expires, ZoneOffset.UTC));
        return tokens.issue(id, session, 0, issued, expires);
    }

    private long scalar(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private static String key() { return UUID.randomUUID().toString(); }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private static <T> List<T> concurrent(Callable<T> first, Callable<T> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return first.call(); });
            var b = executor.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return second.call(); });
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }
}