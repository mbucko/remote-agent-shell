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


class TmuxError(RasError):
    """tmux operation error."""

    pass


class TmuxNotFoundError(TmuxError):
    """tmux not installed."""

    pass


class TmuxVersionError(TmuxError):
    """tmux version too old."""

    pass


class TmuxSessionError(TmuxError):
    """tmux session error (not found, permission denied, etc.)."""

    pass
