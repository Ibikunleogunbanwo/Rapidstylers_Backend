package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.pojo.BaseResponse;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.DecimalFormat;

import static com.macrotel.rapidstylers.config.AppConstants.EMPTY_DATA;

public class AppUtils {
    public String capitalizeString(String text){
        if(text == null || text.isEmpty()){
            return text;
        }
        return text.substring(0,1).toUpperCase() + text.substring(1);
    }
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public String randomAlphanumeric(int length){
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < length; i++) {
            int index = SECURE_RANDOM.nextInt(characters.length());
            char randomChar = characters.charAt(index);
            sb.append(randomChar);
        }

        return sb.toString();
    }

    public String randomDigit(int length){
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int randomNumber = SECURE_RANDOM.nextInt(10);
            sb.append(randomNumber);
        }
        return sb.toString();
    }

    /**
     * Sanitizes free-text user input (reviews, feedback) on write to neutralize
     * HTML/script payloads while preserving ordinary text. Strips script/style
     * blocks, javascript: URIs and any remaining HTML tags. Benign text such as
     * "<3" or "under $50" (no closing angle bracket) is left intact.
     */
    public static String sanitizeText(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String cleaned = input
                .replaceAll("(?is)<script[^>]*>.*?</script\\s*>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style\\s*>", " ")
                .replaceAll("(?i)javascript\\s*:", " ")
                .replaceAll("<[^>]*>", " ")
                .replaceAll(" {2,}", " ");
        return cleaned.trim();
    }

    /**
     * BCrypt-encode a raw password for storage (new accounts and resets).
     * BCrypt is salted, so two encodes of the same input differ — never
     * compare by equality, always use {@link #passwordMatches}.
     */
    public String encryptPassword(String userPassword) {
        return new BCryptPasswordEncoder().encode(userPassword);
    }

    /**
     * Verify a raw password against a stored hash. Supports both BCrypt
     * (new accounts) and the legacy unsalted MD5 hashes already in the DB,
     * so existing accounts keep working during the migration.
     */
    public boolean passwordMatches(String rawPassword, String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) {
            return false;
        }
        if (storedHash.matches("^[0-9a-fA-F]{32}$")) {
            return md5(rawPassword).equalsIgnoreCase(storedHash);
        }
        return new BCryptPasswordEncoder().matches(rawPassword, storedHash);
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public String currencyFormat(String text){
        String removeComma = text.replaceAll(",", "");
        double value = Double.parseDouble(removeComma);

        DecimalFormat decimalFormat = new DecimalFormat("#,##0.00");
        return decimalFormat.format(value);
    }
    public String removeComma(String value){
        return value.replaceAll(",","");
    }

    public String extractUsername(String emailAddress) {
        int atIndex = emailAddress.indexOf('@');
        if (atIndex != -1) {
            return emailAddress.substring(0, atIndex);
        } else {
            return emailAddress;
        }
    }

    public Object callThirdPartyApi(String url, HttpMethod method, String apiKey, String contentType,  Object requestBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.set("Content-Type",contentType);
        BaseResponse baseResponse = new BaseResponse();
        RestTemplate restTemplate = new RestTemplate();
        HttpEntity<Object> requestEntity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Object> responseEntity = restTemplate.exchange(url, method, requestEntity, Object.class);
            return responseEntity.getBody();
        }
        catch (HttpClientErrorException ex) {
            String errorBody = ex.getResponseBodyAsString();
            baseResponse.setStatusCode(ex.getStatusCode().toString());
            baseResponse.setMessage(errorBody);
            baseResponse.setData(EMPTY_DATA);
            return baseResponse;
        }
        catch (Exception ex) {
            // Handle other exceptions
        }
        return  baseResponse;
    }

}
