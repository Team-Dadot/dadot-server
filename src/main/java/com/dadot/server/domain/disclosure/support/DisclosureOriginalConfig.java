package com.dadot.server.domain.disclosure.support;

import com.dadot.server.infrastructure.dart.DartArchiveExtractor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DisclosureOriginalConfig {

    @Bean
    public DartArchiveExtractor dartArchiveExtractor() {
        return new DartArchiveExtractor();
    }
}