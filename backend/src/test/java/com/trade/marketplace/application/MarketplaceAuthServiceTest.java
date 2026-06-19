package com.trade.marketplace.application;

import com.trade.marketplace.config.MarketplaceProperties;
import com.trade.marketplace.model.MarketplaceApi;
import com.trade.marketplace.support.InMemoryMarketplaceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketplaceAuthServiceTest {
    private final InMemoryMarketplaceMapper mapper = new InMemoryMarketplaceMapper();
    private final MarketplaceProperties properties = new MarketplaceProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-19T08:00:00Z"), ZoneOffset.UTC);
    private final MarketplaceAuthService service = new MarketplaceAuthService(
            mapper,
            new BCryptPasswordEncoder(),
            properties,
            clock
    );

    @Test
    void registersWithHashedPasswordAndReturnsMe() {
        MarketplaceApi.AuthResponse registered = service.register(
                new MarketplaceApi.RegisterRequest("alice_1", "password-123", "Alice")
        );

        assertNotNull(registered.token());
        assertEquals("alice_1", registered.user().username());
        assertNotEquals("password-123", mapper.findUserByUsername("alice_1").getPasswordHash());
        assertEquals(registered.user(), service.me("Bearer " + registered.token()));
    }

    @Test
    void loginRejectsWrongPasswordAndCreatesNewSession() {
        MarketplaceApi.AuthResponse registered = service.register(
                new MarketplaceApi.RegisterRequest("bob", "password-123", "Bob")
        );

        assertThrows(MarketplaceUnauthorizedException.class,
                () -> service.login(new MarketplaceApi.LoginRequest("bob", "wrong-pass")));
        MarketplaceApi.AuthResponse loggedIn = service.login(new MarketplaceApi.LoginRequest("bob", "password-123"));

        assertEquals(registered.user(), loggedIn.user());
        assertNotEquals(registered.token(), loggedIn.token());
    }

    @Test
    void logoutAndExpiryInvalidateSession() {
        MarketplaceApi.AuthResponse registered = service.register(
                new MarketplaceApi.RegisterRequest("carol", "password-123", "Carol")
        );
        service.logout("Bearer " + registered.token());
        assertThrows(MarketplaceUnauthorizedException.class, () -> service.me("Bearer " + registered.token()));

        MarketplaceApi.AuthResponse shortLived = service.login(new MarketplaceApi.LoginRequest("carol", "password-123"));
        MarketplaceAuthService afterExpiry = new MarketplaceAuthService(
                mapper,
                new BCryptPasswordEncoder(),
                properties,
                Clock.fixed(clock.instant().plusSeconds(31L * 24 * 60 * 60), ZoneOffset.UTC)
        );
        assertThrows(MarketplaceUnauthorizedException.class, () -> afterExpiry.me("Bearer " + shortLived.token()));
    }
}
