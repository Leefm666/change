package com.example.migration.routing;

import com.example.migration.domain.PayrollResult;

/** 一次核算调用的结果,附带这次实际走的是哪条路径,供接口响应和灰度生效比例观测使用。 */
public record PayrollOutcome(PayrollResult result, RouteTarget routedTo) {

    public enum RouteTarget {
        JAVA,
        LEGACY_PROCEDURE
    }
}
