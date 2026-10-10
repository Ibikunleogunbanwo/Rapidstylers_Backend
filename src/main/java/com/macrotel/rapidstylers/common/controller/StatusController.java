package com.macrotel.rapidstylers.common.controller;

import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Health check: answers "API is working well" when the API is up. Same URL as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class StatusController {

    @GetMapping("/testing")
    public ResponseEntity <BaseResponse> testing(){
        BaseResponse baseResponse = new BaseResponse(true);
        baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
        baseResponse.setMessage("API is working well");
        baseResponse.setData(EMPTY_DATA);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }
}
