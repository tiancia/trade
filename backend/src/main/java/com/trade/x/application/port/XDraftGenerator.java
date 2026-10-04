package com.trade.x.application.port;

import com.trade.x.domain.model.GeneratedXPost;
import com.trade.x.domain.model.XContentPolicy;
import java.util.List;

public interface XDraftGenerator {
    default GeneratedXPost generate(XContentPolicy content) {
        return generate(content, List.of());
    }

    GeneratedXPost generate(XContentPolicy content, List<String> recentBodies);
}
