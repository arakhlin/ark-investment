package com.ark.arkinvestmentapi.investors;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface InvestorRepository extends JpaRepository<Investor, UUID> {
    Optional<Investor> findByClientIdAndId(UUID clientId, UUID id);
    Page<Investor> findAllByClientId(UUID clientId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Investor e where e.clientId=:clientId and e.id=:id")
    Optional<Investor> findForUpdate(UUID clientId, UUID id);
}
