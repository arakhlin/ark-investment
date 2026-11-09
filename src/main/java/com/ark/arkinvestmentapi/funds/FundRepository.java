package com.ark.arkinvestmentapi.funds;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface FundRepository extends JpaRepository<Fund, UUID> {
    Optional<Fund> findByClientIdAndId(UUID clientId, UUID id);
    Page<Fund> findAllByClientId(UUID clientId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Fund e where e.clientId=:clientId and e.id=:id")
    Optional<Fund> findForUpdate(UUID clientId, UUID id);
}
