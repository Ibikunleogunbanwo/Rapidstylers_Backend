package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.pojo.BaseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Single source of truth for turning a {@link BaseResponse} into an HTTP
 * response whose status code actually matches the outcome.
 *
 * The API body has always carried its own application status
 * ({@code statusCode}: {@code "200"} success, {@code "400"} business error,
 * {@code "401"}/{@code "403"} auth failures). Historically controllers
 * returned HTTP 200 for every business error, forcing clients to read the
 * body before trusting the transport status. {@link #respond} closes that
 * gap: a {@code statusCode} of 2xx maps to the matching 2xx HTTP status and
 * any 4xx/5xx application code maps to the same real HTTP error status.
 *
 * The response body is unchanged — {@code statusCode}, {@code message},
 * {@code data}, {@code token} stay exactly as before, so existing clients
 * that parse the JSON body keep working.
 */
public final class ApiResponses {

    private ApiResponses() {
    }

    /** Wraps the body in a ResponseEntity whose HTTP status mirrors its application statusCode. */
    public static ResponseEntity<BaseResponse> respond(BaseResponse body) {
        return ResponseEntity.status(httpStatus(body)).body(body);
    }

    /**
     * Resolves the HTTP status for a BaseResponse:
     * success codes ({@code "2xx"}) → the matching 2xx status (200 in practice);
     * {@code "401"} → 401, {@code "403"} → 403, {@code "404"} → 404, etc.;
     * anything else (including the common generic {@code "400"} error code)
     * → 400 Bad Request. Missing/blank codes default to 200.
     */
    public static HttpStatus httpStatus(BaseResponse body) {
        if (body == null || body.getStatusCode() == null || body.getStatusCode().isBlank()) {
            return HttpStatus.OK;
        }
        try {
            int value = Integer.parseInt(body.getStatusCode().trim());
            if (value >= 100 && value <= 599) {
                return HttpStatus.valueOf(value);
            }
        } catch (Exception ignored) {
            // Not a parseable status code — fall through to the error default.
        }
        return HttpStatus.BAD_REQUEST;
    }
}
