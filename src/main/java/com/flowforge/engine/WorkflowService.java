package com.flowforge.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.*;
import com.flowforge.engine.definition.DefinitionValidator;
import com.flowforge.engine.definition.WorkflowDefinition;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class WorkflowService {

    private final WorkflowRepository workflows;
    private final ObjectMapper mapper;

    public WorkflowService(WorkflowRepository workflows, ObjectMapper mapper) {
        this.workflows = workflows;
        this.mapper = mapper;
    }

    public Workflow create(WorkflowDefinition def) {
        def.setVersion(null); // version is always engine-assigned
        DefinitionValidator.validate(def);
        // Two concurrent creates for the same name can compute the same next
        // version; UNIQUE(name, version) rejects the loser, which recomputes
        // and retries. Each attempt flushes in its own transaction (no outer
        // @Transactional), so the failed attempt leaves nothing behind.
        // Worst case the loser needs one attempt per concurrent creator;
        // 10 covers any realistic deploy stampede (the test hammers 4-way).
        DataIntegrityViolationException last = null;
        for (int i = 0; i < 10; i++) {
            try {
                return insert(def);
            } catch (DataIntegrityViolationException e) {
                last = e;
            }
        }
        throw last;
    }

    private Workflow insert(WorkflowDefinition def) {
        int version = workflows.findMaxVersion(def.getName()).map(v -> v + 1).orElse(1);
        def.setVersion(version);
        try {
            Workflow w = new Workflow();
            w.setName(def.getName());
            w.setVersion(version);
            w.setDefinition(mapper.writeValueAsString(def));
            return workflows.saveAndFlush(w);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid definition JSON", e);
        }
    }

    public Optional<Workflow> latestVersion(String name) {
        return workflows.findMaxVersion(name)
            .flatMap(v -> workflows.findByNameAndVersion(name, v));
    }

    /** Every workflow version, newest first, for the listing endpoint. */
    @Transactional(readOnly = true)
    public List<WorkflowSnapshot> snapshot() {
        return workflows.findAllByOrderByCreatedAtDesc().stream()
            .map(w -> new WorkflowSnapshot(w.getId(), w.getName(), w.getVersion(), w.getDefinition()))
            .toList();
    }
}