package com.roadguard.app.data.update

/**
 * How long to stop calling the GitHub API after a 403.
 *
 * A *primary* rate limit arrives as 403 + `X-RateLimit-Reset` with **no**
 * `Retry-After`; only secondary/abuse limits carry `Retry-After`. Defaulting a
 * missing `Retry-After` to 60 s therefore made the app retry every minute while
 * the real window is an hour (60 requests/h unauthenticated) — and every retry
 * burned one of the remaining requests, so it could never recover.
 *
 * Pure so the header handling is unit-testable without a network or a Context.
 */
internal fun rateLimitBackoffMs(
    retryAfterSeconds: Long?,
    resetEpochSeconds: Long?,
    nowMs: Long,
    defaultMs: Long
): Long = when {
    retryAfterSeconds != null -> retryAfterSeconds.coerceAtLeast(0L) * 1000L
    resetEpochSeconds != null -> (resetEpochSeconds * 1000L - nowMs).coerceAtLeast(0L)
    else -> defaultMs
}
