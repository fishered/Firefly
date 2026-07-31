package com.firefly.api.admin.http;

final class AdminHttpException extends RuntimeException {
    private final int status;
    private final String error;

    AdminHttpException(int status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    int status() {
        return status;
    }

    String error() {
        return error;
    }
}
