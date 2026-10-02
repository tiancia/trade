package com.trade.weibo.application.port;

import com.trade.weibo.domain.model.HotEvent;
import java.util.List;

public interface HotEventSource {
    List<HotEvent> collect();
}
