package com.trade.trading;

import com.trade.client.gemini.GeminiApi;
import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class TradingClientConfiguration {

    @Bean
    public OkxApi okxApi() {
        return new OkxApi(new OkxClient());
    }

    @Bean
    @Lazy
    public GeminiApi geminiApi() {
        return new GeminiApi();
    }
}
