package com.flowforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.domain.Workflow;
import com.flowforge.engine.WorkflowService;
import com.flowforge.engine.definition.WorkflowDefinition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/workflows")
public class WorkflowController {

    private final WorkflowService workflows;
    private final ObjectMapper mapper;

    public WorkflowController(WorkflowService workflows, ObjectMapper mapper) {
        this.workflows = workflows;
        this.mapper = mapper;
    }

    /** POST /workflows — create a new workflow version. */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody JsonNode body) {
        try {
            WorkflowDefinition def = mapper.treeToValue(body, WorkflowDefinition.class);
            Workflow created = workflows.create(def);
            return ResponseEntity.ok(mapper.createObjectNode()
                    .put("id", created.getId())
                    .put("name", created.getName())
                    .put("version", created.getVersion()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(error("invalid definition: " + e.getMessage()));
        }
    }

    /** GET /workflows — list workflow names with latest version. */
    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(workflows.snapshot());
    }

    private JsonNode error(String message) {
        return mapper.createObjectNode().put("error", message);
    }
}