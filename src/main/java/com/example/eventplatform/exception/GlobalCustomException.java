package com.example.eventplatform.exception;

import lombok.Getter;

@Getter
public class GlobalCustomException extends RuntimeException {

    private final GlobalExceptions globalExceptions;
    public GlobalCustomException(GlobalExceptions globalExceptions) {
        this.globalExceptions = globalExceptions;
    }
}
