package com.jengabank.payments.auth;

public enum Role {
    VIEWER,
    CUSTOMER,
    MAKER,
    CHECKER,
    ADMIN;

    /** Only Checkers and Admins can approve or reject transfers. */
    public boolean canApproveTransfer() {
        return this == CHECKER || this == ADMIN;
    }

    /** Customers, Makers, and Admins can initiate transfers. */
    public boolean canInitiateTransfer() {
        return this == CUSTOMER || this == MAKER || this == ADMIN;
    }

    /** Only Admins can administer accounts. */
    public boolean canAdminister() {
        return this == ADMIN;
    }

    /** Only Makers, Checkers, and Admins can view the audit trail. */
    public boolean canViewAudit() {
        return this == MAKER || this == CHECKER || this == ADMIN;
    }

    /** Safely parses a string from an HTTP header into a Role. */
    public static Role parse(String value) {
        if (value == null || value.isBlank()) {
            return VIEWER;
        }
        try {
            return Role.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return VIEWER;
        }
    }
}