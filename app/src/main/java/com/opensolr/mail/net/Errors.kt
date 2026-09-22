package com.opensolr.mail.net

import java.io.IOException

/** The Opensolr session is gone: sign in again. */
class SignInRequiredException : IOException("Opensolr sign-in required")

/** A Fastmail login was revoked or expired for good: that account must sign in again. */
class AccountSignInException(val accountKey: String) : IOException("Mail account sign-in required")

open class ServiceException(message: String) : IOException(message)

class RateLimitedException(val retryAfterSeconds: Long) : IOException("Rate limited")

class QuotaExceededException(val resetsAt: String?) : IOException("Monthly AI quota reached")

class VectorNotAllowedException : IOException("Vectors are not part of this plan")

class IndexMissingException : IOException("The mail index does not exist")

class IndexLimitException : IOException("No room for another index on this plan")

class EndpointMissingException : java.io.IOException("Endpoint not available")
