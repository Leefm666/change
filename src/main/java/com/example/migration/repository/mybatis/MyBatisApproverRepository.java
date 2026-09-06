package com.example.migration.repository.mybatis;

import com.example.migration.domain.Approver;
import com.example.migration.repository.ApproverRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisApproverRepository implements ApproverRepository {

    private final ApproverMapper approverMapper;

    public MyBatisApproverRepository(ApproverMapper approverMapper) {
        this.approverMapper = approverMapper;
    }

    @Override
    public Optional<Approver> findById(int approverId) {
        return Optional.ofNullable(approverMapper.findById(approverId));
    }
}
