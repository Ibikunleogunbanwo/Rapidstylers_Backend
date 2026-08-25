package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import java.util.Objects;

import static com.macrotel.rapidstylers.config.AppConstants.*;

@Data
public class BaseResponse {
    private String statusCode;
    private String message;
    private Object data;
    private String token;

    public BaseResponse() {
    }

    public BaseResponse(boolean error){
        this.statusCode = ERROR_STATUS_CODE;
        this.message = ERROR_MESSAGE;
        this.data = EMPTY_DATA;
    }
}
