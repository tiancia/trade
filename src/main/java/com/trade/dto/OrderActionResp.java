package com.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderActionResp {
    private String ordId;
    private String clOrdId;
    private String tag;
    @JsonProperty("sCode")
    private String sCode;

    @JsonProperty("sMsg")
    private String sMsg;
}
