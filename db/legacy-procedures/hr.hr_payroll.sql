-- 演示用高复杂度存储过程包:员工薪资核算与审批 hr_payroll
-- 覆盖:多入参(含可空入参驱动分叉)、包全局变量(3种不同语义)、
--      多层嵌套调用(public->private->private)、游标遍历(跳过不中断)、
--      多处显式异常(不同SQLSTATE)+ 外层EXCEPTION块、私有方法。
-- 用于压测 "openGauss 存储过程 -> Java 迁移" skill(sp-refactor-java)全流程。

CREATE SCHEMA IF NOT EXISTS hr;

CREATE TABLE IF NOT EXISTS hr.employees (
    employee_id     INT PRIMARY KEY,
    employee_name   VARCHAR(100) NOT NULL,
    grade           VARCHAR(2)  NOT NULL,   -- P1~P7,决定基本工资标准
    perf_rating     VARCHAR(1)  NOT NULL DEFAULT 'B'  -- S/A/B/C,决定奖金系数
);

CREATE TABLE IF NOT EXISTS hr.approvers (
    approver_id     INT PRIMARY KEY,
    approver_name   VARCHAR(100) NOT NULL,
    approval_limit  NUMERIC(12,2) NOT NULL   -- 该审批人可批准的最高净额
);

CREATE TABLE IF NOT EXISTS hr.salary_standards (
    fiscal_year     INT NOT NULL,
    grade           VARCHAR(2) NOT NULL,
    base_salary     NUMERIC(12,2) NOT NULL,
    PRIMARY KEY (fiscal_year, grade)
);

CREATE TABLE IF NOT EXISTS hr.tax_brackets (
    fiscal_year     INT NOT NULL,
    bracket_order   INT NOT NULL,        -- 1=最低档,数字越大税率越高
    upper_bound     NUMERIC(12,2),       -- 本档上限(含),NULL表示无上限(最高档)
    tax_rate        NUMERIC(5,4) NOT NULL,
    PRIMARY KEY (fiscal_year, bracket_order)
);

CREATE TABLE IF NOT EXISTS hr.deduction_items (
    deduction_id    SERIAL PRIMARY KEY,
    employee_id     INT NOT NULL,
    period_id       INT NOT NULL,
    item_type       VARCHAR(30) NOT NULL, -- SOCIAL_INSURANCE/HOUSING_FUND/PENALTY 等
    amount          NUMERIC(12,2) NOT NULL,
    item_status     VARCHAR(20) NOT NULL DEFAULT 'NORMAL' -- NORMAL/DISPUTED
);

CREATE TABLE IF NOT EXISTS hr.payroll_approvals (
    approval_id     SERIAL PRIMARY KEY,
    employee_id     INT NOT NULL,
    period_id       INT NOT NULL,
    approver_id     INT NOT NULL,
    gross_amount    NUMERIC(12,2) NOT NULL,
    deduction_amount NUMERIC(12,2) NOT NULL,
    tax_amount      NUMERIC(12,2) NOT NULL,
    net_amount      NUMERIC(12,2) NOT NULL,
    status          VARCHAR(20) NOT NULL,  -- APPROVED
    approved_at     TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS hr.audit_log (
    log_id          SERIAL PRIMARY KEY,
    operator        VARCHAR(100),
    action          VARCHAR(50) NOT NULL,
    detail          TEXT,
    logged_at       TIMESTAMP NOT NULL DEFAULT now()
);

-- ============================================================
-- 包头(spec):只有这里声明的成员才是 public
-- 三个全局变量刻意设计成三种不同语义:
--   g_fiscal_year    -- 可被参数传递替代的共享中间态
--   g_audit_operator -- 单次调用链内的隐式上下文,不跨外部调用
--   g_dry_run        -- 影响多个子过程是否产生真实副作用的开关状态
-- ============================================================
CREATE OR REPLACE PACKAGE hr.hr_payroll IS
    g_fiscal_year     INT;
    g_audit_operator  VARCHAR(100);
    g_dry_run         BOOLEAN;

    PROCEDURE calculate_and_approve_payroll(
        p_employee_id     INT,
        p_period_id       INT,
        p_approver_id     INT,
        p_bonus_override  NUMERIC,
        OUT o_net_salary  NUMERIC,
        OUT o_status      VARCHAR
    );
END hr_payroll;

-- ============================================================
-- 包体(body):private 成员 calc_base_salary/calc_bonus/
-- calc_progressive_tax/check_approval_authority/deduct_items_loop/
-- write_audit_log 只在包内可调,不对外暴露。
-- ============================================================
CREATE OR REPLACE PACKAGE BODY hr.hr_payroll IS

    -- private:按员工等级+当前财年查基本工资标准
    FUNCTION calc_base_salary(
        p_employee_id INT,
        p_period_id   INT
    ) RETURN NUMERIC IS
        v_grade VARCHAR(2);
        v_base  NUMERIC(12,2);
    BEGIN
        SELECT grade INTO v_grade FROM hr.employees WHERE employee_id = p_employee_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION '员工不存在: %', p_employee_id USING ERRCODE = 'P2001';
        END IF;

        SELECT base_salary INTO v_base
          FROM hr.salary_standards
         WHERE fiscal_year = g_fiscal_year AND grade = v_grade;
        IF NOT FOUND THEN
            RAISE EXCEPTION '财年 % 缺少等级 % 的薪资标准', g_fiscal_year, v_grade USING ERRCODE = 'P2002';
        END IF;

        RETURN v_base;
    END;

    -- private:算奖金。p_override 非NULL直接用,否则按绩效等级系数×基本工资
    -- 嵌套调用 calc_base_salary(调用链深度: public -> calc_bonus -> calc_base_salary)
    FUNCTION calc_bonus(
        p_employee_id INT,
        p_period_id   INT,
        p_override    NUMERIC
    ) RETURN NUMERIC IS
        v_rating VARCHAR(1);
        v_coef   NUMERIC(4,2);
        v_base   NUMERIC(12,2);
    BEGIN
        IF p_override IS NOT NULL THEN
            RETURN p_override;
        END IF;

        SELECT perf_rating INTO v_rating FROM hr.employees WHERE employee_id = p_employee_id;

        CASE v_rating
            WHEN 'S' THEN v_coef := 0.50;
            WHEN 'A' THEN v_coef := 0.30;
            WHEN 'B' THEN v_coef := 0.15;
            ELSE          v_coef := 0.00;  -- C档无奖金
        END CASE;

        v_base := calc_base_salary(p_employee_id, p_period_id);
        RETURN ROUND(v_base * v_coef, 2);
    END;

    -- private:分级累进税率,按 g_fiscal_year 查对应税率表版本
    FUNCTION calc_progressive_tax(
        p_gross NUMERIC
    ) RETURN NUMERIC IS
        v_remaining  NUMERIC(12,2) := p_gross;
        v_tax        NUMERIC(12,2) := 0;
        v_prev_upper NUMERIC(12,2) := 0;
        v_bracket    RECORD;
        CURSOR v_bracket_cursor IS
            SELECT bracket_order, upper_bound, tax_rate
              FROM hr.tax_brackets
             WHERE fiscal_year = g_fiscal_year
             ORDER BY bracket_order;
    BEGIN
        OPEN v_bracket_cursor;
        LOOP
            FETCH v_bracket_cursor INTO v_bracket;
            EXIT WHEN NOT FOUND OR v_remaining <= 0;

            IF v_bracket.upper_bound IS NULL THEN
                -- 最高档,无上限,剩余部分全部按此税率计税
                v_tax := v_tax + ROUND(v_remaining * v_bracket.tax_rate, 2);
                v_remaining := 0;
            ELSE
                DECLARE
                    v_bracket_span    NUMERIC(12,2) := v_bracket.upper_bound - v_prev_upper;
                    v_taxable_in_band NUMERIC(12,2) := LEAST(v_remaining, v_bracket_span);
                BEGIN
                    v_tax := v_tax + ROUND(v_taxable_in_band * v_bracket.tax_rate, 2);
                    v_remaining := v_remaining - v_taxable_in_band;
                    v_prev_upper := v_bracket.upper_bound;
                END;
            END IF;
        END LOOP;
        CLOSE v_bracket_cursor;

        RETURN v_tax;
    END;

    -- private:审批权限校验。审批人不存在 -> P2003;金额超权限 -> P1001
    PROCEDURE check_approval_authority(
        p_approver_id INT,
        p_amount      NUMERIC
    ) IS
        v_limit NUMERIC(12,2);
    BEGIN
        SELECT approval_limit INTO v_limit FROM hr.approvers WHERE approver_id = p_approver_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION '审批人不存在: %', p_approver_id USING ERRCODE = 'P2003';
        END IF;

        IF p_amount > v_limit THEN
            RAISE EXCEPTION '审批金额 % 超过审批人 % 的权限上限 %', p_amount, p_approver_id, v_limit
                USING ERRCODE = 'P1001';
        END IF;
    END;

    -- private:写审计日志,读全局变量 g_audit_operator。g_dry_run=TRUE 时不落库直接返回。
    PROCEDURE write_audit_log(
        p_action VARCHAR,
        p_detail VARCHAR
    ) IS
    BEGIN
        IF g_dry_run THEN
            RETURN;
        END IF;

        INSERT INTO hr.audit_log(operator, action, detail)
        VALUES (g_audit_operator, p_action, p_detail);
    END;

    -- private:游标遍历当期扣款项累加合计。命中 DISPUTED 状态的条目跳过但不中断游标。
    FUNCTION deduct_items_loop(
        p_employee_id INT,
        p_period_id   INT
    ) RETURN NUMERIC IS
        v_total NUMERIC(12,2) := 0;
        v_item  RECORD;
        CURSOR v_item_cursor IS
            SELECT deduction_id, item_type, amount, item_status
              FROM hr.deduction_items
             WHERE employee_id = p_employee_id AND period_id = p_period_id
             ORDER BY deduction_id;
    BEGIN
        OPEN v_item_cursor;
        LOOP
            FETCH v_item_cursor INTO v_item;
            EXIT WHEN NOT FOUND;

            IF v_item.item_status = 'DISPUTED' THEN
                write_audit_log('DEDUCTION_SKIPPED',
                    '跳过争议中的扣款项 deduction_id=' || v_item.deduction_id);
                CONTINUE;
            END IF;

            v_total := v_total + v_item.amount;
        END LOOP;
        CLOSE v_item_cursor;

        RETURN v_total;
    END;

    -- ========================================================
    -- public:主流程。
    -- 1. 初始化三个全局变量(g_fiscal_year 按当前年份固定取2025演示用;
    --    g_audit_operator 记录审批人上下文;g_dry_run 由 p_bonus_override
    --    以外的隐式规则决定——此处固定为 FALSE,试算场景由上层调用方控制)
    -- 2. 审批权限校验(check_approval_authority) -> 失败抛异常终止
    -- 3. 算 gross = calc_base_salary + calc_bonus(嵌套两层调用)
    -- 4. deduct_items_loop 拿扣款合计(游标,跳过DISPUTED不中断)
    -- 5. net_before_tax = gross - deductions,若 <0 则数据异常 P1003
    -- 6. calc_progressive_tax 算税
    -- 7. 写审批记录 + 审计日志,返回净额
    -- 整体包在 EXCEPTION WHEN OTHERS 块中:失败时是否回滚前面已写的
    -- 审计日志,是本例故意留白、交给交叉验证(Step 6)去发现的隐藏行为。
    -- ========================================================
    PROCEDURE calculate_and_approve_payroll(
        p_employee_id     INT,
        p_period_id       INT,
        p_approver_id     INT,
        p_bonus_override  NUMERIC,
        OUT o_net_salary  NUMERIC,
        OUT o_status      VARCHAR
    ) IS
        v_approver_name    VARCHAR(100);
        v_gross             NUMERIC(12,2);
        v_deductions         NUMERIC(12,2);
        v_net_before_tax    NUMERIC(12,2);
        v_tax                NUMERIC(12,2);
        v_net                NUMERIC(12,2);
    BEGIN
        g_fiscal_year    := 2025;
        g_dry_run        := FALSE;

        SELECT approver_name INTO v_approver_name FROM hr.approvers WHERE approver_id = p_approver_id;
        g_audit_operator := COALESCE(v_approver_name, 'approver#' || p_approver_id);

        write_audit_log('PAYROLL_START',
            '开始核算 employee_id=' || p_employee_id || ' period_id=' || p_period_id);

        v_gross := calc_base_salary(p_employee_id, p_period_id)
                 + calc_bonus(p_employee_id, p_period_id, p_bonus_override);

        check_approval_authority(p_approver_id, v_gross);

        v_deductions := deduct_items_loop(p_employee_id, p_period_id);

        v_net_before_tax := v_gross - v_deductions;
        IF v_net_before_tax < 0 THEN
            RAISE EXCEPTION '扣款合计 % 超过应发工资 %,数据异常', v_deductions, v_gross
                USING ERRCODE = 'P1003';
        END IF;

        v_tax := calc_progressive_tax(v_net_before_tax);
        v_net := v_net_before_tax - v_tax;

        IF g_dry_run THEN
            o_net_salary := v_net;
            o_status := 'PENDING_REVIEW';
            RETURN;
        END IF;

        INSERT INTO hr.payroll_approvals(
            employee_id, period_id, approver_id,
            gross_amount, deduction_amount, tax_amount, net_amount, status
        ) VALUES (
            p_employee_id, p_period_id, p_approver_id,
            v_gross, v_deductions, v_tax, v_net, 'APPROVED'
        );

        write_audit_log('PAYROLL_APPROVED',
            '核算通过,净额=' || v_net);

        o_net_salary := v_net;
        o_status := 'APPROVED';
    EXCEPTION
        WHEN OTHERS THEN
            o_net_salary := NULL;
            o_status := 'FAILED: ' || SQLERRM;
            RAISE;
    END;

END hr_payroll;
