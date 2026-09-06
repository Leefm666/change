# hr.hr_payroll.calculate_and_approve_payroll 业务规则推导

## 签名
- 所属包:hr.hr_payroll
- 可见性:public(包头声明)
- 入参:
  - p_employee_id INT,不允许业务层传 NULL(数据库层不禁止,但会命中 B2 描述的 P0002)
  - p_period_id INT — 薪资周期ID(如202501表示2025年1月)
  - p_approver_id INT,不允许业务层传 NULL,同上
  - p_bonus_override NUMERIC,允许 NULL——NULL 时走 B4 的系统计算分支,非NULL直接采用该值
- 出参:
  - o_net_salary NUMERIC — 税后实发净额;异常路径下为NULL
  - o_status VARCHAR — 'APPROVED' 或 'FAILED: {SQLERRM}'(见"待确认项"关于该字段设计的问题)

## 分支清单

| 分支ID | 类型 | 触发条件 | 业务含义 | 是否已实测验证 |
|---|---|---|---|---|
| B1 | 顶层异常块 | 整个 procedure body | `EXCEPTION WHEN OTHERS RAISE`,等价隐式 SAVEPOINT,失败路径的写入随之回滚 | 是(H2) |
| B2 | 隐式 NOT FOUND(非源码显式分支) | 员工/审批人/薪资标准查询无结果 | package 内 `SELECT INTO` 无结果时 openGauss 自动抛 P0002,源码里手写的 P2001/P2002/P2003 分支从未触发 | 是(H1) |
| B3 | CASE | 计算奖金时按 perf_rating 分档 | S=50%、A=30%、B=15%、C及其他=0%,系数×基本工资 | 是 |
| B4 | IF | p_bonus_override 非 NULL | 直接采用该值作为奖金,跳过 B3 | 是 |
| B5 | 嵌套调用 | calc_bonus 内部 | 未走 override 时,calc_bonus 内部又调用一次 calc_base_salary,产生重复查询 | 是 |
| B6 | IF | gross > 审批人 approval_limit | 抛 P1001,审批权限超限 | 是 |
| B7 | 游标+IF | 遍历 deduction_items,item_status=DISPUTED | 跳过该条不计入合计,写 DEDUCTION_SKIPPED 审计日志,游标继续不中断 | 是 |
| B8 | IF | net_before_tax < 0 | 抛 P1003,扣款合计超过应发,数据异常 | 是 |
| B9 | 游标+分档 | calc_progressive_tax 遍历 tax_brackets | upper_bound 是闭区间上限,NULL 表示最高档无上限,逐档累进计税 | 是 |
| B10 | IF(死代码) | g_dry_run | 恒为 FALSE,`PENDING_REVIEW` 分支在当前源码状态下不可达 | 是(H3) |
| B11 | 主成功路径 | 全部前置分支通过 | 写入 payroll_approvals,写 PAYROLL_APPROVED 审计日志,返回 APPROVED | 是 |

## 包全局变量处理

| 变量ID | 变量名 | 类型 | 读/写 | 跨调用是否持续 | 处理结论 |
|---|---|---|---|---|---|
| G1 | g_fiscal_year | INT | 写(硬编码2025)/多处读(calc_base_salary、calc_progressive_tax) | 否,每次调用开头都被重新赋值,不依赖上次调用遗留值 | 纯临时中间量,直接内联为方法内变量或改为显式入参(建议改为入参,硬编码2025是明显的技术债,见待确认项) |
| G2 | g_audit_operator | VARCHAR(100) | 写(查审批人姓名后)/读(write_audit_log 每次调用) | 否,单次业务调用链内的隐式上下文,不跨外部调用 | 显式参数传递:Java 里作为方法内的局部变量,由内部调用 writeAuditLog 时显式传入,不做成字段 |
| G3 | g_dry_run | BOOLEAN | 写(硬编码FALSE)/读(write_audit_log、主流程末尾判断) | 否,每次调用开头都被重新赋值为FALSE | 当前代码路径下是死代码(见"待确认项"——是否要在Java里保留这个从未被真正激活的试算开关) |

- 并发/多会话隔离性实测结论:用两个独立 JDBC 连接构造"连接A设置全局变量后 sleep 5秒,期间连接B设置并读取"的时序实验。结果:连接A的全局变量值全程不受连接B干扰,B读到的始终是B自己设置的值。**openGauss package 全局变量是数据库会话(连接)级隔离的,不是实例级共享的**——G1/G2/G3 都不存在跨会话争用问题,`global-state-mapping.md` 判断树第3步对这三个变量的判断都是"否"。

## 边界条件矩阵

| 边界ID | 类型 | 是否适用 | 实测结论 |
|---|---|---|---|
| E1 | null 输入 | 适用 | p_employee_id 为 NULL 时,`SELECT ... WHERE employee_id = NULL` 不命中任何行,与 B2 走同一条 P0002 路径,不是源码里的 P2001 |
| E2 | 空结果集 | 适用 | 员工当期无任何扣款项(B7 游标一次都不 FETCH 成功)时,deductions=0,直接跳过循环,不影响后续计算 |
| E3 | 重复调用幂等性 | 适用 | 非幂等。同一员工同一周期重复调用,每次都在 payroll_approvals 新增一条 APPROVED 记录,没有"该周期是否已核算"的检查 |
| E4 | 隐式类型转换 | 适用 | 所有金额字段 NUMERIC(12,2),ROUND(amount,2) 与 Java `BigDecimal.setScale(2, RoundingMode.HALF_UP)` 语义一致 |
| E5 | 全局变量多次调用间读写时序 | 适用 | G2(g_audit_operator)每次调用开头都被重新赋值,不依赖上次调用遗留值;同一连接内连续两次不同审批人的调用,互不串号(已实测) |
| E6 | 税率分档边界(闭区间) | 适用 | 净额恰好等于某档 upper_bound 时,该金额完整计入本档(闭区间上限);超出1元起才进入下一档 |
| E7 | 空税率表 | 适用 | 对应财年 tax_brackets 一行都没有时,B9 游标不 FETCH 任何数据,tax=0,净额=税前净额全额发放,不抛异常(潜在业务bug候选,不在本次迁移中修正) |

## 依赖
- 表:hr.employees, hr.approvers, hr.salary_standards, hr.tax_brackets, hr.deduction_items, hr.payroll_approvals, hr.audit_log
- 触发器:无
- 序列:hr.deduction_items_deduction_id_seq / hr.payroll_approvals_approval_id_seq / hr.audit_log_log_id_seq(均为隐式 SERIAL 列)
- 嵌套调用(包内 private,调用链深度3层):
  - calculate_and_approve_payroll → calc_base_salary
  - calculate_and_approve_payroll → calc_bonus → calc_base_salary(B5,重复调用)
  - calculate_and_approve_payroll → check_approval_authority
  - calculate_and_approve_payroll → deduct_items_loop → write_audit_log(B7 跳过分支才调用)
  - calculate_and_approve_payroll → calc_progressive_tax
  - calculate_and_approve_payroll → write_audit_log(直接调用两处:START/APPROVED)

## 隐藏行为记录

| 隐藏行为ID | 关联的分支/边界ID | 发现内容 | Java 侧应如何处理 |
|---|---|---|---|
| H1 | B2, E1 | package 内 `SELECT INTO` 查询无结果时,openGauss 自动抛 P0002("query returned no rows when process INTO"),源码里手写的 `RAISE EXCEPTION ... USING ERRCODE='P2001'/'P2002'/'P2003'` 三条分支和"查审批人姓名"那行 `COALESCE(v_approver_name,...)` 兜底逻辑均为死代码,从未被触发过。实测覆盖:不存在的员工(999)、不存在的审批人(999)、等级缺薪资标准(P9) | 统一用 `PayrollEntityNotFoundException`(SQLSTATE P0002)复现实测行为,不翻译源码里那些死代码分支 |
| H2 | B1 | 调用审批人11(权限上限5000)结算gross=27000的员工,触发 P1001 异常前 `write_audit_log('PAYROLL_START',...)` 已执行 INSERT,但实测调用前后 `hr.audit_log` 表行数不变,PAYROLL_START 记录随 RAISE 回滚 | 整体 `@Transactional`,失败路径依然调用 insert 但让事务统一回滚(机制对等,不是"失败分支干脆不 insert"的表面模仿) |
| H3 | B10 | g_dry_run 恒为 FALSE,没有任何入参或外部条件能让它变成 TRUE,`PENDING_REVIEW` 分支在当前源码状态下永远不可达 | Java 实现暂不实现这个分支,待业务方确认是否要保留/激活 |

## 覆盖自检

| ID | 类型 | 对应测试用例文档里的用例名 |
|---|---|---|
| B1 | 分支 | failure_approvalOverLimit_javaMatchesProcedureAndBothRollBackAuditLog |
| B2 | 分支 | failure_employeeNotFound_throwsP0002, failure_approverNotFound_throwsP0002, failure_gradeMissingSalaryStandard_throwsP0002 |
| B3 | 分支 | success_naturalCRatingZeroBonus, success_basic_noDeductions |
| B4 | 分支 | success_overrideZeroBonus |
| B5 | 分支 | success_naturalCRatingZeroBonus(通过审计辅助断言员工查询次数,已在 mock 层 PayrollServiceTest 验证,IT 层不重复断言查询次数) |
| B6 | 分支 | failure_approvalOverLimit_javaMatchesProcedureAndBothRollBackAuditLog |
| B7 | 分支 | success_deductionWithDisputedSkip_javaMatchesProcedure |
| B8 | 分支 | failure_netBeforeTaxNegative_javaMatchesProcedure |
| B9 | 分支 | taxBracketBoundary_upperInclusive_javaMatchesProcedure, taxBracketBoundary_overByOne(仅存储过程行为IT) |
| B10 | 分支 | dry_run_branch_currently_unreachable(mock 层 PayrollServiceTest) |
| B11 | 分支 | success_basic_noDeductions |
| E1 | 边界条件 | nullEmployeeId_throwsP0002(存储过程行为IT,真实传JDBC NULL) |
| E2 | 边界条件 | success_basic_noDeductions(该场景无扣款记录) |
| E3 | 边界条件 | repeatedCall_nonIdempotent_insertsTwoApprovalRows |
| E4 | 边界条件 | 所有金额断言均用 isEqualByComparingTo,隐含验证 |
| E5 | 边界条件 | global_var_audit_operator_not_leaked_across_calls(mock 层 PayrollServiceTest) |
| E6 | 边界条件 | taxBracketBoundary_upperInclusive_javaMatchesProcedure |
| E7 | 边界条件 | tax_bracket_table_empty_zero_tax(mock层)、taxBracketTableEmpty_zeroTax(存储过程行为IT)、taxBracketTableEmpty_javaMatchesProcedure(对比IT) |
| G1 | 全局变量 | 隐含在所有场景中(硬编码2025) |
| G2 | 全局变量 | global_var_audit_operator_not_leaked_across_calls |
| G3 | 全局变量 | dry_run_branch_currently_unreachable |
| H1 | 隐藏行为 | failure_employeeNotFound_javaMatchesProcedure, failure_gradeMissingSalaryStandard_javaMatchesProcedure |
| H2 | 隐藏行为 | failure_approvalOverLimit_javaMatchesProcedureAndBothRollBackAuditLog |
| H3 | 隐藏行为 | dry_run_branch_currently_unreachable |

## 待确认项(需业务方裁决,不由开发自行决定)
- **g_fiscal_year(G1)硬编码为2025,不随实际系统年份变化**:是技术债还是故意设计?Java 实现是否应该改成动态取当前年份或显式传参?
- **g_dry_run(G3)恒为 FALSE,PENDING_REVIEW 分支是死代码(H3)**:Java 实现是否要保留这个"试算不落库"能力,还是判定为未完成的历史遗留代码予以移除?
- **B5 calc_bonus 内部重复调用 calc_base_salary**:是否需要在 Java 实现里优化成只查一次(有性能影响但会改变调用结构)?
- **o_status 失败路径下拼接 SQLERRM 原始异常消息**:这个字符串格式是否是被上游依赖的契约?Java 侧是否要保留同样格式,还是改成结构化异常类型?
- **E3 重复调用非幂等**:是否是预期设计,还是应该增加"该周期是否已核算"的前置检查?
- **H1 P0002 覆盖了源码里手写的 P2001/P2002/P2003**:源码里那三条自定义错误码分支是否本来想给业务方更友好的提示,只是被 package 语法本身的行为覆盖失效?是否是既有 bug 而不是本次迁移顺带保留的行为?
