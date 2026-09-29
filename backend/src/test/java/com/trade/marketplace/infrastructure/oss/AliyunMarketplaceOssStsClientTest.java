package com.trade.marketplace.infrastructure.oss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.marketplace.infrastructure.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class AliyunMarketplaceOssStsClientTest {
    @Test
    void uploadPolicyGrantsOnlySingleObjectUploadActions() throws Exception {
        var mapper = new ObjectMapper();
        var client = new AliyunMarketplaceOssStsClient(new MarketplaceProperties(), mapper);
        var policy = mapper.readTree(client.uploadPolicy("bucket", "marketplace/users/42/image.png"));
        assertEquals("1", policy.path("Version").asText());
        assertEquals(1, policy.path("Statement").size());
        var statement = policy.path("Statement").get(0);
        assertEquals("Allow", statement.path("Effect").asText());
        assertEquals(1, statement.path("Resource").size());
        assertEquals("acs:oss:*:*:bucket/marketplace/users/42/image.png", statement.path("Resource").get(0).asText());
        Set<String> actions = new HashSet<>();
        statement.path("Action").forEach(action -> actions.add(action.asText()));
        assertEquals(Set.of("oss:PutObject", "oss:PutObjectAcl", "oss:AbortMultipartUpload", "oss:ListParts"), actions);
    }
}
