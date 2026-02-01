from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class QrPayload(_message.Message):
    __slots__ = ("version", "ip", "port", "master_secret", "session_id", "ntfy_topic", "tailscale_ip", "tailscale_port", "vpn_ip", "vpn_port")
    VERSION_FIELD_NUMBER: _ClassVar[int]
    IP_FIELD_NUMBER: _ClassVar[int]
    PORT_FIELD_NUMBER: _ClassVar[int]
    MASTER_SECRET_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    NTFY_TOPIC_FIELD_NUMBER: _ClassVar[int]
    TAILSCALE_IP_FIELD_NUMBER: _ClassVar[int]
    TAILSCALE_PORT_FIELD_NUMBER: _ClassVar[int]
    VPN_IP_FIELD_NUMBER: _ClassVar[int]
    VPN_PORT_FIELD_NUMBER: _ClassVar[int]
    version: int
    ip: str
    port: int
    master_secret: bytes
    session_id: str
    ntfy_topic: str
    tailscale_ip: str
    tailscale_port: int
    vpn_ip: str
    vpn_port: int
    def __init__(self, version: _Optional[int] = ..., ip: _Optional[str] = ..., port: _Optional[int] = ..., master_secret: _Optional[bytes] = ..., session_id: _Optional[str] = ..., ntfy_topic: _Optional[str] = ..., tailscale_ip: _Optional[str] = ..., tailscale_port: _Optional[int] = ..., vpn_ip: _Optional[str] = ..., vpn_port: _Optional[int] = ...) -> None: ...
