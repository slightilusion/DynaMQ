package org.dynabot.auth;

import lombok.Builder;
import lombok.Data;

/**
 * Result of an authentication attempt.
 */
@Data
@Builder
public class AuthResult {

    /**
     * Whether authentication was successful
     */
    private boolean success;

    /**
     * Error message if authentication failed
     */
    private String errorMessage;

    /**
     * Authenticated principal (username or clientId)
     */
    private String principal;

    /**
     * Optional roles or permissions
     */
    private String[] roles;

    /**
     * Create a successful auth result
     */
    public static AuthResult success(String principal) {
        return AuthResult.builder()
                .success(true)
                .principal(principal)
                .build();
    }

    /**
     * Create a failed auth result
     */
    public static AuthResult failed(String errorMessage) {
        return AuthResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
