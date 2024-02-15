package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.pojo.BaseResponse;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.Random;

import static com.macrotel.rapidstylers.config.AppConstants.EMPTY_DATA;

public class AppUtils {
    public String capitalizeString(String text){
        if(text == null || text.isEmpty()){
            return text;
        }
        return text.substring(0,1).toUpperCase() + text.substring(1);
    }
    public String randomAlphanumeric(int length){
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();

        Random random = new Random();
        for (int i = 0; i < length; i++) {
            int index = random.nextInt(characters.length());
            char randomChar = characters.charAt(index);
            sb.append(randomChar);
        }

        return sb.toString();
    }

    public String randomDigit(int length){
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int randomNumber = random.nextInt(10);
            sb.append(randomNumber);
        }
        return sb.toString();
    }

    public String encryptPassword(String userPassword) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(userPassword.getBytes());
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return userPassword;
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
