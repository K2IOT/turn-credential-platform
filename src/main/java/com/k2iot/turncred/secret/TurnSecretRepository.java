package com.k2iot.turncred.secret;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TurnSecretRepository extends JpaRepository<TurnSecret, TurnSecretId> {

    @Query("SELECT s FROM TurnSecret s WHERE s.id.realm = :realm AND s.validUntil IS NULL")
    Optional<TurnSecret> findCurrentByRealm(@Param("realm") String realm);

    @Query("SELECT s FROM TurnSecret s WHERE s.id.realm = :realm " +
           "AND (s.validUntil IS NULL OR s.validUntil > CURRENT_TIMESTAMP)")
    List<TurnSecret> findValidByRealm(@Param("realm") String realm);

    @Modifying
    @Query("DELETE FROM TurnSecret s WHERE s.id.realm = :realm " +
           "AND s.validUntil IS NOT NULL AND s.validUntil <= CURRENT_TIMESTAMP")
    void deleteExpiredForRealm(@Param("realm") String realm);
}
