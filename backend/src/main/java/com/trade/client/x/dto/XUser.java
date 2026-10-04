package com.trade.client.x.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record XUser(String id, String name, String username) {
}
