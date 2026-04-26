package com.trade.client.okx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
/**
 * OKX 鑾峰彇浜ゆ槗浜у搧淇℃伅杩斿洖鍙傛暟
 *
 * 瀵瑰簲鎺ュ彛锛?
 * GET /api/v5/account/instruments
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstrumentInfoResp {

    /**
     * 浜у搧绫诲瀷锛歋POT / MARGIN / SWAP / FUTURES / OPTION
     */
    private String instType;

    /**
     * 浜у搧ID锛屼緥濡?BTC-USD銆丅TC-USDT銆丅TC-USDT-SWAP
     */
    private String instId;

    /**
     * 浜у搧ID瀵瑰簲鐨勬暟鍊肩紪鐮?
     */
    private Long instIdCode;

    /**
     * 鏍囩殑鎸囨暟锛屼緥濡?BTC-USD
     * 鐜拌揣閫氬父涓虹┖
     */
    private String uly;

    /**
     * 浜ゆ槗鍝佺锛屼緥濡?BTC-USDT
     * 鐜拌揣閫氬父涓虹┖
     */
    private String instFamily;

    /**
     * 鍩虹甯佺锛屼緥濡?BTC
     */
    private String baseCcy;

    /**
     * 璁′环甯佺锛屼緥濡?USD / USDT
     */
    private String quoteCcy;

    /**
     * 缁撶畻甯佺
     */
    private String settleCcy;

    /**
     * 鍚堢害闈㈠€?
     */
    private String ctVal;

    /**
     * 鍚堢害涔樻暟
     */
    private String ctMult;

    /**
     * 鍚堢害闈㈠€艰浠峰竵绉?
     */
    private String ctValCcy;

    /**
     * 鍚堢害绫诲瀷锛歭inear / inverse
     */
    private String ctType;

    /**
     * 鏈熸潈绫诲瀷锛欳 / P
     */
    private String optType;

    /**
     * 鍒版湡鏃堕棿锛屾绉掓椂闂存埑
     */
    private String expTime;

    /**
     * 涓婄嚎鏃堕棿锛屾绉掓椂闂存埑
     */
    private String listTime;

    /**
     * 鏉犳潌鍊嶆暟
     */
    private String lever;

    /**
     * 鏈€灏忎笅鍗曟暟閲?
     */
    private String minSz;

    /**
     * 涓嬪崟鏁伴噺绮惧害
     */
    private String lotSz;

    /**
     * 涓嬪崟浠锋牸绮惧害
     */
    private String tickSz;

    /**
     * 鏈€澶ч檺浠峰崟鏁伴噺
     */
    private String maxLmtSz;

    /**
     * 鏈€澶у競浠峰崟鏁伴噺
     */
    private String maxMktSz;

    /**
     * 鏈€澶ч檺浠峰崟閲戦
     */
    private String maxLmtAmt;

    /**
     * 鏈€澶у競浠峰崟閲戦
     */
    private String maxMktAmt;

    /**
     * 鏈€澶ф鐩堟鎹熷鎵樻暟閲?
     */
    private String maxStopSz;

    /**
     * 鏈€澶ц鍒掑鎵樻暟閲?
     */
    private String maxTriggerSz;

    /**
     * 鏈€澶у啺灞卞鎵樻暟閲?
     */
    private String maxIcebergSz;

    /**
     * 鏈€澶?TWAP 濮旀墭鏁伴噺
     */
    private String maxTwapSz;

    /**
     * 鏈€澶у钩鍙版湭骞充粨闄愬埗
     */
    private String maxPlatOILmt;

    /**
     * 浜у搧鐘舵€侊細live / suspend / preopen 绛?
     */
    private String state;

    /**
     * 浜ゆ槗瑙勫垯绫诲瀷
     */
    private String ruleType;

    /**
     * 寮€鐩樼被鍨嬶細fix_price / pre_quote / call_auction
     */
    private String openType;

    /**
     * 闆嗗悎绔炰环缁撴潫鏃堕棿锛屽凡閫愭笎搴熷純
     */
    private String auctionEndTime;

    /**
     * 杩炵画浜ゆ槗鍒囨崲鏃堕棿
     */
    private String contTdSwTime;

    /**
     * 鐩樺墠浜ゆ槗鍒囨崲鏃堕棿
     */
    private String preMktSwTime;

    /**
     * 鏀寔鐨勫疄闄呬氦鏄撹浠峰竵绉嶅垪琛紝渚嬪 ["USDC", "USDG"]
     */
    private List<String> tradeQuoteCcyList;

    /**
     * 澶氬ご鍓╀綑寮€浠撻搴?
     */
    private String longPosRemainingQuota;

    /**
     * 绌哄ご鍓╀綑寮€浠撻搴?
     */
    private String shortPosRemainingQuota;

    /**
     * 浠撲綅闄愰閲戦
     */
    private String posLmtAmt;

    /**
     * 浠撲綅闄愰姣斾緥
     */
    private String posLmtPct;

    /**
     * 浜у搧鍒嗙被
     */
    private String instCategory;

    /**
     * 鍒嗙粍ID
     */
    private String groupId;

    /**
     * 鏄惁鏈潵缁撶畻
     */
    private Boolean futureSettlement;

    /**
     * ELP 鐩稿叧瀛楁
     */
    private String elp;

    /**
     * 琛屾潈浠锋牸锛屾湡鏉冪浉鍏?
     */
    private String stk;

    /**
     * 鍚堢害鍛ㄦ湡锛屼緥濡?this_week / next_week / quarter
     */
    private String freq;

    /**
     * 鏂规硶瀛楁锛岄€氬父涓虹┖
     */
    private String method;

    /**
     * 绯诲垪ID
     */
    private String seriesId;

    /**
     * 鏈煡/鎵╁睍鍙樻洿瀛楁锛屾帴鍙ｉ噷閫氬父鏄?[]
     */
    private List<Object> upcChg;
}