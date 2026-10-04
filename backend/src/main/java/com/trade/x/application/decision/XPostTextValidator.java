package com.trade.x.application.decision;

import com.trade.x.domain.model.XContentPolicy;
import com.twitter.twittertext.TwitterTextParseResults;
import com.twitter.twittertext.TwitterTextParser;

import java.text.Normalizer;
import java.util.Objects;

/** Official twitter-text v3 counting stays outside the runtime-independent domain model. */
public final class XPostTextValidator {
    private XPostTextValidator() { }

    public static String normalizeAndValidate(String value, XContentPolicy policy) {
        Objects.requireNonNull(policy, "X content policy is required");
        if (value == null) throw new IllegalArgumentException("X post text is required");
        String body = policy.validateBody(Normalizer.normalize(value, Normalizer.Form.NFC));
        TwitterTextParseResults parsed = TwitterTextParser.parseTweet(body);
        if (!parsed.isValid || parsed.weightedLength < 1 || parsed.weightedLength > 280) {
            throw new IllegalArgumentException("X post text violates the weighted 280-character platform limit");
        }
        return body;
    }
}
