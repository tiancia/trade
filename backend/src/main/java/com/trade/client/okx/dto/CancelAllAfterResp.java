package com.trade.client.okx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Exchange acknowledgement of the account's cancel-all-after timer. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CancelAllAfterResp {
    private String triggerTime;
    private String ts;
    private String tag;
}
