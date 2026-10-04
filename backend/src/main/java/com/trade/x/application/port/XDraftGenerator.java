package com.trade.x.application.port;

import com.trade.x.domain.model.GeneratedXPost;
import com.trade.x.domain.model.XContentPolicy;

public interface XDraftGenerator {
    GeneratedXPost generate(XContentPolicy content);
}
