package com.trade.client.okx.ws;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OkxWsEvent<T> {
    private String event;
    private OkxWsArg arg;
    private String code;
    private String msg;
    private String connId;
    private List<T> data;
}
