# hr.hr_payroll.calculate_and_approve_payroll 测试用例设计

依据 `docs/rules/hr.hr_payroll.calculate_and_approve_payroll.md` 逐条分支和边界条件列出场景,自然语言描述,不写代码。后续 mock UT / 直连数据库 UT / 特性开关双路径断言均复用这里的场景名字。

| 场景名 | 输入 | 对应分支/边界条件 | 预期结果 | 预期表状态变化 |
|---|---|---|---|---|
| success_basic_no_deductions | 员工2(P3/A绩效),新周期(无扣款记录),审批人10,override=NULL | 分支4(系统算奖金,A档30%)、边界条件"空结果集" | APPROVED,gross=10400(8000+2400),deductions=0,net按累进税算 | payroll_approvals新增1条;audit_log新增PAYROLL_START+PAYROLL_APPROVED两条 |
| success_override_zero_bonus | 员工3(P4/C绩效),审批人10,override=0 | 分支4(override非NULL直接用,值为0) | APPROVED,bonus=0,gross=12000 | 同上,新增1条审批记录 |
| success_natural_c_rating_zero_bonus | 员工3(P4/C绩效),审批人10,override=NULL | 分支4(CASE的ELSE分支,系统按C档系数0计算,不是走override) | APPROVED,bonus=0,gross=12000,与override=0结果数值相同但代码路径不同 | 同上 |
| success_deduction_with_disputed_skip | 员工1(P5/S绩效),周期202501(有3条扣款:800普通/400普通/200争议中),审批人10 | 分支6(游标遍历,DISPUTED条目跳过不计入合计但不中断游标) | APPROVED,deductions=1200(不含被跳过的200) | audit_log新增1条DEDUCTION_SKIPPED记录,detail包含被跳过的deduction_id |
| failure_employee_not_found | 员工ID=999(不存在),审批人10 | 分支4起点(calc_base_salary里SELECT INTO查员工),隐藏行为1 | 抛异常SQLSTATE=P0002,不是源码里的P2001 | 无任何表变化(异常在写PAYROLL_START之后触发,验证该条日志是否随事务回滚——见下一行) |
| failure_employee_not_found_audit_log_rollback | 同上 | 隐藏行为2的另一实例:PAYROLL_START已执行INSERT但异常发生在其后 | 同上失败 | audit_log行数与调用前完全一致(PAYROLL_START被回滚) |
| failure_approver_not_found | 员工1(存在),审批人ID=999(不存在) | 分支2(查审批人姓名的SELECT INTO),隐藏行为1(COALESCE兜底是死代码) | 抛异常SQLSTATE=P0002,不会走到COALESCE生成'approver#999'这条兜底路径 | 无表变化(异常发生在写PAYROLL_START之前) |
| failure_grade_missing_salary_standard | 员工5(P9等级,该等级在salary_standards里无对应行),审批人10 | 分支4(calc_base_salary里查salary_standards的SELECT INTO),隐藏行为1(源码P2002分支死代码) | 抛异常SQLSTATE=P0002,不是源码里的P2002 | audit_log的PAYROLL_START记录被回滚(同隐藏行为2机制) |
| failure_approval_over_limit | 员工1(gross=27000),审批人11(权限上限5000) | 分支5(check_approval_authority超限),隐藏行为2 | 抛异常SQLSTATE=P1001,消息含具体金额和上限 | audit_log的PAYROLL_START记录被回滚,行数与调用前一致 |
| failure_net_before_tax_negative | 任一员工,人为插入一条扣款金额远超gross的记录,override=0(避免奖金干扰计算) | 分支7(net_before_tax<0) | 抛异常SQLSTATE=P1003,消息含扣款合计与应发金额 | audit_log的PAYROLL_START及deduct_items_loop产生的日志(如有)一并被回滚 |
| tax_bracket_boundary_upper_inclusive | 员工4(P1,base=3000),override使净额税前恰好=5000(第1档upper_bound) | 分支8边界值(闭区间上限) | APPROVED,税=150(全部按3%计入第1档),net=4850,不进入第2档 | payroll_approvals新增1条,tax_amount=150.00 |
| tax_bracket_boundary_over_by_one | 员工4,override使净额税前=5001(比第1档上限多1) | 分支8边界值(跨档) | APPROVED,税=150.10(第1档150+第2档1×10%=0.10),net=4850.90 | payroll_approvals新增1条,tax_amount=150.10 |
| tax_bracket_table_empty_zero_tax | 任一员工,g_fiscal_year对应的tax_brackets临时清空 | 分支8边界条件(税率表为空) | APPROVED,tax=0,net=税前净额全额,不抛异常(标记为潜在业务bug,不在本次迁移中"修正"其行为) | payroll_approvals新增1条,tax_amount=0.00 |
| null_employee_id_input | p_employee_id=NULL,其余入参有效 | 边界条件"null输入" | 抛异常SQLSTATE=P0002(SELECT INTO WHERE employee_id=NULL不命中任何行),与failure_employee_not_found走同一条异常路径 | 同failure_employee_not_found_audit_log_rollback |
| repeated_call_non_idempotent | 同一员工同一周期,连续调用两次,入参完全相同 | 边界条件"重复调用幂等性" | 两次都返回APPROVED,结果数值相同 | payroll_approvals新增2条记录(不是1条更新),audit_log对应新增2组PAYROLL_START+PAYROLL_APPROVED |
| global_var_audit_operator_not_leaked_across_calls | 同一连接内连续调用两次:第一次审批人A,第二次审批人B(不同员工/周期,避免其他因素干扰) | 全局变量g_audit_operator的读写时序(每次调用开头都被重新赋值,不依赖上次调用遗留值) | 两次调用各自的audit_log记录里operator字段分别对应各自的审批人姓名,不出现"第二次调用的日志被误写成第一次审批人姓名"的串号 | audit_log两组记录的operator字段与各自调用的审批人一一对应 |
| dry_run_branch_currently_unreachable | 任意合法入参组合 | 分支10(g_dry_run恒为FALSE),隐藏行为3 | 恒为APPROVED,永远不会返回PENDING_REVIEW(这是当前代码的真实行为,回归测试用于防止未来改动意外让这个死分支复活却没人注意到语义变化) | 恒有payroll_approvals新增记录,不会出现"跳过写入审批记录"的情况 |

## 覆盖检查
- 详设文档11条分支:1(初始化)/2(审批人查询+死代码COALESCE)/3(写日志)/4(gross计算+override+C档+P2002死代码)/5(权限校验)/6(游标扣款+跳过)/7(净额校验)/8(累进税+边界)/9(净额计算,隨其他场景一并验证)/10(dry_run死代码)/11(写入审批记录+日志)均有场景命中。
- 边界条件清单6条:null输入、空结果集、幂等性、隐式类型转换(体现在税额四舍五入的数值断言中,不单列场景)、税率分档边界、空税率表,均有对应场景。
- 全局变量读写时序:g_audit_operator场景已覆盖;g_fiscal_year每次调用都硬编码重置,时序场景意义不大,不单列;g_dry_run的时序等价于"永远是FALSE"这一稳定事实,由dry_run_branch_currently_unreachable场景覆盖。
- 并发会话隔离性已在详设文档阶段用独立实验验证(见文档"全局变量处理"章节),不重复放进这份单员工调用场景清单,该实验的产出已作为文档结论,不需要在UT里回归。
