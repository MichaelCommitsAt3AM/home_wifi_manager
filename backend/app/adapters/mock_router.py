"""Explicit test-only adapter. Never selected by production configuration."""
from .base import RouterAdapter, Observation, Restriction, UnavailableError


class MockRouter(RouterAdapter):
    def __init__(self):
        self.devices = [Observation('02:00:00:00:00:02', '192.168.100.50', 'Test device', True, 'wired', 'LAN1')]
        self.restrictions = []
        self.unavailable = False
        self.commands = []

    async def authenticate(self):
        pass

    async def snapshot(self):
        if self.unavailable:
            raise UnavailableError('Test outage')
        return self.devices, self.restrictions, {'block_supported': True, 'scheduling_supported': True}

    async def set_blocked(self, device, blocked):
        self.commands.append((device.mac, blocked))
        self.restrictions = [r for r in self.restrictions if r.mac != device.mac]
        if blocked:
            self.restrictions.append(Restriction(device.mac, 'test', 'wired'))

    async def close(self):
        pass

    async def logout(self):
        return True
