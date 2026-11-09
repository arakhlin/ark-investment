package com.ark.arkinvestmentapi.security;

import com.ark.arkinvestmentapi.common.ResourceNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import static com.ark.arkinvestmentapi.security.ClientRole.*;

@Service
@Transactional(readOnly = true)
public class ClientAccess {
    private final JdbcTemplate jdbc;
    public ClientAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record AppUser(UUID id, String issuer, String subject, UserType userType, UUID clientId,
                          ClientRole role, String status, Instant createdAt) {}
    public record Client(UUID id, String name, String status) {}

    static AppUser map(ResultSet r) throws SQLException {
        return new AppUser(r.getObject("id", UUID.class), r.getString("issuer"), r.getString("subject"),
                UserType.valueOf(r.getString("user_type")), r.getObject("client_id", UUID.class),
                ClientRole.valueOf(r.getString("role")), r.getString("status"), r.getTimestamp("created_at").toInstant());
    }

    public AppUser currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated())
            throw new BadCredentialsException("Authentication required");
        var jwt = token.getToken();
        var users = jdbc.query("select * from app_users where issuer=? and subject=? and status='ACTIVE'",
                (r,n) -> map(r), jwt.getClaimAsString("iss"), jwt.getSubject());
        if (users.size()!=1) throw new BadCredentialsException("Application identity unavailable");
        return users.getFirst();
    }

    private ClientRole require(UUID clientId, boolean administration, ClientRole... allowed) {
        var user=currentUser();
        // Check ownership before looking up the requested client, including for administration.
        if (user.userType()==UserType.CLIENT && !Objects.equals(user.clientId(),clientId))
            throw new ResourceNotFoundException("Client not found");
        if (jdbc.queryForList("select id from clients where id=? and (status='ACTIVE' or ?)", UUID.class,clientId,administration && user.role()==ARK_ADMIN).isEmpty())
            throw new ResourceNotFoundException("Client not found");
        if (Arrays.stream(allowed).noneMatch(user.role()::equals)) denied();
        return user.role();
    }

    public void financial(UUID clientId) { require(clientId,false,CLIENT_ADMIN,CLIENT_MANAGER,ARK_OPERATIONS); }
    public void reports(UUID clientId) { require(clientId,false,CLIENT_ADMIN,CLIENT_MANAGER,CLIENT_REPORT_VIEWER,ARK_OPERATIONS,ARK_REPORT_VIEWER); }
    public void administer(UUID clientId) { require(clientId,true,CLIENT_ADMIN,ARK_ADMIN); }
    public void arkAdmin() { if (currentUser().role()!=ARK_ADMIN) denied(); }
    private static void denied() { throw new AccessDeniedException("Insufficient permission"); }

    public List<Client> clients() {
        var user=currentUser();
        return jdbc.query("select id,name,status from clients where status='ACTIVE' and (? or id=?) order by id",
                (r,n)->new Client(r.getObject(1,UUID.class),r.getString(2),r.getString(3)),
                user.userType()==UserType.ARK,user.clientId());
    }
}
