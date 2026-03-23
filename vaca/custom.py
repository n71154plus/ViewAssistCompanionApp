"""# Custom components for View Assist satellite integration with Wyoming events."""

from dataclasses import dataclass
from enum import StrEnum
import logging
from typing import Any

from awesomeversion import AwesomeVersion
from wyoming.event import Event, Eventable

from homeassistant.core import HomeAssistant
from homeassistant.helpers import entity_registry as er
from homeassistant.loader import async_get_integration

from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

_CUSTOM_EVENT_TYPE = "custom-event"
_PIPELINE_ENDED_EVENT_TYPE = "pipeline-ended"

ACTION_EVENT_TYPE = "action"
BLE_ADVERTISEMENT_EVENT_TYPE = "ble_advertisement"
CAPABILITIES_EVENT_TYPE = "capabilities"
SETTINGS_EVENT_TYPE = "settings"
STATUS_EVENT_TYPE = "status"

# BLE GATT — incoming (Android → HA)
BLE_CONNECT_RESULT_EVENT_TYPE = "ble_connect_result"
BLE_DISCONNECTED_EVENT_TYPE = "ble_disconnected"
BLE_READ_RESULT_EVENT_TYPE = "ble_read_result"
BLE_WRITE_RESULT_EVENT_TYPE = "ble_write_result"
BLE_NOTIFY_EVENT_TYPE = "ble_notify"
BLE_ERROR_EVENT_TYPE = "ble_error"

# BLE GATT — outgoing (HA → Android)
BLE_CONNECT_EVENT_TYPE = "ble_connect"
BLE_DISCONNECT_EVENT_TYPE = "ble_disconnect"
BLE_READ_EVENT_TYPE = "ble_read"
BLE_WRITE_EVENT_TYPE = "ble_write"
BLE_SUBSCRIBE_EVENT_TYPE = "ble_subscribe"


class CustomActions(StrEnum):
    """Actions for media control."""

    MEDIA_PLAY_MEDIA = "play-media"
    MEDIA_PLAY = "play"
    MEDIA_PAUSE = "pause"
    MEDIA_STOP = "stop"
    MEDIA_SET_VOLUME = "set-volume"
    REFRESH = "refresh"
    SCREEN_SLEEP = "screen-sleep"
    SCREEN_WAKE = "screen-wake"
    TOAST_MESSAGE = "toast-message"
    WAKE = "wake"
    LAUNCH_APP = "launch-app"


@dataclass
class PipelineEnded(Eventable):
    """Event triggered when a pipeline ends."""

    @staticmethod
    def is_type(event_type: str) -> bool:
        """Check if the event type matches."""
        return event_type == _PIPELINE_ENDED_EVENT_TYPE

    def event(self) -> Event:
        """Create an event for the pipeline ended."""
        return Event(type=_PIPELINE_ENDED_EVENT_TYPE)

    @staticmethod
    def from_event(event: Event) -> "PipelineEnded":
        """Create a PipelineEnded instance from an event."""
        return PipelineEnded()


@dataclass
class CustomEvent(Eventable):
    """Custom event class."""

    event_type: str
    """Type of the event."""

    event_data: dict[str, Any] | None = None
    """Data associated with the event."""

    @staticmethod
    def is_type(event_type: str) -> bool:
        """Check if the event type matches."""
        return event_type == _CUSTOM_EVENT_TYPE

    def event(self) -> Event:
        """Create an event for the custom event."""
        data = {"event_type": self.event_type}
        if self.event_data is not None:
            data.update(self.event_data)
        return Event(
            type=_CUSTOM_EVENT_TYPE,
            data=data,
        )

    @staticmethod
    def from_event(event: Event) -> "CustomEvent":
        """Create a CustomEvent instance from an event."""
        return CustomEvent(
            event_type=event.data.get("event_type", "unknown"),
            event_data=event.data.get("data"),
        )


async def getIntegrationVersion(hass: HomeAssistant) -> str | AwesomeVersion | None:
    """Get the integration version."""
    integration = await async_get_integration(hass, DOMAIN)
    return integration.version if integration else "0.0.0"


def getVADashboardPath(hass: HomeAssistant, uuid: str) -> str:
    """Get the dashboard path."""
    # Look for VA and a config entry that uses this uuid for display.  Then get the dashboard path
    # from it or the master entry.  If not set, return empty string
    if entries := hass.config_entries.async_entries(
        "view_assist", include_disabled=False
    ):
        entity_reg = er.async_get(hass)
        for entry in entries:
            try:
                if entry.data["type"] == "vaca":
                    if mic_device := entry.data.get("mic_device", {}):
                        # Get device id for this entity
                        if mic_device_entity := entity_reg.async_get(mic_device):
                            entry_id = mic_device_entity.config_entry_id
                            if entry_id == uuid:
                                if home := entry.options.get("home"):
                                    return home
                                # Look for master entry
                                for master_entry in entries:
                                    if master_entry.data["type"] == "master_config":
                                        if home := master_entry.options.get("home"):
                                            return home
                                return "view-assist"
            except Exception as e:  # noqa: BLE001
                _LOGGER.error("Error getting dashboard path: %s", e)
                continue
    return ""
