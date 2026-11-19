package com.tiwalo.banking_core_api.shared;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public record ApiProblem(String type, String title, int status, String code) {

    public static final ApiProblem INVALID_REQUEST =
            new ApiProblem("urn:tiwalo:problem:invalid-request", "Invalid request", 400, "INVALID_REQUEST");
    public static final ApiProblem UNAUTHORIZED =
            new ApiProblem("urn:tiwalo:problem:unauthorized", "Authentication required", 401, "UNAUTHORIZED");
    public static final ApiProblem FORBIDDEN =
            new ApiProblem("urn:tiwalo:problem:forbidden", "Permission denied", 403, "FORBIDDEN");
    public static final ApiProblem NOT_FOUND =
            new ApiProblem("urn:tiwalo:problem:not-found", "Resource not found", 404, "NOT_FOUND");

    public ResponseEntity<ApiProblem> response() {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(this);
    }
}
