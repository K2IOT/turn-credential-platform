package com.k2iot.turncred.credential;

public class UserNotRegisteredException extends RuntimeException {
    public UserNotRegisteredException(String userId) {
        super("User not registered or suspended: " + userId);
    }
}
