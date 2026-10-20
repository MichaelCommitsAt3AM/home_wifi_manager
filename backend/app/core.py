import logging
import json
from pathlib import Path
from pydantic import SecretStr, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file='.env', extra='ignore')
    database_url: str = 'sqlite:///./data/router.db'
    api_token: SecretStr = Field(min_length=32)
    router_url: str = 'http://192.168.100.1'
    router_username: str = 'root'
    router_password: SecretStr
    writes_enabled: bool = False
    # Only enable after the approved real-device block/unblock validation.
    verified_writes: bool = False
    protected_macs: str = ''
    protected_ips: str = '192.168.100.14,192.168.100.158,192.168.100.85'
    poll_seconds: int = Field(default=60, ge=30, le=3600)
    offline_polls: int = Field(default=2, ge=2, le=10)
    retention_days: int = Field(default=90, ge=7, le=3650)
    ntfy_url: str = ''
    ntfy_token: SecretStr = SecretStr('')
    adguard_url: str = ''
    adguard_username: str = ''
    adguard_password: SecretStr = SecretStr('')
    adguard_dns_host: str = 'host.docker.internal'


class JsonFormatter(logging.Formatter):
    def format(self, record):
        # Deliberately omit arbitrary exception/HTTP request content and secrets.
        return json.dumps({'level': record.levelname, 'event': record.getMessage(),
                           'time': self.formatTime(record)})


def configure_logging():
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    logging.basicConfig(level=logging.INFO, handlers=[handler], force=True)
    for name in ('httpx', 'httpcore', 'apscheduler'):
        logging.getLogger(name).setLevel(logging.WARNING)
