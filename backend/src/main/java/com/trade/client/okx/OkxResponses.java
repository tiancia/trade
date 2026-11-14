package com.trade.client.okx;

import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.OkxResponse;

import java.util.List;
import java.util.Optional;

public final class OkxResponses {
    private OkxResponses() {
    }

    public static <T> Optional<T> first(OkxResponse<T> response) {
        if (response == null) {
            return Optional.empty();
        }
        List<T> data = response.getData();
        if (data == null || data.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(data.get(0));
    }

    public static <T> List<T> data(OkxResponse<T> response, String name) {
        requireOk(response, name);
        List<T> data = response.getData();
        return data == null ? List.of() : data;
    }

    public static <T> T requireFirst(OkxResponse<T> response, String name) {
        requireOk(response, name);
        return first(response)
                .orElseThrow(() -> new IllegalStateException("OKX response has no " + name + " data"));
    }

    public static void requireOk(OkxResponse<?> response, String name) {
        if (!isOk(response)) {
            throw new IllegalStateException(failureMessage(response, name));
        }
    }

    public static <T> T requireSingleSnapshot(OkxResponse<T> response, String name) {
        requireOk(response, name);
        List<T> data = response.getData();
        if (data == null || data.size() != 1 || data.getFirst() == null) {
            throw new IllegalStateException("OKX " + name + " requires exactly one snapshot");
        }
        return data.getFirst();
    }

    /** An omitted currency is valid in an explicit list of balances. */
    public static BalanceDetail findBalance(AccountBalanceResp account, String currency) {
        if (account.getDetails() == null) {
            throw new IllegalStateException("OKX account snapshot has no balance details");
        }
        BalanceDetail result = null;
        for (BalanceDetail detail : account.getDetails()) {
            if (detail == null || detail.getCcy() == null || detail.getCcy().isBlank()) {
                throw new IllegalStateException("OKX balance response has a missing currency identity");
            }
            if (currency.equalsIgnoreCase(detail.getCcy())) {
                if (result != null) {
                    throw new IllegalStateException("OKX balance response has duplicate entries for " + currency);
                }
                result = detail;
            }
        }
        return result;
    }

    public static boolean isOk(OkxResponse<?> response) {
        return response != null && "0".equals(response.getCode());
    }

    public static String failureMessage(OkxResponse<?> response, String name) {
        if (response == null) {
            return "OKX " + name + " response is null";
        }
        return "OKX " + name + " business error, code=" + response.getCode() + ", msg=" + response.getMsg();
    }
}
