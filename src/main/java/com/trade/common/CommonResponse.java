package com.trade.common;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommonResponse<T> {
    private String code;
    private String msg;
    private List<T> data;

    public static <T> CommonResponse<T> success(List<T> data) {
        CommonResponse<T> resp = new CommonResponse<>();
        resp.setCode("0");
        resp.setMsg("success");
        resp.setData(data);
        return resp;
    }

    public static <T> CommonResponse<T> success(String msg, List<T> data) {
        CommonResponse<T> resp = new CommonResponse<>();
        resp.setCode("0");
        resp.setMsg(msg);
        resp.setData(data);
        return resp;
    }

    public static <T> CommonResponse<T> fail(String code, String msg) {
        CommonResponse<T> resp = new CommonResponse<>();
        resp.setCode(code);
        resp.setMsg(msg);
        resp.setData(null);
        return resp;
    }
}
