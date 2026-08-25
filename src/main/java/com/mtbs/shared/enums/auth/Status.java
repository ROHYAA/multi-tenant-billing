package com.mtbs.shared.enums.auth;

public enum Status {
    ACTIVE,
    INACTIVE,
    SUSPENDED,
    REGISTERED,
    /** Shop.status only — set on self-service signup, blocks writes until a SUPER_ADMIN approves. */
    PENDING_APPROVAL
}
