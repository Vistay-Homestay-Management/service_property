package com.vistay.property.application;

public class DynamicConfigException extends RuntimeException {
    private final String code;

    public DynamicConfigException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
