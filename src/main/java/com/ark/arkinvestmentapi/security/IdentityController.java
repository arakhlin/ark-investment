package com.ark.arkinvestmentapi.security;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    private final ClientAccess access;
    private final AdministrationService administration;
    public IdentityController(ClientAccess access, AdministrationService administration) {
        this.access=access; this.administration=administration;
    }
    @GetMapping("/me") public ClientAccess.AppUser me() { return access.currentUser(); }
    @GetMapping({"/me/clients","/clients"}) public List<ClientAccess.Client> clients() { return access.clients(); }
    @PostMapping("/clients") @ResponseStatus(HttpStatus.CREATED)
    public ClientAccess.Client create(@Valid @RequestBody AdministrationService.ClientChange input) { return administration.createClient(input); }
    @PutMapping("/clients/{clientId}")
    public ClientAccess.Client replace(@PathVariable UUID clientId, @Valid @RequestBody AdministrationService.ClientChange input) { return administration.replaceClient(clientId,input); }
    @GetMapping("/users") public List<ClientAccess.AppUser> users() { return administration.users(null); }
    @GetMapping("/clients/{clientId}/users") public List<ClientAccess.AppUser> users(@PathVariable UUID clientId) { return administration.users(clientId); }
    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED)
    public ClientAccess.AppUser createUser(@Valid @RequestBody AdministrationService.NewUser input) { return administration.createUser(input); }
    @PutMapping("/users/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(@PathVariable UUID userId, @Valid @RequestBody AdministrationService.UserChange input) { administration.changeUser(null,userId,input); }
    @PutMapping("/clients/{clientId}/users/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(@PathVariable UUID clientId, @PathVariable UUID userId, @Valid @RequestBody AdministrationService.UserChange input) { administration.changeUser(clientId,userId,input); }
}
