package com.trade.story.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiStoryProperties.class)
public class StoryConfiguration {
}
