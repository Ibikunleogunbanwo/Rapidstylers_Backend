package com.macrotel.rapidstylers.common.web;

import com.macrotel.rapidstylers.pojo.BaseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.servlet.http.HttpServletRequest;

import static com.macrotel.rapidstylers.config.AppConstants.EMPTY_DATA;

/**
 * Who is calling, as {@code JwtAuthFilter} recorded it on the request. Shared by
 * every domain controller.
 */
public final class CurrentAccount {

    private CurrentAccount() {
    }

    /** The signed-in account id, or null when the request has no valid token. */
    public static String id(HttpServletRequest request) {
        Object accountId = request.getAttribute("accountId");
        return (accountId == null) ? null : String.valueOf(accountId);
    }

    /** The role from the token, or null. */
    public static String role(HttpServletRequest request) {
        Object role = request.getAttribute("role");
        return (role == null) ? null : String.valueOf(role);
    }

    /** The 401 answer for a request with no signed-in account. */
    public static ResponseEntity<BaseResponse> unauthorized() {
        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode("401");
        baseResponse.setMessage("Authentication required");
        baseResponse.setData(EMPTY_DATA);
        return new ResponseEntity<>(baseResponse, HttpStatus.UNAUTHORIZED);
    }
}
