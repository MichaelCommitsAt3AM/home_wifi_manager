"""Explicit test-only adapter. Never selected by production configuration."""
from .base import RouterAdapter, Observation, Restriction, UnavailableError


class MockRouter(RouterAdapter):
    def __init__(self):
        self.devices = [Observation('02:00:00:00:00:02', '192.168.100.50', 'Test device', True, 'wired', 'LAN1')]
        self.restrictions = []
        self.unavailable = False
        self.commands = []
        self.radio = {'enabled': True, 'standard': '11n', 'channel': 11, 'auto_channel': False,
                      'bandwidth': '20', 'transmit_power': '100', 'country': 'KE',
                      'standards': [{'value': '11n', 'label': '802.11n only'},
                                    {'value': '11bgn', 'label': '802.11b/g/n mixed'}],
                      'channels': list(range(14))}

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

    async def get_radio(self):
        return dict(self.radio)

    async def set_radio(self, standard, channel):
        self.commands.append(('radio', standard, channel))
        if standard:
            self.radio['standard'] = standard
        if channel is not None:
            self.radio.update(channel=channel or self.radio['channel'], auto_channel=channel == 0)
        return dict(self.radio)

    async def close(self):
        pass

    async def logout(self):
        return True
