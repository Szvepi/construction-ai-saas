package com.buildassist.exception;

public class AccountInactiveException extends RuntimeException {

    public AccountInactiveException() {
        super("Account is not activated. Contact support after payment.");
    }
}
