package com.trade.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * OKX 获取交易产品信息请求参数
 *
 * 对应接口：
 * GET /api/v5/account/instruments
 */
@Data
@Accessors(chain = true)
public class InstrumentInfoReq {

    /**
     * 产品类型
     *
     * 常见取值：
     * SPOT    现货
     * MARGIN  杠杆
     * SWAP    永续合约
     * FUTURES 交割合约
     * OPTION  期权
     */
    private String instType;

    /**
     * 系列 ID
     * 主要用于期权等产品。
     * 普通现货、合约查询一般用不到，可以不传。
     */
    private String seriesId;

    /**
     * 交易品种
     * 常用于合约、期权等产品。
     */
    private String instFamily;

    /**
     * 产品 ID
     * 用于指定具体交易产品。
     */
    private String instId;
}