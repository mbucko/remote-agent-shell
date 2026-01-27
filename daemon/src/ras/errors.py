"""Base exceptions for RAS daemon."""


class RasError(Exception):
    """Base exception for all RAS errors."""

    pass


class CryptoError(RasError):
    """Cryptographic operation failed."""

    pass


class AuthError(RasError):
    """Authentication error."""

    pass


class MessageError(RasError):
    """Message processing error."""

    pass


class NtfyError(RasError):
    """ntfy operation error."""

    pass


class StorageError(RasError):
    """Storage operation error."""

    pass
