package com.ark.arkinvestmentapi.participation;

import com.ark.arkinvestmentapi.funds.Fund;
import com.ark.arkinvestmentapi.investors.Investor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.UUID;

public interface ParticipationRepository extends JpaRepository<Participation, UUID> {
    boolean existsByClientIdAndFundIdAndInvestorId(UUID clientId, UUID fundId, UUID investorId);
    @Modifying
    @Query(value = "insert into fund_investors (client_id,id,fund_id,investor_id) values (:clientId,:id,:fundId,:investorId) on conflict (client_id,fund_id,investor_id) do nothing", nativeQuery = true)
    void associate(UUID clientId, UUID id, UUID fundId, UUID investorId);
    @Modifying
    @Query("delete from Participation p where p.clientId=:clientId and p.fundId=:fundId and p.investorId=:investorId")
    void remove(UUID clientId, UUID fundId, UUID investorId);
    @Query("select i from Investor i where i.clientId=:clientId and exists (select p.id from Participation p where p.clientId=:clientId and p.investorId=i.id and p.fundId=:fundId)")
    Page<Investor> investors(UUID clientId, UUID fundId, Pageable pageable);
    @Query("select f from Fund f where f.clientId=:clientId and exists (select p.id from Participation p where p.clientId=:clientId and p.fundId=f.id and p.investorId=:investorId)")
    Page<Fund> funds(UUID clientId, UUID investorId, Pageable pageable);
}
