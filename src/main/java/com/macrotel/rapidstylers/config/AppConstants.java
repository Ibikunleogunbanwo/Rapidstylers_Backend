package com.macrotel.rapidstylers.config;

import java.util.Arrays;
import java.util.List;

public class AppConstants {
    public static final String ERROR_STATUS_CODE = "400";
    public static final String SUCCESS_STATUS_CODE = "200";
    public static final String ERROR_MESSAGE = "Unsuccessful";
    public static final String AMOUNT_VALIDATION_REGEX = "^\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?$|^\\d+(?:\\.\\d+)?$";
    public static final  String SUCCESS_MESSAGE = "Successful";
    public static  final Object EMPTY_DATA = new Object[0];
    public static final String CHARACTER_VALIDATION_REGEX = "^[a-zA-Z ]{3,}(?: [a-zA-Z ]+){0,2}$";
    public static final String NUMBER_VALIDATION_REGEX = "^[0-9]+$";
    // Canadian phone: exactly 10 digits, or 11 with leading 1
    public static final String PHONE_NUMBER_VALIDATION_REGEX ="^1?[0-9]{10}$";
    // Password: 8+ chars, at least 1 uppercase, 1 lowercase, 1 digit, 1 special char
    public static final String PASSWORD_PATTERN = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[!@#&()-[{}]:;'?/*~$^+=<>.]).{8,30}$";
    public static final String ALGORITHM = "AES";
    // Legacy encryption fallback key. It is PUBLIC source code, so EncryptionConfig
    // refuses to boot on it unless the active Spring profile is "test" — real
    // environments must set ENCRYPT_KEY (see .env.example: `openssl rand -hex 32`).
    public static final String ENCRYPT_DECRYPT_KEY_FALLBACK = "D0n!T'T&mp3r@w1Th^&()";

    // Professional verification workflow states (StylerEntity.verificationStatus)
    public static final String VERIFICATION_PENDING = "PENDING";
    public static final String VERIFICATION_APPROVED = "APPROVED";
    public static final String VERIFICATION_REJECTED = "REJECTED";
    public static final String VERIFICATION_SUSPENDED = "SUSPENDED";

    // Gallery categories — used by the gallery tabs and the stylist work uploads.
    // Must stay in sync with the frontend CATEGORIES list.
    public static final List<String> GALLERY_CATEGORIES = Arrays.asList(
            "dreadlocks", "buzz cut", "braids", "cornrows", "wigs", "high-top fade",
            "hair dye", "nail tech", "makeup", "eyelash extensions", "natural hair", "locs");

    // Service duration is stored in 15-minute increments.
    public static final int DEFAULT_SERVICE_DURATION_MINUTES = 60;
    public static final int MIN_SERVICE_DURATION_MINUTES = 15;
    public static final int MAX_SERVICE_DURATION_MINUTES = 480;

    // Max portfolio images a stylist can upload (they replace Pexels over time).
    public static final int MAX_STYLER_PORTFOLIO_IMAGES = 30;

    // Runtime platform settings keys.
    public static final String COMMISSION_SETTING_KEY = "commission_percent";
}
