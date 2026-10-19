package com.macrotel.rapidstylers.common.service;

/** Cache loaders are saturated or the caller's bounded wait expired. */
public class CacheLoadUnavailableException extends RuntimeException {
    public CacheLoadUnavailableException() {
        super("The service is busy. Please try again shortly.");
    }
}
