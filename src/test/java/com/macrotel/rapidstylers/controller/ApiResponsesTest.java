package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.pojo.BaseResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every non-2xx business outcome must surface as a real HTTP error status —
 * never HTTP 200 with an error statusCode hidden in the body.
 */
class ApiResponsesTest {

    private BaseResponse body(String code) {
        BaseResponse response = new BaseResponse();
        response.setStatusCode(code);
        response.setMessage("test");
        return response;
    }

    @Test
    void successBodyMapsToHttp200() {
        assertEquals(HttpStatus.OK, ApiResponses.httpStatus(body("200")));
    }

    @Test
    void genericBusinessErrorMapsToHttp400() {
        assertEquals(HttpStatus.BAD_REQUEST, ApiResponses.httpStatus(body("400")));
    }

    @Test
    void authFailureCodesMapToRealStatuses() {
        assertEquals(HttpStatus.UNAUTHORIZED, ApiResponses.httpStatus(body("401")));
        assertEquals(HttpStatus.FORBIDDEN, ApiResponses.httpStatus(body("403")));
        assertEquals(HttpStatus.NOT_FOUND, ApiResponses.httpStatus(body("404")));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ApiResponses.httpStatus(body("500")));
    }

    @Test
    void blankOrNullCodeDefaultsToOk() {
        assertEquals(HttpStatus.OK, ApiResponses.httpStatus(new BaseResponse()));
        assertEquals(HttpStatus.OK, ApiResponses.httpStatus(null));
        assertEquals(HttpStatus.OK, ApiResponses.httpStatus(body("  ")));
    }

    @Test
    void unparseableCodeFallsBackToBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST, ApiResponses.httpStatus(body("not-a-code")));
        assertEquals(HttpStatus.BAD_REQUEST, ApiResponses.httpStatus(body("99")));
    }

    @Test
    void respondWrapsBodyWithTheResolvedStatus() {
        assertEquals(HttpStatus.BAD_REQUEST, ApiResponses.respond(body("400")).getStatusCode());
        assertEquals(HttpStatus.OK, ApiResponses.respond(body("200")).getStatusCode());
        assertEquals("test", ApiResponses.respond(body("200")).getBody().getMessage());
    }
}
