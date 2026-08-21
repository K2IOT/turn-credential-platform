package com.k2iot.turncred.secret;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TurnSecretRepository extends JpaRepository<TurnSecret, String> {
    Optional<TurnSecret> findByRealm(String realm);
}
