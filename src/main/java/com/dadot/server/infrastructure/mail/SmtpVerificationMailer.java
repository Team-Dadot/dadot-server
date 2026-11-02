package com.dadot.server.infrastructure.mail;

import com.dadot.server.domain.auth.entity.VerificationPurpose;
import com.dadot.server.domain.auth.service.VerificationMailer;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.properties.EmailVerificationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SmtpVerificationMailer implements VerificationMailer {

    private final JavaMailSender sender;
    private final EmailVerificationProperties properties;

    @Override
    public void send(
            String email,
            String code,
            VerificationPurpose purpose
    ) {
        SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(properties.fromAddress());
        message.setTo(email);
        message.setSubject("[다닷] 이메일 인증번호 안내");
        message.setText("""
                다닷 이메일 인증번호는 %s 입니다.
                유효기간은 발급 시점부터 10분입니다.
                본인이 요청하지 않았다면 이 메일을 무시해 주세요.
                """.formatted(code));

        try {
            sender.send(message);

        } catch (MailException ex) {
            // SMTP 예외에는 주소나 본문이 포함될 수 있다.
            // 원본 예외를 그대로 로그·응답에 남기지 않는다.
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }
}