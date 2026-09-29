package com.trade.textgame.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.textgame.domain.rule.TextGameRuleEngine;
import com.trade.textgame.domain.rule.TextGameStoryValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TextGameConfiguration {
    @Bean
    TextGameRuleEngine textGameRuleEngine() {
        return new TextGameRuleEngine();
    }

    @Bean
    TextGameStoryValidator textGameStoryValidator() {
        return new TextGameStoryValidator();
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper textGameObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
