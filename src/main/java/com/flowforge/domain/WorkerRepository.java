package com.flowforge.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkerRepository extends JpaRepository<Worker, String> {

    @Modifying
    @Query("update Worker w set w.status = 'UNHEALTHY' where w.lastHeartbeat < :cutoff and w.status = 'HEALTHY'")
    int markUnhealthy(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("update Worker w set w.lastHeartbeat = :now where w.id = :id")
    int touchHeartbeat(@Param("id") String id, @Param("now") Instant now);

    /** A worker proving life again after UNHEALTHY is trusted once more. */
    @Modifying
    @Query("update Worker w set w.status = 'HEALTHY' where w.id = :id and w.status = 'UNHEALTHY'")
    int revive(@Param("id") String id);

    Optional<Worker> findById(String id);
}