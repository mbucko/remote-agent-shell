# Re-export all proto types from ras.py
# This is needed because the nested ras/ directory (for clipboard subpackage)
# shadows the ras.py module
import importlib.util
import sys
from pathlib import Path

# Load ras.py directly since the ras/ directory shadows it
_ras_py = Path(__file__).parent / "ras.py"
_spec = importlib.util.spec_from_file_location("_ras_proto_types", _ras_py)
_module = importlib.util.module_from_spec(_spec)
sys.modules["_ras_proto_types"] = _module
_spec.loader.exec_module(_module)

# Import all types from the loaded module
AuthErrorErrorCode = _module.AuthErrorErrorCode
SessionStatus = _module.SessionStatus
SignalErrorErrorCode = _module.SignalErrorErrorCode
KeyType = _module.KeyType
NotificationType = _module.NotificationType
AuthEnvelope = _module.AuthEnvelope
AuthChallenge = _module.AuthChallenge
AuthResponse = _module.AuthResponse
AuthVerify = _module.AuthVerify
AuthSuccess = _module.AuthSuccess
AuthError = _module.AuthError
IpChangeNotification = _module.IpChangeNotification
QrPayload = _module.QrPayload
Session = _module.Session
SessionCommand = _module.SessionCommand
ListSessionsCommand = _module.ListSessionsCommand
CreateSessionCommand = _module.CreateSessionCommand
KillSessionCommand = _module.KillSessionCommand
RenameSessionCommand = _module.RenameSessionCommand
GetAgentsCommand = _module.GetAgentsCommand
GetDirectoriesCommand = _module.GetDirectoriesCommand
RefreshAgentsCommand = _module.RefreshAgentsCommand
SessionEvent = _module.SessionEvent
SessionListEvent = _module.SessionListEvent
SessionCreatedEvent = _module.SessionCreatedEvent
SessionKilledEvent = _module.SessionKilledEvent
SessionRenamedEvent = _module.SessionRenamedEvent
SessionActivityEvent = _module.SessionActivityEvent
SessionErrorEvent = _module.SessionErrorEvent
Agent = _module.Agent
AgentsListEvent = _module.AgentsListEvent
DirectoryEntry = _module.DirectoryEntry
DirectoriesListEvent = _module.DirectoriesListEvent
SignalRequest = _module.SignalRequest
SignalResponse = _module.SignalResponse
SignalError = _module.SignalError
TerminalOutput = _module.TerminalOutput
TerminalInput = _module.TerminalInput
SpecialKey = _module.SpecialKey
TerminalResize = _module.TerminalResize
TerminalCommand = _module.TerminalCommand
AttachTerminal = _module.AttachTerminal
DetachTerminal = _module.DetachTerminal
TerminalEvent = _module.TerminalEvent
TerminalNotification = _module.TerminalNotification
TerminalAttached = _module.TerminalAttached
TerminalDetached = _module.TerminalDetached
TerminalError = _module.TerminalError
OutputSkipped = _module.OutputSkipped

__all__ = [
    "AuthErrorErrorCode",
    "SessionStatus",
    "SignalErrorErrorCode",
    "KeyType",
    "NotificationType",
    "AuthEnvelope",
    "AuthChallenge",
    "AuthResponse",
    "AuthVerify",
    "AuthSuccess",
    "AuthError",
    "IpChangeNotification",
    "QrPayload",
    "Session",
    "SessionCommand",
    "ListSessionsCommand",
    "CreateSessionCommand",
    "KillSessionCommand",
    "RenameSessionCommand",
    "GetAgentsCommand",
    "GetDirectoriesCommand",
    "RefreshAgentsCommand",
    "SessionEvent",
    "SessionListEvent",
    "SessionCreatedEvent",
    "SessionKilledEvent",
    "SessionRenamedEvent",
    "SessionActivityEvent",
    "SessionErrorEvent",
    "Agent",
    "AgentsListEvent",
    "DirectoryEntry",
    "DirectoriesListEvent",
    "SignalRequest",
    "SignalResponse",
    "SignalError",
    "TerminalOutput",
    "TerminalInput",
    "SpecialKey",
    "TerminalResize",
    "TerminalCommand",
    "AttachTerminal",
    "DetachTerminal",
    "TerminalEvent",
    "TerminalNotification",
    "TerminalAttached",
    "TerminalDetached",
    "TerminalError",
    "OutputSkipped",
]
