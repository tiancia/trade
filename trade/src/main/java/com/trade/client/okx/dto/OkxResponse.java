package com.trade.client.okx.dto;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OkxResponse<T> {
    private String code;
    private String msg;
    private List<T> data;

    public static <T> OkxResponse<T> success(List<T> data) {
        OkxResponse<T> resp = new OkxResponse<>();
        resp.setCode("0");
        resp.setMsg("success");
        resp.setData(data);
        return resp;
    }

    public static <T> OkxResponse<T> success(String msg, List<T> data) {
        OkxResponse<T> resp = new OkxResponse<>();
        resp.setCode("0");
        resp.setMsg(msg);
        resp.setData(data);
        return resp;
    }

    public static <T> OkxResponse<T> fail(String code, String msg) {
        OkxResponse<T> resp = new OkxResponse<>();
        resp.setCode(code);
        resp.setMsg(msg);
        resp.setData(null);
        return resp;
    }
}
