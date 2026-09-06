package com.example.migration.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.migration.domain.PayrollResult;
import com.example.migration.exception.ApprovalLimitExceededException;
import com.example.migration.exception.NegativeNetSalaryException;
import com.example.migration.exception.PayrollEntityNotFoundException;
import com.example.migration.legacy.LegacyPayrollProcedureFailureException;
import com.example.migration.routing.PayrollOutcome;
import com.example.migration.routing.PayrollOutcome.RouteTarget;
import com.example.migration.routing.PayrollRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {PayrollController.class, PayrollApiExceptionHandler.class})
class PayrollControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PayrollRoutingService routingService;

    @Test
    void calculateAndApprove_success_returnsResultAndRoutedTo() throws Exception {
        when(routingService.calculateAndApprovePayroll(1, 202501, 10, null)).thenReturn(new PayrollOutcome(
                new PayrollResult(new BigDecimal("22990.00"), "APPROVED"), RouteTarget.LEGACY_PROCEDURE));

        mockMvc.perform(post("/api/payroll/calculate-and-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PayrollRequest(1, 202501, 10, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netSalary").value(22990.00))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.routedTo").value("LEGACY_PROCEDURE"));
    }

    @Test
    void calculateAndApprove_entityNotFound_returns404WithSqlState() throws Exception {
        when(routingService.calculateAndApprovePayroll(eq(999), eq(202501), eq(10), any()))
                .thenThrow(new PayrollEntityNotFoundException("员工不存在: 999"));

        mockMvc.perform(post("/api/payroll/calculate-and-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PayrollRequest(999, 202501, 10, null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.sqlState").value("P0002"));
    }

    @Test
    void calculateAndApprove_approvalLimitExceeded_returns409WithSqlState() throws Exception {
        when(routingService.calculateAndApprovePayroll(eq(1), eq(202501), eq(11), any()))
                .thenThrow(new ApprovalLimitExceededException(
                        new BigDecimal("27000.00"), 11, new BigDecimal("5000.00")));

        mockMvc.perform(post("/api/payroll/calculate-and-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PayrollRequest(1, 202501, 11, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.sqlState").value("P1001"));
    }

    @Test
    void calculateAndApprove_negativeNetSalary_returns409WithSqlState() throws Exception {
        when(routingService.calculateAndApprovePayroll(eq(3), eq(202501), eq(10), any()))
                .thenThrow(new NegativeNetSalaryException(new BigDecimal("999999.00"), new BigDecimal("12000.00")));

        mockMvc.perform(post("/api/payroll/calculate-and-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PayrollRequest(3, 202501, 10, BigDecimal.ZERO))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.sqlState").value("P1003"));
    }

    @Test
    void calculateAndApprove_legacyProcedureFailure_returns409WithSqlState() throws Exception {
        when(routingService.calculateAndApprovePayroll(eq(1), eq(202501), eq(11), any()))
                .thenThrow(new LegacyPayrollProcedureFailureException(
                        "P1001", "审批金额 27000.00 超过审批人 11 的权限上限 5000.00", null));

        mockMvc.perform(post("/api/payroll/calculate-and-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PayrollRequest(1, 202501, 11, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.sqlState").value("P1001"));
    }
}
