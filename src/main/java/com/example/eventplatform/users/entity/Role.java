package com.example.eventplatform.users.entity;

public enum Role {
    USER("USER"),
    ADMIN("ADMIN");

    private String role;

    Role(String role) {
        this.role = role;
    }
}
