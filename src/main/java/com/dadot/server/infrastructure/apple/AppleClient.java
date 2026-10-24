package com.dadot.server.infrastructure.apple;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

@Component
public class AppleClient {

    static final String ISSUER = "https://appleid.apple.com";
    static final String KEYS_URL = ISSUER + "/auth/keys";
    static final String TOKEN_URL = ISSUER + "/auth/token";

    private static final int MAX_BYTES = 65_536;

    private final String teamId;
    private final String clientId;
    private final String keyId;
    private final String privateKeyPath;
    private final RestClient client;
    private final JsonMapper mapper;
    private final Clock clock;

    private volatile ECPrivateKey signingKey;

    private JWKSet cachedKeys;
    private Instant keysExpireAt = Instant.EPOCH;
    private Instant refreshAfter = Instant.EPOCH;

    @Autowired
    public AppleClient(
            @Value("${app.apple.team-id:}") String teamId,
            @Value("${app.apple.client-id:}") String clientId,
            @Value("${app.apple.key-id:}") String keyId,
            @Value("${app.apple.private-key-path:}") String privateKeyPath,
            JsonMapper mapper,
            Clock clock
    ) {
        this(
                teamId,
                clientId,
                keyId,
                privateKeyPath,
                createClient(),
                mapper,
                clock
        );
    }

    AppleClient(
            String teamId,
            String clientId,
            String keyId,
            String privateKeyPath,
            RestClient client,
            JsonMapper mapper,
            Clock clock
    ) {
        this.teamId = teamId;
        this.clientId = clientId;
        this.keyId = keyId;
        this.privateKeyPath = privateKeyPath;
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void checkConfigured() {
        if (!teamId.matches("[A-Z0-9]{10}")
                || !keyId.matches("[A-Z0-9]{10}")
                || clientId.isBlank()
                || clientId.length() > 255
                || privateKeyPath.isBlank()) {
            throw unavailable();
        }

        privateKey();
    }

    public Verified verifyIdentity(
            String token,
            String rawNonce
    ) {
        checkConfigured();

        try {
            if (token == null || token.length() > 8192) {
                throw invalid();
            }

            SignedJWT jwt = SignedJWT.parse(token);
            var header = jwt.getHeader();

            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())
                    || header.getKeyID() == null
                    || header.getKeyID().length() > 128
                    || header.getKeyID().isBlank()
                    || (
                    header.getCriticalParams() != null
                            && !header.getCriticalParams().isEmpty()
            )) {
                throw invalid();
            }

            if (!jwt.verify(
                    new RSASSAVerifier(
                            publicKey(header.getKeyID()).toRSAPublicKey()
                    )
            )) {
                throw invalid();
            }

            var claims = jwt.getJWTClaimsSet();
            Instant now = clock.instant();

            if (!ISSUER.equals(claims.getIssuer())
                    || !List.of(clientId).equals(claims.getAudience())
                    || claims.getExpirationTime() == null
                    || claims.getIssueTime() == null
                    || !claims.getExpirationTime().toInstant().isAfter(now)
                    || claims.getIssueTime().toInstant()
                    .isAfter(now.plusSeconds(30))
                    || !claims.getExpirationTime().after(claims.getIssueTime())
                    || (
                    claims.getNotBeforeTime() != null
                            && claims.getNotBeforeTime().toInstant().isAfter(now)
            )
                    || claims.getSubject() == null
                    || claims.getSubject().isBlank()
                    || claims.getSubject().length() > 255
                    || !matches(
                    sha256(rawNonce),
                    claims.getStringClaim("nonce")
            )) {
                throw invalid();
            }

            Object verifiedEmail = claims.getClaim("email_verified");
            String email = null;

            if (Boolean.TRUE.equals(verifiedEmail)
                    || "true".equals(verifiedEmail)) {
                email = claims.getStringClaim("email");

                if (email != null
                        && (email.isBlank() || email.length() > 254)) {
                    throw invalid();
                }
            }

            return new Verified(
                    claims.getSubject(),
                    claims.getExpirationTime().toInstant(),
                    email
            );

        } catch (
                ParseException
                | JOSEException
                | IllegalArgumentException ex
        ) {
            throw invalid();
        }
    }

    public Verified exchange(
            String code,
            String rawNonce,
            Verified original
    ) {
        checkConfigured();

        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret());
        form.add("code", code);
        form.add("grant_type", "authorization_code");

        try {
            Verified exchanged = client.post()
                    .uri(TOKEN_URL)
                    .contentType(
                            MediaType.APPLICATION_FORM_URLENCODED
                    )
                    .body(form)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();

                        if (status != 200 && status != 400) {
                            throw unavailable();
                        }

                        JsonNode body = mapper.readTree(
                                read(response.getBody())
                        );

                        if (body == null || !body.isObject()) {
                            throw unavailable();
                        }

                        if (status == 400) {
                            if ("invalid_grant".equals(
                                    body.path("error").asString("")
                            )) {
                                throw invalid();
                            }

                            throw unavailable();
                        }

                        var idToken = body.get("id_token");

                        if (idToken == null || !idToken.isString()) {
                            throw unavailable();
                        }

                        return verifyIdentity(
                                idToken.asString(),
                                rawNonce
                        );
                    });

            if (exchanged == null
                    || !matches(original.subject(), exchanged.subject())
                    || !original.validUntil().isAfter(clock.instant())) {
                throw invalid();
            }

            Instant validUntil =
                    original.validUntil().isBefore(exchanged.validUntil())
                            ? original.validUntil()
                            : exchanged.validUntil();

            return new Verified(
                    exchanged.subject(),
                    validUntil,
                    exchanged.email()
            );

        } catch (RestClientException | JacksonException ex) {
            throw unavailable();
        }
    }

    private synchronized RSAKey publicKey(String kid) {
        Instant now = clock.instant();

        boolean fresh =
                cachedKeys != null && keysExpireAt.isAfter(now);

        if (fresh
                && cachedKeys.getKeyByKeyId(kid) instanceof RSAKey rsa) {
            return checkKey(rsa);
        }

        if (refreshAfter.isAfter(now)) {
            throw fresh ? invalid() : unavailable();
        }

        // 알 수 없는 kid로 공개 키 서버를 반복 호출하지 않도록 제한한다.
        refreshAfter = now.plusSeconds(60);

        try {
            JWKSet fetched = client.get()
                    .uri(KEYS_URL)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 200) {
                            throw unavailable();
                        }

                        try {
                            return JWKSet.parse(
                                    new String(
                                            read(response.getBody()),
                                            StandardCharsets.UTF_8
                                    )
                            );
                        } catch (ParseException ex) {
                            throw unavailable();
                        }
                    });

            if (fetched == null
                    || fetched.getKeys().isEmpty()
                    || fetched.getKeys().size() > 20) {
                throw unavailable();
            }

            cachedKeys = fetched;
            keysExpireAt = now.plusSeconds(3600);

        } catch (RestClientException ex) {
            throw unavailable();
        }

        if (!(cachedKeys.getKeyByKeyId(kid) instanceof RSAKey rsa)) {
            throw invalid();
        }

        return checkKey(rsa);
    }

    private RSAKey checkKey(RSAKey key) {
        if (key.isPrivate()
                || key.size() < 2048
                || (
                key.getAlgorithm() != null
                        && !JWSAlgorithm.RS256.equals(key.getAlgorithm())
        )
                || (
                key.getKeyUse() != null
                        && !"sig".equals(key.getKeyUse().identifier())
        )) {
            throw invalid();
        }

        return key;
    }

    private String clientSecret() {
        Instant now = clock.instant();

        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .keyID(keyId)
                        .build(),
                new JWTClaimsSet.Builder()
                        .issuer(teamId)
                        .subject(clientId)
                        .audience(ISSUER)
                        .issueTime(Date.from(now))
                        .expirationTime(
                                Date.from(now.plusSeconds(300))
                        )
                        .build()
        );

        try {
            jwt.sign(new ECDSASigner(privateKey()));
            return jwt.serialize();

        } catch (JOSEException ex) {
            throw unavailable();
        }
    }

    private synchronized ECPrivateKey privateKey() {
        if (signingKey != null) {
            return signingKey;
        }

        byte[] decoded = null;

        try (var input = Files.newInputStream(Path.of(privateKeyPath))) {
            byte[] bytes = input.readNBytes(16_385);

            if (bytes.length > 16_384) {
                throw unavailable();
            }

            String pem = new String(
                    bytes,
                    StandardCharsets.US_ASCII
            ).strip();

            Arrays.fill(bytes, (byte) 0);

            String begin = "-----BEGIN PRIVATE KEY-----";
            String end = "-----END PRIVATE KEY-----";

            if (!pem.startsWith(begin) || !pem.endsWith(end)) {
                throw unavailable();
            }

            decoded = Base64.getDecoder().decode(
                    pem.substring(
                            begin.length(),
                            pem.length() - end.length()
                    ).replaceAll("\\s", "")
            );

            var key = KeyFactory.getInstance("EC")
                    .generatePrivate(
                            new PKCS8EncodedKeySpec(decoded)
                    );

            if (!(key instanceof ECPrivateKey ec)
                    || !ec.getParams().getOrder().equals(
                    new java.math.BigInteger(
                            "FFFFFFFF00000000FFFFFFFFFFFFFFFF"
                                    + "BCE6FAADA7179E84F3B9CAC2FC632551",
                            16
                    )
            )) {
                throw unavailable();
            }

            signingKey = ec;
            return ec;

        } catch (
                IOException
                | GeneralSecurityException
                | IllegalArgumentException ex
        ) {
            throw unavailable();

        } finally {
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    value.getBytes(
                                            StandardCharsets.US_ASCII
                                    )
                            )
            );
        } catch (GeneralSecurityException ex) {
            throw unavailable();
        }
    }

    private static boolean matches(String left, String right) {
        return left != null
                && right != null
                && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static byte[] read(
            java.io.InputStream input
    ) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);

        if (bytes.length > MAX_BYTES) {
            throw unavailable();
        }

        return bytes;
    }

    private static RestClient createClient() {
        var http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    private static BusinessException invalid() {
        return new BusinessException(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL
        );
    }

    private static BusinessException unavailable() {
        return new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE
        );
    }

    public record Verified(
            String subject,
            Instant validUntil,
            String email
    ) {
        public Verified {
            if (subject == null
                    || subject.isBlank()
                    || subject.length() > 255
                    || validUntil == null) {
                throw new IllegalArgumentException(
                        "Invalid verified Apple identity"
                );
            }
        }

        @Override
        public String toString() {
            return "Verified[identity=REDACTED]";
        }
    }
}