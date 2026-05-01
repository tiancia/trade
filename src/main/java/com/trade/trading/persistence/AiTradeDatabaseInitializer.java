package com.trade.trading.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AiTradeDatabaseInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AiTradeDatabaseInitializer.class);

    private final MyBatisAiDecisionAuditRepository auditRepository;

    public AiTradeDatabaseInitializer(MyBatisAiDecisionAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            auditRepository.initializeSchema();
        } catch (Exception e) {
            log.warn("AI trade audit schema initialization skipped: {}", e.getMessage(), e);
        }
    }
}
