from dataclasses import dataclass
from abc import ABC, abstractmethod


class RouterError(Exception):
    code = 'router_error'


class AuthenticationError(RouterError):
    code = 'router_authentication_failed'


class UnavailableError(RouterError):
    code = 'router_unavailable'


class CapabilityError(RouterError):
    code = 'unsupported_capability'


class VerificationError(RouterError):
    code = 'state_verification_failed'


class ParseError(RouterError):
    code = 'firmware_response_changed'


@dataclass
class Observation:
    mac: str
    ip: str | None = None
    hostname: str = ''
    online: bool = False
    connection_type: str = 'unknown'
    port: str = ''
    device_type: str = ''
    source: str = 'router'


@dataclass
class Restriction:
    mac: str
    domain: str
    scope: str
    ssid: str = ''


class RouterAdapter(ABC):
    @abstractmethod
    async def authenticate(self): ...
    @abstractmethod
    async def snapshot(self) -> tuple[list[Observation], list[Restriction], dict]: ...
    @abstractmethod
    async def set_blocked(self, device: Observation, blocked: bool): ...
    @abstractmethod
    async def logout(self) -> bool: ...
    async def resume(self):
        await self.authenticate()
    @abstractmethod
    async def close(self): ...
