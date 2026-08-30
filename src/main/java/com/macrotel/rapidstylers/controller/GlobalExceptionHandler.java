package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.pojo.BaseResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

import static com.macrotel.rapidstylers.config.AppConstants.ERROR_STATUS_CODE;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public BaseResponse handleValidationExceptions(MethodArgumentNotValidException ex) {
        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode(ERROR_STATUS_CODE);

        Map<String, String> errors = new HashMap<>();
        StringBuilder message = new StringBuilder();

        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
            if (message.length() > 0) message.append("; ");
            message.append(errorMessage);
        });

        baseResponse.setMessage(message.length() > 0 ? message.toString() : "Validation failed. Please check your input.");
        baseResponse.setData(errors);
        return baseResponse;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.OK)
    public BaseResponse handleDataIntegrity(DataIntegrityViolationException ex) {
        logger.error("Data integrity violation: {}", ex.getMessage());
        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode(ERROR_STATUS_CODE);
        baseResponse.setMessage("This operation conflicts with existing data. Please try different values.");
        return baseResponse;
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public BaseResponse handleAccessDenied(AccessDeniedException ex) {
        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode(ERROR_STATUS_CODE);
        baseResponse.setMessage("You do not have permission to perform this action.");
        return baseResponse;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public BaseResponse handleIllegalArgument(IllegalArgumentException ex) {
        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode(ERROR_STATUS_CODE);
        baseResponse.setMessage(ex.getMessage());
        return baseResponse;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public BaseResponse handleGenericException(Exception ex) {
        logger.error("Unexpected error: {}", ex.getMessage(), ex);
        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode(ERROR_STATUS_CODE);
        baseResponse.setMessage("Something went wrong. Please try again later.");
        return baseResponse;
    }
}
