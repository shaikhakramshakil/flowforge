package com.flowforge.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkflowRepository extends JpaRepository<Workflow, Long> {

    Optional<Workflow> findByName(String name);

    List<Workflow> findAllByOrderByCreatedAtDesc();

    @Query("select max(w.version) from Workflow w where w.name = :name")
    Optional<Integer> findMaxVersion(@Param("name") String name);

    @Query("select w from Workflow w where w.name = :name and w.version = :version")
    Optional<Workflow> findByNameAndVersion(@Param("name") String name, @Param("version") int version);
}