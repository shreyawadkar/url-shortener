package com.shortener.service;

public class AliasTakenException extends RuntimeException {
    public AliasTakenException(String alias) {
        super("Alias '" + alias + "' is already taken");
    }
}
