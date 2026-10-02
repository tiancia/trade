package com.trade.weibo.application.port;

import com.trade.weibo.domain.model.GeneratedComment;
import com.trade.weibo.domain.model.HotEvent;

public interface WeiboDraftGenerator {
    GeneratedComment generate(HotEvent event, int maxBodyChars);
}
