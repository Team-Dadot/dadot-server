package com.dadot.server.domain.job.service;

import com.dadot.server.domain.job.dto.request.JobSearchRequest;
import com.dadot.server.domain.job.dto.response.JobSearchResponse;
import com.dadot.server.domain.job.repository.JobRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

@Service
public class JobService {

    private final JobRepository repository;
    private final TransactionTemplate readTransaction;

    public JobService(
            JobRepository repository,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ
        );
        this.readTransaction.setTimeout(5);
    }

    public JobSearchResponse search(JobSearchRequest request) {
        try {
            return Objects.requireNonNull(readTransaction.execute(status -> {
                var page = repository.search(
                        request.query(), request.categoryId(), request.popular(),
                        PageRequest.of(request.page(), request.size())
                );

                return JobSearchResponse.from(page, repository.findAllCategories());
            }));
        } catch (
                TransientDataAccessException
                | DataAccessResourceFailureException
                | CannotCreateTransactionException
                | TransactionTimedOutException ex
        ) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}