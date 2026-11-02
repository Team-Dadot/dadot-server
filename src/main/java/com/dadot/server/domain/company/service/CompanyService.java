package com.dadot.server.domain.company.service;

import com.dadot.server.domain.company.dto.request.CompanySearchRequest;
import com.dadot.server.domain.company.dto.response.CompanyResponse;
import com.dadot.server.domain.company.repository.CompanyRepository;
import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.response.PageResponse;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

@Service
public class CompanyService {

    private final CompanyRepository repository;
    private final CompanyLogoUrlResolver logos;
    private final TransactionTemplate readTransaction;

    public CompanyService(
            CompanyRepository repository,
            CompanyLogoUrlResolver logos,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.logos = logos;
        this.readTransaction = new TransactionTemplate(transactionManager);

        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ
        );
        this.readTransaction.setTimeout(5);
    }

    public PageResponse<CompanyResponse> search(CompanySearchRequest request) {
        Page<CompanyRepository.CompanyRow> page;

        try {
            page = Objects.requireNonNull(readTransaction.execute(status ->
                    repository.search(
                            request.query(),
                            PageRequest.of(request.page(), request.size())
                    )
            ));
        } catch (
                TransientDataAccessException
                | DataAccessResourceFailureException
                | CannotCreateTransactionException
                | TransactionTimedOutException ex
        ) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }

        var content = page.getContent().stream()
                .map(company -> new CompanyResponse(
                        company.id(),
                        company.name(),
                        company.industry(),
                        logos.resolve(company.logoKey())
                ))
                .toList();

        // page + 1의 int 오버플로를 피하고 실제 offset으로 판정한다.
        boolean hasNext = page.getPageable().getOffset()
                + page.getSize() < page.getTotalElements();

        return new PageResponse<>(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                hasNext
        );
    }
}