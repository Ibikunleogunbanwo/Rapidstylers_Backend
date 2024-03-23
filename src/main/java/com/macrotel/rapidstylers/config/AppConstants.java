package com.macrotel.rapidstylers.config;

public class AppConstants {
    public static final String ERROR_STATUS_CODE = "400";
    public static final String SUCCESS_STATUS_CODE = "200";
    public static final String ERROR_MESSAGE = "Unsuccessful";

    public static final  String SUCCESS_MESSAGE = "Successful";
    public static  final Object EMPTY_DATA = new Object[0];
    public static final String CHARACTER_VALIDATION_REGEX = "^[a-zA-Z ]{3,}(?: [a-zA-Z ]+){0,2}$";
    public static final String NUMBER_VALIDATION_REGEX = "^[0-9]+$";
    public static final String PHONE_NUMBER_VALIDATION_REGEX ="^[\\d+]{1,20}$";
    public static final String PASSWORD_PATTERN = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[!@#&()-[{}]:;'?/*~$^+=<>.]).{6,20}$";
}
