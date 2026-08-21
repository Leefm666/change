package com.example.migration.routing;

import com.example.migration.domain.SettlementResult;

/** 一次结算调用的结果,附带这次实际走的是哪条路径,供接口响应和灰度生效比例观测使用。 */
public record SettlementOutcome(SettlementResult result, RouteTarget routedTo) {

    public enum RouteTarget {
        JAVA,
        LEGACY_PROCEDURE
    }
}
