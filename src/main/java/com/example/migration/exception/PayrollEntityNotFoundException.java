package com.example.migration.exception;

/**
 * 对应存储过程 SQLSTATE P0002:package 内 SELECT INTO 查询无结果时,openGauss
 * 自动抛出的异常(不是源码里手写的 P2001/P2002/P2003 分支——那些分支实测是死代码,
 * 详见 docs/rules/hr.hr_payroll.calculate_and_approve_payroll.md 的"隐藏行为1")。
 * 覆盖员工不存在、审批人不存在、员工等级缺薪资标准三种场景。
 */
public class PayrollEntityNotFoundException extends RuntimeException {

    public PayrollEntityNotFoundException(String message) {
        super(message);
    }

    public String sqlState() {
        return "P0002";
    }
}
