package com.example.migration.repository;

import com.example.migration.domain.Approver;
import java.util.Optional;

public interface ApproverRepository {

    Optional<Approver> findById(int approverId);
}
