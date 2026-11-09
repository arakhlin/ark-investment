package com.ark.arkinvestmentapi.security;

import com.ark.arkinvestmentapi.common.*;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.ark.arkinvestmentapi.security.ClientRole.*;

@Service
@Transactional(readOnly=true)
public class AdministrationService {
    public enum ClientStatus { ACTIVE, SUSPENDED }
    public enum UserStatus { ACTIVE, DISABLED }
    public record ClientChange(@NotBlank @Size(max=200) String name, @NotNull ClientStatus status) {}
    public static final class NewUser {
        @NotBlank @Size(max=1000) private String issuer;
        @NotBlank @Size(max=255) private String subject;
        @NotNull private UserType userType;
        private UUID clientId;
        @NotNull private ClientRole role;
        @NotNull private UserStatus status;

        public NewUser() {}

        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        public NewUser(String issuer, String subject, UserType userType, UUID clientId,
                       ClientRole role, UserStatus status) {
            this.issuer = issuer;
            this.subject = subject;
            this.userType = userType;
            this.clientId = clientId;
            this.role = role;
            this.status = status;
        }

        public String issuer() { return issuer; }
        public String subject() { return subject; }
        public UserType userType() { return userType; }
        public UUID clientId() { return clientId; }
        public ClientRole role() { return role; }
        public UserStatus status() { return status; }

        public void setIssuer(String issuer) { this.issuer = issuer; }
        public void setSubject(String subject) { this.subject = subject; }
        public void setUserType(UserType userType) { this.userType = userType; }
        // Omission is valid for ARK users; an explicitly supplied null is invalid JSON input.
        @JsonSetter(nulls = Nulls.FAIL)
        public void setClientId(UUID clientId) { this.clientId = clientId; }
        public void setRole(ClientRole role) { this.role = role; }
        public void setStatus(UserStatus status) { this.status = status; }
    }
    public record UserChange(@NotNull ClientRole role, @NotNull UserStatus status) {}
    private final JdbcTemplate jdbc;
    private final ClientAccess access;
    private final String trustedIssuer;
    private final ObjectMapper json = new ObjectMapper();
    public AdministrationService(JdbcTemplate jdbc, ClientAccess access, @Value("${ark.security.issuer}") String trustedIssuer) { this.jdbc=jdbc; this.access=access; this.trustedIssuer=trustedIssuer; }

    @Transactional
    public ClientAccess.Client createClient(ClientChange input) {
        access.arkAdmin();
        var actor=access.currentUser();
        var id=UUID.randomUUID();
        jdbc.update("insert into clients(id,name,status) values (?,?,?)",id,input.name(),input.status().name());
        var result=new ClientAccess.Client(id,input.name(),input.status().name());
        audit(actor,"CLIENT",id,null,result);
        return result;
    }
    @Transactional
    public ClientAccess.Client replaceClient(UUID id, ClientChange input) {
        access.arkAdmin();
        var actor=access.currentUser();
        var previous=jdbc.query("select id,name,status from clients where id=? for update",
                (r,n)->new ClientAccess.Client(r.getObject(1,UUID.class),r.getString(2),r.getString(3)),id)
                .stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Client not found"));
        access.arkAdmin();
        if(jdbc.update("update clients set name=?,status=? where id=?",input.name(),input.status().name(),id)!=1)
            throw new ResourceNotFoundException("Client not found");
        var result=new ClientAccess.Client(id,input.name(),input.status().name());
        audit(actor,"CLIENT",id,previous,result);
        return result;
    }
    public List<ClientAccess.AppUser> users(UUID clientId) {
        if(clientId==null) access.arkAdmin(); else access.administer(clientId);
        return jdbc.query("select * from app_users where (? or client_id=?) order by id",(r,n)->ClientAccess.map(r),clientId==null,clientId);
    }
    private void lockClient(UUID clientId) {
        access.administer(clientId);
        jdbc.queryForList("select id from clients where id=? for update",clientId);
        access.administer(clientId);
    }
    @Transactional
    public ClientAccess.AppUser createUser(NewUser input) {
        var actor=access.currentUser();
        if(!input.role().compatible(input.userType()) || (input.userType()==UserType.CLIENT)!=(input.clientId()!=null))
            throw new IllegalArgumentException("User type, role and client ownership are incompatible");
        if(input.userType()==UserType.ARK) { access.arkAdmin(); lockArk(); access.arkAdmin(); }
        else lockClient(input.clientId());
        if(actor.userType()==UserType.CLIENT && input.userType()!=UserType.CLIENT)
            throw new AccessDeniedException("Insufficient permission");
        // The first active administrator is provisioned by ARK_ADMIN through this endpoint.
        if(input.clientId()!=null && actor.role()!=ARK_ADMIN && !hasAdmin(input.clientId()))
            throw new StateConflictException("First Client Administrator must be provisioned by ARK_ADMIN");
        requireTrusted(input.issuer(),input.subject(),input.userType(),input.clientId());
        var id=UUID.randomUUID();
        try { jdbc.update("insert into app_users(id,issuer,subject,user_type,client_id,role,status) values (?,?,?,?,?,?,?)",
                id,input.issuer(),input.subject(),input.userType().name(),input.clientId(),input.role().name(),input.status().name());
        } catch(DuplicateKeyException e) { throw new StateConflictException("Identity binding unavailable"); }
        var result=find(id);
        audit(actor,"USER",id,null,result);
        return result;
    }
    private boolean hasAdmin(UUID clientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from app_users where client_id=? and role='CLIENT_ADMIN' and status='ACTIVE')",Boolean.class,clientId));
    }
    private ClientAccess.AppUser find(UUID id) {
        return jdbc.query("select * from app_users where id=?",(r,n)->ClientAccess.map(r),id).stream().findFirst()
                .orElseThrow(()->new ResourceNotFoundException("User not found"));
    }
    @Transactional
    public void changeUser(UUID scope, UUID id, UserChange input) {
        if(scope==null) access.arkAdmin(); else access.administer(scope);
        var user=find(id);
        if(scope!=null && !scope.equals(user.clientId())) throw new ResourceNotFoundException("User not found");
        if(user.clientId()!=null) lockClient(user.clientId()); else lockArk();
        // Ownership and identity are immutable. Lock and reload before last-admin validation.
        jdbc.queryForList("select id from app_users where id=? for update",id);
        user=find(id);
        if(scope==null) access.arkAdmin(); else access.administer(scope);
        if(!input.role().compatible(user.userType())) throw new IllegalArgumentException("Role incompatible with user type");
        if(user.role()==CLIENT_ADMIN && user.status().equals("ACTIVE") &&
                (input.role()!=CLIENT_ADMIN || input.status()!=UserStatus.ACTIVE)) {
            int count=jdbc.queryForObject("select count(*) from app_users u join trusted_identities t on t.issuer=u.issuer and t.subject=u.subject and t.user_type=u.user_type and t.client_id=u.client_id where u.client_id=? and u.role='CLIENT_ADMIN' and u.status='ACTIVE' and u.id<>? and u.issuer=?",Integer.class,user.clientId(),id,trustedIssuer);
            if(count==0) throw new StateConflictException("Last active Client Administrator is required");
        }
        if((input.role()==ARK_ADMIN || input.role()==CLIENT_ADMIN) && input.status()==UserStatus.ACTIVE)
            requireTrusted(user.issuer(),user.subject(),user.userType(),user.clientId());
        if(user.role()==ARK_ADMIN && user.status().equals("ACTIVE") &&
                (input.role()!=ARK_ADMIN || input.status()!=UserStatus.ACTIVE)) {
            int count=jdbc.queryForObject("""
                    select count(*) from app_users u join trusted_identities t
                    on t.issuer=u.issuer and t.subject=u.subject and t.user_type='ARK'
                    where u.role='ARK_ADMIN' and u.status='ACTIVE' and u.id<>? and u.issuer=?
                    """,Integer.class,id,trustedIssuer);
            if(count==0) throw new StateConflictException("Last active verified ARK Administrator is required");
        }
        var actor=access.currentUser();
        jdbc.update("update app_users set role=?,status=? where id=?",input.role().name(),input.status().name(),id);
        audit(actor,"USER",id,user,find(id));
    }
    private void lockArk() {
        // Shared with trusted bootstrap. Transaction scope releases this lock on commit/rollback.
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext('ark-initial-administrator'))");
    }
    private void requireTrusted(String issuer, String subject, UserType type, UUID clientId) {
        boolean approved=trustedIssuer.equals(issuer) && Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from trusted_identities where issuer=? and subject=? and user_type=? and client_id is not distinct from ?)",
                Boolean.class,issuer,subject,type.name(),clientId));
        // Same response for unavailable approvals and already bound identities, regardless of scope.
        if(!approved) throw new StateConflictException("Identity binding unavailable");
    }
    private void audit(ClientAccess.AppUser actor, String type, UUID id, Object previous, Object next) {
        jdbc.update("""
                insert into administrative_audit(id,actor_user_id,actor_issuer,actor_subject,
                    target_type,target_id,operation,previous_values,new_values)
                values (?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                """,UUID.randomUUID(),actor.id(),actor.issuer(),actor.subject(),type,id,
                previous==null?"CREATE":"UPDATE",previous==null?null:json.writeValueAsString(previous),json.writeValueAsString(next));
    }
}
