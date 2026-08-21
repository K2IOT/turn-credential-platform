package com.k2iot.turncred.credential.dto;

import java.util.List;

public record TurnCredentialResponse(String username, String password, int ttlSeconds, List<String> uris) {}
