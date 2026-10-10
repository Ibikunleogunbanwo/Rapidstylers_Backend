package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.pojo.BaseResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

import static com.macrotel.rapidstylers.config.AppConstants.ERROR_STATUS_CODE;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
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
    @ResponseStatus(HttpStatus.BAD_REQUEST)
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
        baseResponse.setStatusCode("403");
        baseResponse.setMessage("You do not have permission to perform this action.");
        baseResponse.setData(new Object[0]);
        return baseResponse;
    }

    // Client mistakes that Spring raises before a controller runs. Without these
    // handlers the catch-all below answered them with 500, telling the caller
    // the server broke and logging a server error for someone else's typo.

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public BaseResponse handleUnreadableBody(HttpMessageNotReadableException ex) {
        return clientError(ERROR_STATUS_CODE, "The request body could not be read. Please send valid JSON.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public BaseResponse handleMissingParameter(MissingServletRequestParameterException ex) {
        return clientError(ERROR_STATUS_CODE, "Missing required parameter: " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public BaseResponse handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return clientError(ERROR_STATUS_CODE, "Invalid value for parameter: " + ex.getName());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public BaseResponse handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return clientError("405", "This endpoint does not support " + ex.getMethod() + ".");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public BaseResponse handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return clientError("415", "Please send the request as application/json.");
    }

    // NumberFormatException is an IllegalArgumentException whose message is Java's
    // own wording ("For input string: ..."), so it gets a plain message instead.
    @ExceptionHandler(NumberFormatException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public BaseResponse handleNumberFormat(NumberFormatException ex) {
        return clientError(ERROR_STATUS_CODE, "A number in the request is not valid.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public BaseResponse handleIllegalArgument(IllegalArgumentException ex) {
        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode(ERROR_STATUS_CODE);
        baseResponse.setMessage(ex.getMessage());
        return baseResponse;
    }

    private static BaseResponse clientError(String statusCode, String message) {
        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode(statusCode);
        baseResponse.setMessage(message);
        baseResponse.setData(new Object[0]);
        return baseResponse;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public BaseResponse handleGenericException(Exception ex) {
        logger.error("Unexpected error: {}", ex.getMessage(), ex);
        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode("500");
        baseResponse.setMessage("Something went wrong. Please try again later.");
        baseResponse.setData(new Object[0]);
        return baseResponse;
    }
}
