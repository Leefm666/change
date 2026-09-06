package com.example.migration.crossvalidation;

import com.example.migration.repository.mybatis.ApproverMapper;
import com.example.migration.repository.mybatis.DeductionItemMapper;
import com.example.migration.repository.mybatis.EmployeeMapper;
import com.example.migration.repository.mybatis.MyBatisApproverRepository;
import com.example.migration.repository.mybatis.MyBatisDeductionItemRepository;
import com.example.migration.repository.mybatis.MyBatisEmployeeRepository;
import com.example.migration.repository.mybatis.MyBatisPayrollApprovalRepository;
import com.example.migration.repository.mybatis.MyBatisPayrollAuditLogRepository;
import com.example.migration.repository.mybatis.MyBatisSalaryStandardRepository;
import com.example.migration.repository.mybatis.MyBatisTaxBracketRepository;
import com.example.migration.repository.mybatis.PayrollApprovalMapper;
import com.example.migration.repository.mybatis.PayrollAuditLogMapper;
import com.example.migration.repository.mybatis.SalaryStandardMapper;
import com.example.migration.repository.mybatis.TaxBracketMapper;
import com.example.migration.service.PayrollService;
import com.example.migration.support.JdbcProcedureExecutionRecorder;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * hr_payroll 交叉验证测试共用的搭建逻辑 + 种子数据,种子数据直接写在这个类里
 * (不拆单独 .sql 文件),供 HrPayrollProcedureBehaviorIT 和 PayrollJavaVsProcedureIT
 * 两个 IT 共用。
 *
 * 重置策略:基础字典表(employees/approvers/salary_standards/tax_brackets)用
 * INSERT ... ON DUPLICATE KEY UPDATE 幂等覆写,不依赖任何"先清空再插入"的执行顺序;
 * 依赖这些字典表的业务表(deduction_items/payroll_approvals/audit_log)用 TRUNCATE。
 * 不靠 JUnit 事务回滚重置——存储过程调用走独立 autocommit 连接,不在 Java 事务的回滚
 * 范围内,回滚只会清理 Java 侧的写入,清不掉存储过程侧的写入,两边状态会不对称
 * (这个教训记在 docs/rules/demo.settle_order.md 的"幂等性"一节)。
 */
final class PayrollTestFixtures {

    static final String URL = "jdbc:opengauss://127.0.0.1:15433/postgres";
    static final String USER = "migration_test";

    private PayrollTestFixtures() {
    }

    static String requirePassword() {
        String password = System.getenv("OG_TEST_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "交叉验证测试需要环境变量 OG_TEST_PASSWORD(openGauss migration_test 用户密码),未设置");
        }
        return password;
    }

    static DataSource buildDataSource(String password) {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.opengauss.Driver.class);
        ds.setUrl(URL);
        ds.setUsername(USER);
        ds.setPassword(password);
        return ds;
    }

    static JdbcProcedureExecutionRecorder buildProcedureRecorder(DataSource dataSource) {
        return new JdbcProcedureExecutionRecorder(() -> {
            try {
                return dataSource.getConnection();
            } catch (SQLException e) {
                throw new IllegalStateException("无法获取 openGauss 连接", e);
            }
        });
    }

    static PayrollService buildJavaService(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTransactionFactory(new SpringManagedTransactionFactory());
        SqlSessionFactory sqlSessionFactory = factoryBean.getObject();
        sqlSessionFactory.getConfiguration().addMapper(EmployeeMapper.class);
        sqlSessionFactory.getConfiguration().addMapper(ApproverMapper.class);
        sqlSessionFactory.getConfiguration().addMapper(SalaryStandardMapper.class);
        sqlSessionFactory.getConfiguration().addMapper(TaxBracketMapper.class);
        sqlSessionFactory.getConfiguration().addMapper(DeductionItemMapper.class);
        sqlSessionFactory.getConfiguration().addMapper(PayrollApprovalMapper.class);
        sqlSessionFactory.getConfiguration().addMapper(PayrollAuditLogMapper.class);
        SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);

        return new PayrollService(
                new MyBatisEmployeeRepository(sqlSessionTemplate.getMapper(EmployeeMapper.class)),
                new MyBatisApproverRepository(sqlSessionTemplate.getMapper(ApproverMapper.class)),
                new MyBatisSalaryStandardRepository(sqlSessionTemplate.getMapper(SalaryStandardMapper.class)),
                new MyBatisTaxBracketRepository(sqlSessionTemplate.getMapper(TaxBracketMapper.class)),
                new MyBatisDeductionItemRepository(sqlSessionTemplate.getMapper(DeductionItemMapper.class)),
                new MyBatisPayrollApprovalRepository(sqlSessionTemplate.getMapper(PayrollApprovalMapper.class)),
                new MyBatisPayrollAuditLogRepository(sqlSessionTemplate.getMapper(PayrollAuditLogMapper.class)));
    }

    /**
     * 幂等重置到基线:4个员工(1张三P5/S、2李四P3/A、3王五P4/C、4赵六P1/A)、
     * 2个审批人(10审批人A限额10万、11审批人B限额5000)、2025财年薪资标准与税率表、
     * 员工1周期202501的3条扣款(2条NORMAL共1200+1条DISPUTED 200)。
     * 员工5(P9,故意缺薪资标准)不在基线里,由需要它的测试用 upsertEmployee 单独插入。
     */
    static void resetAndSeed(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute("TRUNCATE TABLE hr.audit_log CASCADE");
            statement.execute("TRUNCATE TABLE hr.payroll_approvals CASCADE");
            statement.execute("TRUNCATE TABLE hr.deduction_items CASCADE");
            statement.execute("DELETE FROM hr.employees WHERE employee_id NOT IN (1, 2, 3, 4)");

            statement.execute("INSERT INTO hr.employees(employee_id, employee_name, grade, perf_rating) VALUES "
                    + "(1, '张三', 'P5', 'S'), (2, '李四', 'P3', 'A'), (3, '王五', 'P4', 'C'), (4, '赵六', 'P1', 'A') "
                    + "ON DUPLICATE KEY UPDATE employee_name=EXCLUDED.employee_name, grade=EXCLUDED.grade, "
                    + "perf_rating=EXCLUDED.perf_rating");

            statement.execute("INSERT INTO hr.approvers(approver_id, approver_name, approval_limit) VALUES "
                    + "(10, '审批人A', 100000.00), (11, '审批人B', 5000.00) "
                    + "ON DUPLICATE KEY UPDATE approver_name=EXCLUDED.approver_name, "
                    + "approval_limit=EXCLUDED.approval_limit");

            statement.execute("INSERT INTO hr.salary_standards(fiscal_year, grade, base_salary) VALUES "
                    + "(2025, 'P1', 3000.00), (2025, 'P3', 8000.00), (2025, 'P4', 12000.00), (2025, 'P5', 18000.00) "
                    + "ON DUPLICATE KEY UPDATE base_salary=EXCLUDED.base_salary");

            statement.execute("INSERT INTO hr.tax_brackets(fiscal_year, bracket_order, upper_bound, tax_rate) VALUES "
                    + "(2025, 1, 5000.00, 0.03), (2025, 2, 20000.00, 0.10), (2025, 3, NULL, 0.20) "
                    + "ON DUPLICATE KEY UPDATE upper_bound=EXCLUDED.upper_bound, tax_rate=EXCLUDED.tax_rate");

            statement.execute("INSERT INTO hr.deduction_items(employee_id, period_id, item_type, amount, item_status) VALUES "
                    + "(1, 202501, 'SOCIAL_INSURANCE', 800.00, 'NORMAL'), "
                    + "(1, 202501, 'HOUSING_FUND', 400.00, 'NORMAL'), "
                    + "(1, 202501, 'PENALTY', 200.00, 'DISPUTED')");
        }
    }

    static void upsertEmployee(DataSource dataSource, int employeeId, String name, String grade, String perfRating)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO hr.employees(employee_id, employee_name, grade, perf_rating) VALUES (?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE employee_name=EXCLUDED.employee_name, "
                             + "grade=EXCLUDED.grade, perf_rating=EXCLUDED.perf_rating")) {
            statement.setInt(1, employeeId);
            statement.setString(2, name);
            statement.setString(3, grade);
            statement.setString(4, perfRating);
            statement.execute();
        }
    }

    static void insertDeductionItem(
            DataSource dataSource, int employeeId, int periodId, String itemType, String amount, String status)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO hr.deduction_items(employee_id, period_id, item_type, amount, item_status) "
                             + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setInt(1, employeeId);
            statement.setInt(2, periodId);
            statement.setString(3, itemType);
            statement.setBigDecimal(4, new BigDecimal(amount));
            statement.setString(5, status);
            statement.execute();
        }
    }

    /** E7 边界场景专用:清空财年税率表。下一次 resetAndSeed 会通过 ON DUPLICATE KEY UPDATE 自动恢复,不需要手动还原。 */
    static void clearTaxBrackets(DataSource dataSource, int fiscalYear) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM hr.tax_brackets WHERE fiscal_year = ?")) {
            statement.setInt(1, fiscalYear);
            statement.execute();
        }
    }

    static int countAuditLog(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM hr.audit_log")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static int countApprovals(DataSource dataSource, int employeeId, int periodId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM hr.payroll_approvals WHERE employee_id = ? AND period_id = ?")) {
            statement.setInt(1, employeeId);
            statement.setInt(2, periodId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
