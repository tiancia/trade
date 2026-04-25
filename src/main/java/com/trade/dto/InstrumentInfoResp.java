package com.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
/**
 * OKX 获取交易产品信息返回参数
 *
 * 对应接口：
 * GET /api/v5/account/instruments
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstrumentInfoResp {

    /**
     * 产品类型：SPOT / MARGIN / SWAP / FUTURES / OPTION
     */
    private String instType;

    /**
     * 产品ID，例如 BTC-USD、BTC-USDT、BTC-USDT-SWAP
     */
    private String instId;

    /**
     * 产品ID对应的数值编码
     */
    private Long instIdCode;

    /**
     * 标的指数，例如 BTC-USD
     * 现货通常为空
     */
    private String uly;

    /**
     * 交易品种，例如 BTC-USDT
     * 现货通常为空
     */
    private String instFamily;

    /**
     * 基础币种，例如 BTC
     */
    private String baseCcy;

    /**
     * 计价币种，例如 USD / USDT
     */
    private String quoteCcy;

    /**
     * 结算币种
     */
    private String settleCcy;

    /**
     * 合约面值
     */
    private String ctVal;

    /**
     * 合约乘数
     */
    private String ctMult;

    /**
     * 合约面值计价币种
     */
    private String ctValCcy;

    /**
     * 合约类型：linear / inverse
     */
    private String ctType;

    /**
     * 期权类型：C / P
     */
    private String optType;

    /**
     * 到期时间，毫秒时间戳
     */
    private String expTime;

    /**
     * 上线时间，毫秒时间戳
     */
    private String listTime;

    /**
     * 杠杆倍数
     */
    private String lever;

    /**
     * 最小下单数量
     */
    private String minSz;

    /**
     * 下单数量精度
     */
    private String lotSz;

    /**
     * 下单价格精度
     */
    private String tickSz;

    /**
     * 最大限价单数量
     */
    private String maxLmtSz;

    /**
     * 最大市价单数量
     */
    private String maxMktSz;

    /**
     * 最大限价单金额
     */
    private String maxLmtAmt;

    /**
     * 最大市价单金额
     */
    private String maxMktAmt;

    /**
     * 最大止盈止损委托数量
     */
    private String maxStopSz;

    /**
     * 最大计划委托数量
     */
    private String maxTriggerSz;

    /**
     * 最大冰山委托数量
     */
    private String maxIcebergSz;

    /**
     * 最大 TWAP 委托数量
     */
    private String maxTwapSz;

    /**
     * 最大平台未平仓限制
     */
    private String maxPlatOILmt;

    /**
     * 产品状态：live / suspend / preopen 等
     */
    private String state;

    /**
     * 交易规则类型
     */
    private String ruleType;

    /**
     * 开盘类型：fix_price / pre_quote / call_auction
     */
    private String openType;

    /**
     * 集合竞价结束时间，已逐渐废弃
     */
    private String auctionEndTime;

    /**
     * 连续交易切换时间
     */
    private String contTdSwTime;

    /**
     * 盘前交易切换时间
     */
    private String preMktSwTime;

    /**
     * 支持的实际交易计价币种列表，例如 ["USDC", "USDG"]
     */
    private List<String> tradeQuoteCcyList;

    /**
     * 多头剩余开仓额度
     */
    private String longPosRemainingQuota;

    /**
     * 空头剩余开仓额度
     */
    private String shortPosRemainingQuota;

    /**
     * 仓位限额金额
     */
    private String posLmtAmt;

    /**
     * 仓位限额比例
     */
    private String posLmtPct;

    /**
     * 产品分类
     */
    private String instCategory;

    /**
     * 分组ID
     */
    private String groupId;

    /**
     * 是否未来结算
     */
    private Boolean futureSettlement;

    /**
     * ELP 相关字段
     */
    private String elp;

    /**
     * 行权价格，期权相关
     */
    private String stk;

    /**
     * 合约周期，例如 this_week / next_week / quarter
     */
    private String freq;

    /**
     * 方法字段，通常为空
     */
    private String method;

    /**
     * 系列ID
     */
    private String seriesId;

    /**
     * 未知/扩展变更字段，接口里通常是 []
     */
    private List<Object> upcChg;
}