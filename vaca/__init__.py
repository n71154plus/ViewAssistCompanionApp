"""The Wyoming integration."""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from homeassistant.components.wyoming import (
    DomainDataItem,
    WyomingService,
    async_register_websocket_api,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryNotReady, HomeAssistantError
from homeassistant.helpers import config_validation as cv, device_registry as dr
from homeassistant.helpers.typing import ConfigType

from .client import AsyncTcpClient
from .const import ATTR_SPEAKER, DOMAIN
from .bluetooth import VacaBLEScanner, VacaBleGattProxy, async_connect_ble_scanner
from .custom import CustomActions, CustomEvent
from .devices import VASatelliteDevice

_LOGGER = logging.getLogger(__name__)

CONFIG_SCHEMA = cv.empty_config_schema(DOMAIN)

SATELLITE_PLATFORMS = [
    Platform.ASSIST_SATELLITE,
    Platform.BINARY_SENSOR,
    Platform.BUTTON,
    Platform.SELECT,
    Platform.SWITCH,
    Platform.MEDIA_PLAYER,
    Platform.NUMBER,
    Platform.SENSOR,
]

__all__ = [
    "ATTR_SPEAKER",
    "DOMAIN",
    "async_setup",
    "async_setup_entry",
    "async_unload_entry",
]


class WyomingError(HomeAssistantError):
    """Base class for Wyoming errors."""


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Set up the Wyoming integration."""
    async_register_websocket_api(hass)

    async def handle_launch_app(call: Any) -> None:
        """Handle launch_app service call."""
        package_name = call.data.get("package_name")
        entry_id = call.data.get("entry_id")
        if not package_name:
            return
        for eid, _item in hass.data[DOMAIN].items():
            if entry_id and eid != entry_id:
                continue
            if _item.device:
                _item.device.send_custom_action(
                    CustomActions.LAUNCH_APP,
                    {"package_name": package_name},
                )

    hass.services.async_register(DOMAIN, "launch_app", handle_launch_app)

    # ── BLE GATT proxy services ────────────────────────────────────────────────

    def _get_gatt_proxy(entry_id: str | None) -> VacaBleGattProxy | None:
        """Return the GATT proxy for a specific entry, or the first available one."""
        proxies: dict = hass.data.get(f"{DOMAIN}_gatt", {})
        if entry_id:
            return proxies.get(entry_id)
        return next(iter(proxies.values()), None)

    async def handle_ble_connect(call: Any) -> None:
        proxy = _get_gatt_proxy(call.data.get("entry_id"))
        if proxy:
            await proxy.async_connect(call.data["address"])

    async def handle_ble_disconnect(call: Any) -> None:
        proxy = _get_gatt_proxy(call.data.get("entry_id"))
        if proxy:
            await proxy.async_disconnect(call.data["address"])

    async def handle_ble_read(call: Any) -> None:
        proxy = _get_gatt_proxy(call.data.get("entry_id"))
        if proxy:
            await proxy.async_read_characteristic(
                call.data["address"],
                call.data["service"],
                call.data["characteristic"],
            )

    async def handle_ble_write(call: Any) -> None:
        proxy = _get_gatt_proxy(call.data.get("entry_id"))
        if proxy:
            import base64
            value = base64.b64decode(call.data["value"])
            await proxy.async_write_characteristic(
                call.data["address"],
                call.data["service"],
                call.data["characteristic"],
                value,
                response=call.data.get("response", True),
            )

    def handle_ble_subscribe(call: Any) -> None:
        proxy = _get_gatt_proxy(call.data.get("entry_id"))
        if proxy:
            proxy.async_subscribe_notification(
                call.data["address"],
                call.data["service"],
                call.data["characteristic"],
                enable=call.data.get("enable", True),
            )

    hass.services.async_register(DOMAIN, "ble_connect", handle_ble_connect)
    hass.services.async_register(DOMAIN, "ble_disconnect", handle_ble_disconnect)
    hass.services.async_register(DOMAIN, "ble_read", handle_ble_read)
    hass.services.async_register(DOMAIN, "ble_write", handle_ble_write)
    hass.services.async_register(DOMAIN, "ble_subscribe", handle_ble_subscribe)

    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Load Wyoming."""
    service = await WyomingService.create(entry.data["host"], entry.data["port"])

    if service is None:
        raise ConfigEntryNotReady("Unable to connect")

    item = DomainDataItem(service=service)

    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = item

    await hass.config_entries.async_forward_entry_setups(entry, service.platforms)
    entry.async_on_unload(entry.add_update_listener(update_listener))

    if (satellite_info := service.info.satellite) is not None:
        # Create satellite device first so scanner can be linked to it
        dev_reg = dr.async_get(hass)

        # Use config entry id since only one satellite per entry is supported
        satellite_id = entry.entry_id
        host = entry.data.get("host", "")
        http_port = 8080
        configuration_url = f"http://{host}:{http_port}/" if host else None

        device = dev_reg.async_get_or_create(
            config_entry_id=entry.entry_id,
            identifiers={(DOMAIN, satellite_id)},
            name=satellite_info.name,
            suggested_area=satellite_info.area,
            configuration_url=configuration_url,
        )

        # Set up BLE GATT proxy and scanner, linked to the device above
        gatt_proxy = VacaBleGattProxy(hass, entry.entry_id)
        hass.data.setdefault(f"{DOMAIN}_gatt", {})[entry.entry_id] = gatt_proxy

        def _unload_gatt_proxy() -> None:
            hass.data.get(f"{DOMAIN}_gatt", {}).pop(entry.entry_id, None)

        entry.async_on_unload(_unload_gatt_proxy)

        ble_scanner, ble_unload = await async_connect_ble_scanner(
            hass,
            entry,
            gatt_proxy,
            source_model=satellite_info.name,
            source_device_id=device.id,
        )
        hass.data.setdefault(f"{DOMAIN}_ble", {})[entry.entry_id] = ble_scanner
        entry.async_on_unload(ble_unload)

        item.device = VASatelliteDevice(
            satellite_id=satellite_id,
            device_id=device.id,
        )
        item.device.capabilities = await get_device_capabilities(item)

        # Set up satellite entity, sensors, switches, etc.
        await hass.config_entries.async_forward_entry_setups(entry, SATELLITE_PLATFORMS)

    return True


async def update_listener(hass: HomeAssistant, entry: ConfigEntry):
    """Handle options update."""
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload Wyoming."""
    item: DomainDataItem = hass.data[DOMAIN][entry.entry_id]

    platforms = list(item.service.platforms)
    if item.device is not None:
        platforms += SATELLITE_PLATFORMS

    unload_ok = await hass.config_entries.async_unload_platforms(entry, platforms)
    if unload_ok:
        del hass.data[DOMAIN][entry.entry_id]

    return unload_ok


async def get_device_capabilities(item: DomainDataItem):
    """Get device capabilities."""
    capabilities: dict[str, Any] | None = None

    for _ in range(4):
        try:
            async with asyncio.timeout(1):
                async with AsyncTcpClient(item.service.host, item.service.port) as client:
                    # Describe -> Info
                    await client.write_event(CustomEvent("capabilities").event())
                    while True:
                        event = await client.read_event()
                        if event is None:
                            raise WyomingError(  # noqa: TRY301
                                "Connection closed unexpectedly",
                            )

                        if CustomEvent.is_type(event.type) and (
                            event_data := CustomEvent.from_event(event).event_data
                        ):
                            capabilities = event_data.get("capabilities")
                            break  # while

            if capabilities is not None:
                break  # for
        except (TimeoutError, OSError, WyomingError) as ex:
            _LOGGER.warning(
                "Error getting device capabilities: %s, %s", ex, capabilities
            )
            # Sleep and try again
            await asyncio.sleep(2)

    return capabilities
