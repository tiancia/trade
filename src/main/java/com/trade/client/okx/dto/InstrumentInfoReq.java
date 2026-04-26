package com.trade.client.okx.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * OKX 鑾峰彇浜ゆ槗浜у搧淇℃伅璇锋眰鍙傛暟
 *
 * 瀵瑰簲鎺ュ彛锛?
 * GET /api/v5/account/instruments
 */
@Data
@Accessors(chain = true)
public class InstrumentInfoReq {

    /**
     * 浜у搧绫诲瀷
     *
     * 甯歌鍙栧€硷細
     * SPOT    鐜拌揣
     * MARGIN  鏉犳潌
     * SWAP    姘哥画鍚堢害
     * FUTURES 浜ゅ壊鍚堢害
     * OPTION  鏈熸潈
     */
    private String instType;

    /**
     * 绯诲垪 ID
     * 涓昏鐢ㄤ簬鏈熸潈绛変骇鍝併€?
     * 鏅€氱幇璐с€佸悎绾︽煡璇竴鑸敤涓嶅埌锛屽彲浠ヤ笉浼犮€?
     */
    private String seriesId;

    /**
     * 浜ゆ槗鍝佺
     * 甯哥敤浜庡悎绾︺€佹湡鏉冪瓑浜у搧銆?
     */
    private String instFamily;

    /**
     * 浜у搧 ID
     * 鐢ㄤ簬鎸囧畾鍏蜂綋浜ゆ槗浜у搧銆?
     */
    private String instId;
}