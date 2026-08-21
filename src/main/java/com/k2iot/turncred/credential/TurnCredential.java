package com.k2iot.turncred.credential;

import java.util.List;

public record TurnCredential(String username, String password, int ttlSeconds, List<String> uris) {}
