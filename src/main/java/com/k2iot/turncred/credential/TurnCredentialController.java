package com.k2iot.turncred.credential;

import com.k2iot.turncred.auth.CurrentTenantHolder;
import com.k2iot.turncred.credential.dto.IssueCredentialRequest;
import com.k2iot.turncred.credential.dto.TurnCredentialResponse;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/turn-credentials")
public class TurnCredentialController {

    private final TurnCredentialService credentialService;

    public TurnCredentialController(TurnCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public TurnCredentialResponse issue(@RequestBody(required = false) IssueCredentialRequest request) {
        var tenant = CurrentTenantHolder.get();
        String userId = (request != null && request.userId() != null) ? request.userId() : UUID.randomUUID().toString();

        TurnCredential credential = credentialService.issueCredential(tenant, userId);

        return new TurnCredentialResponse(credential.username(), credential.password(),
                credential.ttlSeconds(), credential.uris());
    }
}
