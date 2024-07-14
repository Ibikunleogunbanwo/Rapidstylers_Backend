package com.macrotel.rapidstylers.config;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import static com.macrotel.rapidstylers.config.AppConstants.*;

public class EncryptionConfig {
    private static final SecretKeySpec SECRET_KEY;

    static {
        try {
            SECRET_KEY = generateSecretKey();
        } catch (Exception e) {
            throw new RuntimeException("Error initializing secret key", e);
        }
    }


    // Generate a SecretKeySpec from the provided key string
    private static SecretKeySpec generateSecretKey() throws Exception {
        byte[] key = ENCRYPTDECRYPTKEY.getBytes(StandardCharsets.UTF_8);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        key = sha.digest(key);
        key = Arrays.copyOf(key, 16);
        return new SecretKeySpec(key, ALGORITHM);
    }

    // Encrypt a string
    public static String encrypt(String input) throws Exception {
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        byte[] outputBytes = cipher.doFinal(inputBytes);
        return Base64.getEncoder().encodeToString(outputBytes);
    }

    // Decrypt a string
    public static String decrypt(String input) throws Exception {
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, SECRET_KEY);
        byte[] inputBytes = Base64.getDecoder().decode(input);
        byte[] outputBytes = cipher.doFinal(inputBytes);
        return new String(outputBytes, StandardCharsets.UTF_8);
    }
}
