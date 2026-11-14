package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.request.KakaoLoginRequest;
import com.dadot.server.domain.auth.dto.response.SocialLoginResponse;
import com.dadot.server.domain.auth.repository.SocialLoginRequestStore;
import com.dadot.server.domain.auth.repository.SocialLoginRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.infrastructure.kakao.KakaoClient;
import com.dadot.server.infrastructure.kakao.KakaoProfileClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
public class KakaoLoginService {

    public static final String UUID_PATTERN =
            "(?i)\\A[0-9a-f]{8}-[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{12}\\z";

    private final KakaoClient kakao;
    private final KakaoProfileClient profiles;
    private final SocialLoginRepository repository;
    private final SocialLoginIpLimiter limiter;
    private final SocialLoginRequestStore requests;
    private final SocialLoginTransaction transaction;

    @Transactional(
            propagation = Propagation.NOT_SUPPORTED
    )
    public SocialLoginResponse login(
            @NotNull
            @Valid
            KakaoLoginRequest request,

            @NotBlank
            @Pattern(regexp = UUID_PATTERN)
            String idempotencyKey,

            @NotBlank
            @Size(max = 64)
            String remoteAddress
    ) {
        try {
            limiter.consume(remoteAddress);

            var key = requests.key(
                    request.accessToken(),
                    idempotencyKey
            );

            var replay = transaction.replay(key);

            if (replay.isPresent()) {
                return replay.get();
            }

            // 외부 호출 중 DB 트랜잭션과 행 잠금을 유지하지 않는다.
            var verified = kakao.verify(
                    request.accessToken()
            );

            // 외부 프로필 조회는 트랜잭션 밖에서 신규 가입 대상에만 수행한다.
            // 실제 가입/로그인 판정은 아래 잠금 트랜잭션에서 다시 확인한다.
            String nickname = repository.findUser("KAKAO", verified.subject()).isEmpty()
                    ? profiles.nickname(request.accessToken(), verified.subject())
                    : null;

            return transaction.complete(
                    key,
                    verified,
                    nickname
            );

        } catch (
                TransientDataAccessException
                | DataAccessResourceFailureException
                | CannotCreateTransactionException ex
        ) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }
}
