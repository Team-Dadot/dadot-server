# 소셜 가입 닉네임

신규 소셜 회원은 제공받은 이름을 최초 닉네임으로 저장한다. 이메일 가입과 기존 회원의 닉네임은 변경하지 않는다. 이후 로그인 시에는 이름을 덮어쓰지 않는다. 기존 닉네임 수정 API의 권한·동작을 변경하는 작업은 포함하지 않는다.

## 카카오

`POST /v1/auth/social/kakao`의 요청·응답 형식은 유지한다.

서버는 기존 액세스 토큰 검증으로 앱 ID·회원번호·유효기간을 확인한 뒤, 아직 등록되지 않은 회원에 한해 `GET https://kapi.kakao.com/v2/user/me`를 호출한다. 요청하는 `property_keys`는 `["kakao_account.profile"]`이다. 응답의 회원번호가 검증한 회원번호와 같은 경우에만 `kakao_account.profile.nickname`을 사용한다.

카카오 Developers의 동의항목 `profile_nickname` 또는 기존 프로필 정보 동의가 필요하다. 동의·이름 미제공, HTTP 오류·네트워크 오류·잘못된 응답은 이름 없음으로 처리한다. 이름 조회는 인증을 대체하지 않으며, 실패해도 기존 인증 성공을 실패로 바꾸지 않는다. 기존 회원 로그인과 Idempotency 응답 복원 시에는 프로필을 다시 조회하지 않는다.

## Apple

`POST /v1/auth/social/apple` 요청에 선택 필드 `fullName`을 추가한다.

```json
{
  "identityToken": "SDK_ID_TOKEN",
  "authorizationCode": "SDK_AUTHORIZATION_CODE",
  "nonce": "SERVER_ISSUED_NONCE",
  "fullName": "김진영"
}
```

`fullName`은 최대 200 UTF-16 코드 단위의 문자열 또는 null이다. 생략 가능한 필드이므로 기존 앱의 세 필드 요청도 계속 허용한다. 다른 알 수 없는 필드를 거절하는 정책은 유지한다. 응답 형식은 바뀌지 않는다.

이름은 SDK가 최초 인증 때 앱에 제공하는 표시 정보이며 ID 토큰으로 검증되는 실명 정보가 아니다. 서버의 기존 nonce·토큰·authorizationCode 검증이 성공한 뒤에만 사용한다. 이름이 있는 요청은 정규화된 이름까지 Idempotency HMAC에 포함한다. 이름 없는 구버전 요청의 기존 HMAC 계산은 유지한다.

앱의 후속 변경: Apple 인증 scope에 fullName을 추가하고 SDK가 제공하는 이름을 조합해 `fullName`으로 전달해야 한다. 최초 수신 이름을 서버 요청 성공 전까지 잃지 않도록 재시도할 때 유지해야 한다. 이름은 일반적으로 최초 인증 때만 제공되므로 저장되지 않은 기존 Apple 회원의 이름을 재로그인만으로 복원하지 못한다. 이 서버 패키지만 적용한 기존 앱은 Apple 이름을 보내지 않는다.

## 저장·대체 규칙

- 앞뒤 공백만 제거하고 내부 공백·문자 순서는 유지한다.
- 기존 닉네임 DB 제한에 맞는 1~20 유니코드 코드 포인트 이름을 저장한다. 제어 문자·포맷 문자·고립 서로게이트를 허용하지 않는다.
- 이름 없음·빈 이름·정책에 맞지 않는 이름은 기존 `다닷` + 12자리 임의 문자열 방식으로 대체한다. 20자를 넘는 이름을 임의로 잘라 저장하지 않는다. 이름 중복도 허용한다.
- 이름은 `auth_proofs.subject_nickname`에 해당 가입 증빙과 함께 보관하고 기존 약관 동의 완료 트랜잭션에서 `users.nickname`으로 저장한다. 기존 회원 일괄 변경·이메일 가입 변경은 하지 않는다.
- Flyway `V21__add_social_signup_nickname.sql`은 nullable 컬럼 하나를 추가한다. 기존 마이그레이션 파일은 수정하지 않는다. 기존 가입 증빙의 새 컬럼 값은 null이다.

서버 배포와 Flyway 적용을 먼저 완료한 뒤 새 Apple 요청 필드를 보내는 앱을 배포한다. 이 문서와 코드 적용 스크립트는 운영 DB·서버 프로세스·Git·배포를 직접 변경하지 않는다.

참고: [카카오 사용자 정보 조회](https://developers.kakao.com/docs/ko/kakaologin/rest-api#req-user-info), [Apple 사용자 인증과 최초 이름 제공](https://developer.apple.com/documentation/signinwithapple/authenticating-users-with-sign-in-with-apple).
