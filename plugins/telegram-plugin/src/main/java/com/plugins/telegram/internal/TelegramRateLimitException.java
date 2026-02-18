package com.plugins.telegram.internal;

import java.io.IOException;

public class TelegramRateLimitException extends IOException {
    private final long retryAfterSeconds;

    public TelegramRateLimitException(long retryAfterSeconds, String message) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
