package com.macrotel.rapidstylers.common.web;

import com.macrotel.rapidstylers.entity.*;
import com.macrotel.rapidstylers.pojo.*;
import com.macrotel.rapidstylers.repo.*;
import java.util.*;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Small helpers for building the error answers every domain service returns.
 */
public final class Responses {

    private Responses() {
    }

    public static BaseResponse errorResponse(BaseResponse response, String message){
        response.setStatusCode(ERROR_STATUS_CODE);
        response.setMessage(message);
        response.setData(EMPTY_DATA);
        return response;
    }

    /** Error response that also exposes a machine-readable paymentError code for the UI. */
    public static BaseResponse paymentErrorResponse(BaseResponse response, String paymentError, String message){
        response.setStatusCode(ERROR_STATUS_CODE);
        response.setMessage(message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentError", paymentError);
        response.setData(data);
        return response;
    }
}
