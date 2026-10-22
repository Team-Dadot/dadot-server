package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.entity.VerificationPurpose;

public interface VerificationMailer {

    void send(
            String email,
            String code,
            VerificationPurpose purpose
    );
}