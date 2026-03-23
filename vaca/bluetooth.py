"""Bluetooth proxy support for VACA - registers Android as a BLE remote scanner."""

from __future__ import annotations

import asyncio
import logging
from base64 import b64decode, b64encode
from collections.abc import Callable
from typing import Any

from habluetooth import BaseHaRemoteScanner, HaBluetoothConnector
from homeassistant.components.bluetooth import MONOTONIC_TIME, async_register_scanner
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import CALLBACK_TYPE, HomeAssistant, callback

from .const import DOMAIN
from .custom import (
    BLE_CONNECT_EVENT_TYPE,
    BLE_CONNECT_RESULT_EVENT_TYPE,
    BLE_DISCONNECT_EVENT_TYPE,
    BLE_DISCONNECTED_EVENT_TYPE,
    BLE_ERROR_EVENT_TYPE,
    BLE_NOTIFY_EVENT_TYPE,
    BLE_READ_EVENT_TYPE,
    BLE_READ_RESULT_EVENT_TYPE,
    BLE_SUBSCRIBE_EVENT_TYPE,
    BLE_WRITE_EVENT_TYPE,
    BLE_WRITE_RESULT_EVENT_TYPE,
)

_LOGGER = logging.getLogger(__name__)


class VacaBLEScanner(BaseHaRemoteScanner):
    """BLE scanner that receives advertisements from the Android device via Wyoming."""

    @callback
    def inject_advertisement(self, adv_data: dict) -> None:
        """Inject a BLE advertisement received from Android."""
        try:
            manufacturer_data: dict[int, bytes] = {}
            for k, v in adv_data.get("manufacturer_data", {}).items():
                try:
                    manufacturer_data[int(k)] = b64decode(v)
                except Exception:  # noqa: BLE001
                    pass

            service_data: dict[str, bytes] = {}
            for k, v in adv_data.get("service_data", {}).items():
                try:
                    service_data[k] = b64decode(v)
                except Exception:  # noqa: BLE001
                    pass

            tx_power = adv_data.get("tx_power", -1)

            self._async_on_advertisement(
                address=adv_data.get("address", ""),
                rssi=adv_data.get("rssi", -100),
                local_name=adv_data.get("local_name") or None,
                service_uuids=adv_data.get("service_uuids", []),
                service_data=service_data,
                manufacturer_data=manufacturer_data,
                tx_power=tx_power if tx_power != -1 else None,
                details={},
                advertisement_monotonic_time=MONOTONIC_TIME(),
            )
        except Exception as e:  # noqa: BLE001
            _LOGGER.debug("Error injecting BLE advertisement: %s", e)


class VacaBleGattProxy:
    """Proxy BLE GATT operations through the Android device via Wyoming custom-events.

    Outgoing requests are sent via a callback set by the satellite entity.
    Incoming results resolve pending futures and fire HA bus events so that
    automations and scripts can react to them.
    """

    def __init__(self, hass: HomeAssistant, entry_id: str) -> None:
        """Initialise the GATT proxy."""
        self._hass = hass
        self._entry_id = entry_id
        self._send_callback: Callable[[str, dict[str, Any]], None] | None = None
        # Pending futures keyed by address (uppercase) or "addr/svc/char"
        self._connect_futures: dict[str, asyncio.Future] = {}
        self._read_futures: dict[str, asyncio.Future] = {}
        self._write_futures: dict[str, asyncio.Future] = {}

    # ── Send plumbing ──────────────────────────────────────────────────────────

    def set_send_callback(
        self, send_callback: Callable[[str, dict[str, Any]], None]
    ) -> None:
        """Register the callback used to send events to Android."""
        self._send_callback = send_callback

    def _send(self, event_type: str, data: dict[str, Any]) -> None:
        if self._send_callback:
            self._send_callback(event_type, data)
        else:
            _LOGGER.warning("BLE GATT: no send callback, dropping %s", event_type)

    # ── Public API ─────────────────────────────────────────────────────────────

    async def async_connect(self, address: str, timeout: float = 10.0) -> dict:
        """Connect to a BLE device and return its discovered service tree."""
        addr = address.upper()
        fut: asyncio.Future = self._hass.loop.create_future()
        self._connect_futures[addr] = fut
        self._send(BLE_CONNECT_EVENT_TYPE, {"address": address})
        try:
            return await asyncio.wait_for(fut, timeout=timeout)
        except TimeoutError:
            self._connect_futures.pop(addr, None)
            raise

    async def async_disconnect(self, address: str) -> None:
        """Disconnect from a BLE device."""
        self._send(BLE_DISCONNECT_EVENT_TYPE, {"address": address})

    async def async_read_characteristic(
        self,
        address: str,
        service: str,
        characteristic: str,
        timeout: float = 5.0,
    ) -> bytes:
        """Read a GATT characteristic and return the raw bytes."""
        key = _gatt_key(address, service, characteristic)
        fut: asyncio.Future = self._hass.loop.create_future()
        self._read_futures[key] = fut
        self._send(
            BLE_READ_EVENT_TYPE,
            {"address": address, "service": service, "characteristic": characteristic},
        )
        try:
            result = await asyncio.wait_for(fut, timeout=timeout)
            return b64decode(result.get("value", ""))
        except TimeoutError:
            self._read_futures.pop(key, None)
            raise

    async def async_write_characteristic(
        self,
        address: str,
        service: str,
        characteristic: str,
        value: bytes,
        response: bool = True,
        timeout: float = 5.0,
    ) -> bool:
        """Write a GATT characteristic.  Returns True on success."""
        key = _gatt_key(address, service, characteristic)
        fut: asyncio.Future = self._hass.loop.create_future()
        self._write_futures[key] = fut
        self._send(
            BLE_WRITE_EVENT_TYPE,
            {
                "address": address,
                "service": service,
                "characteristic": characteristic,
                "value": b64encode(value).decode(),
                "response": str(response).lower(),
            },
        )
        try:
            result = await asyncio.wait_for(fut, timeout=timeout)
            return bool(result.get("success", False))
        except TimeoutError:
            self._write_futures.pop(key, None)
            raise

    def async_subscribe_notification(
        self,
        address: str,
        service: str,
        characteristic: str,
        enable: bool = True,
    ) -> None:
        """Subscribe or unsubscribe from GATT characteristic notifications."""
        self._send(
            BLE_SUBSCRIBE_EVENT_TYPE,
            {
                "address": address,
                "service": service,
                "characteristic": characteristic,
                "enable": str(enable).lower(),
            },
        )

    # ── Incoming result handler ────────────────────────────────────────────────

    @callback
    def handle_result(self, event_type: str, data: dict[str, Any]) -> None:
        """Dispatch an incoming BLE result from Android.

        Called by the satellite entity from the HA event loop.
        """
        address = data.get("address", "").upper()

        if event_type == BLE_CONNECT_RESULT_EVENT_TYPE:
            fut = self._connect_futures.pop(address, None)
            if fut and not fut.done():
                if data.get("success"):
                    fut.set_result(data)
                else:
                    fut.set_exception(Exception(f"BLE connect failed: {data}"))
            self._hass.bus.async_fire(
                f"{DOMAIN}_ble_connect_result",
                {"entry_id": self._entry_id, **data},
            )

        elif event_type == BLE_DISCONNECTED_EVENT_TYPE:
            # Cancel all pending futures for this device
            for store in (self._connect_futures, self._read_futures, self._write_futures):
                for key in list(store.keys()):
                    if key.upper().startswith(address):
                        fut = store.pop(key)
                        if not fut.done():
                            fut.set_exception(Exception("Device disconnected"))
            self._hass.bus.async_fire(
                f"{DOMAIN}_ble_disconnected",
                {"entry_id": self._entry_id, **data},
            )

        elif event_type == BLE_READ_RESULT_EVENT_TYPE:
            key = _gatt_key(address, data.get("service", ""), data.get("characteristic", ""))
            fut = self._read_futures.pop(key, None)
            if fut and not fut.done():
                fut.set_result(data)
            self._hass.bus.async_fire(
                f"{DOMAIN}_ble_read_result",
                {"entry_id": self._entry_id, **data},
            )

        elif event_type == BLE_WRITE_RESULT_EVENT_TYPE:
            key = _gatt_key(address, data.get("service", ""), data.get("characteristic", ""))
            fut = self._write_futures.pop(key, None)
            if fut and not fut.done():
                fut.set_result(data)
            self._hass.bus.async_fire(
                f"{DOMAIN}_ble_write_result",
                {"entry_id": self._entry_id, **data},
            )

        elif event_type == BLE_NOTIFY_EVENT_TYPE:
            self._hass.bus.async_fire(
                f"{DOMAIN}_ble_notify",
                {"entry_id": self._entry_id, **data},
            )

        elif event_type == BLE_ERROR_EVENT_TYPE:
            operation = data.get("operation", "")
            message = data.get("message", "Unknown error")
            _LOGGER.warning("BLE GATT error [%s] %s: %s", address, operation, message)
            store = {
                "connect": self._connect_futures,
                "read": self._read_futures,
                "write": self._write_futures,
            }.get(operation)
            if store:
                for key in list(store.keys()):
                    if key.upper().startswith(address):
                        fut = store.pop(key)
                        if not fut.done():
                            fut.set_exception(Exception(message))
            self._hass.bus.async_fire(
                f"{DOMAIN}_ble_error",
                {"entry_id": self._entry_id, **data},
            )


def _gatt_key(address: str, service: str, characteristic: str) -> str:
    return f"{address.upper()}/{service.lower()}/{characteristic.lower()}"


async def async_connect_ble_scanner(
    hass: HomeAssistant, entry: ConfigEntry
) -> tuple[VacaBLEScanner, CALLBACK_TYPE]:
    """Create and register a BLE scanner for this config entry."""
    # Use unique_id if available, fallback to entry_id
    source = f"vaca_{entry.unique_id or entry.entry_id}"

    connector = HaBluetoothConnector(
        client=None,
        source=source,
        can_connect=lambda: False,
    )

    scanner = VacaBLEScanner(
        source,
        f"VACA {entry.title}",
        connector,
        False,  # connectable=False - passive scanning only
    )

    # async_setup() is a coroutine, must be awaited
    cancel_setup = scanner.async_setup()

    unregister = async_register_scanner(
        hass,
        scanner,
        source_domain=DOMAIN,
        source_config_entry_id=entry.entry_id,
    )

    @callback
    def _async_unload() -> None:
        unregister()
        cancel_setup()

    _LOGGER.debug("BLE scanner registered for %s (%s)", entry.title, source)
    return scanner, _async_unload
