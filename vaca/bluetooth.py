"""Bluetooth proxy support for VACA - registers Android as a BLE remote scanner."""

from __future__ import annotations

import asyncio
import hashlib
import importlib
import logging
import re
from base64 import b64decode, b64encode
from collections.abc import Callable
from functools import partial
from typing import Any

from bleak.assigned_numbers import CHARACTERISTIC_PROPERTIES
from bleak.backends.characteristic import BleakGATTCharacteristic
from bleak.backends.client import BaseBleakClient
from bleak.backends.service import BleakGATTService, BleakGATTServiceCollection
from bleak.exc import BleakError
from habluetooth import Allocations, BaseHaRemoteScanner, HaBluetoothConnector
from homeassistant.components.bluetooth import MONOTONIC_TIME, async_register_scanner
from homeassistant.components.bluetooth import websocket_api as bt_websocket_api
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import CALLBACK_TYPE, HomeAssistant, callback

from .const import DOMAIN
from .custom import (
    BLE_CONNECT_EVENT_TYPE,
    BLE_CONNECT_RESULT_EVENT_TYPE,
    BLE_CONNECTIONS_UPDATE_EVENT_TYPE,
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
_BLUETOOTH_ADDRESS_RE = re.compile(r"^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")

GATT_HEADER_SIZE = 3
DEFAULT_MTU = 23
DEFAULT_CONNECT_TIMEOUT = 10.0
GATT_OP_TIMEOUT = 5.0
CONNECT_FREE_SLOT_TIMEOUT = 2.0


# ── Service collection builder ─────────────────────────────────────────────────

def _build_service_collection(
    services_data: list[dict], mtu: int = DEFAULT_MTU
) -> BleakGATTServiceCollection:
    """Build a BleakGATTServiceCollection from Android ble_connect_result data."""
    max_write = mtu - GATT_HEADER_SIZE
    services = BleakGATTServiceCollection()
    handle = 1
    for svc_data in services_data:
        svc_handle = handle
        handle += 1
        bleak_service = BleakGATTService(
            svc_data, svc_handle, svc_data.get("uuid", "").lower()
        )
        services.add_service(bleak_service)
        for char_data in svc_data.get("characteristics", []):
            char_handle = handle
            handle += 1
            char_props_int = char_data.get("properties", 0)
            props = [
                prop
                for mask, prop in CHARACTERISTIC_PROPERTIES.items()
                if char_props_int & mask
            ]
            bleak_char = BleakGATTCharacteristic(
                char_data,
                char_handle,
                char_data.get("uuid", "").lower(),
                props,
                lambda mtu=max_write: mtu,
                bleak_service,
            )
            services.add_characteristic(bleak_char)
    return services


# ── VacaBLEScanner ─────────────────────────────────────────────────────────────

class VacaBLEScanner(BaseHaRemoteScanner):
    """BLE scanner that receives advertisements from the Android device via Wyoming."""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._proxy: VacaBleGattProxy | None = None

    def set_proxy(self, proxy: VacaBleGattProxy) -> None:
        """Attach the GATT proxy so the scanner can report allocation state."""
        # Re-register safely if set_proxy is called more than once.
        if self._proxy is not None:
            self._proxy.unregister_allocations_listener(self._allocations_updated)
        self._proxy = proxy
        self._proxy.register_allocations_listener(self._allocations_updated)
        # Seed allocation cache once on attach so HA can include this source
        # before the first Android slot-update event arrives.
        self._allocations_updated()

    def get_allocations(self) -> Allocations | None:
        """Return current BLE connection slot state, or None if not yet known."""
        if self._proxy is None:
            return None
        return Allocations(
            self.source,
            self._proxy.connections_limit,
            self._proxy.connections_free,
            list(self._proxy._allocated),
        )

    @callback
    def _allocations_updated(self) -> None:
        """Notify HA bluetooth manager that connection-slot allocations changed."""
        allocation = self.get_allocations()
        # In the HA build used by this project, manager.async_on_allocation_changed
        # takes exactly one positional argument: Allocations.
        hass = getattr(self, "_vaca_hass", None)
        if hass is not None and allocation is not None:
            try:
                manager = bt_websocket_api._get_manager(hass)
                manager.async_on_allocation_changed(allocation)
            except Exception:  # noqa: BLE001
                pass

        # BaseHaRemoteScanner changed internal hook names across versions;
        # call whichever exists so manager can refresh websocket subscribers.
        for method_name in (
            "_async_on_allocation_changed",
            "async_on_allocation_changed",
            "_async_on_allocation_update",
            "async_on_allocation_update",
        ):
            method = getattr(self, method_name, None)
            if callable(method):
                try:
                    method()
                except Exception:  # noqa: BLE001
                    continue
                break

    @property
    def current_mode(self) -> Any | None:
        """Return current scan mode in the type HA websocket expects."""
        if not self.scanning:
            return None
        return _passive_mode_value()

    @property
    def requested_mode(self) -> Any:
        """Return requested scan mode in the type HA websocket expects."""
        return _passive_mode_value()


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


# ── VacaBleGattProxy ───────────────────────────────────────────────────────────

class VacaBleGattProxy:
    """Proxy BLE GATT operations through the Android device via Wyoming custom-events.

    Outgoing requests are sent via a callback set by the satellite entity.
    Incoming results resolve pending futures and fire HA bus events so that
    automations and scripts can react to them.
    """

    def __init__(self, hass: HomeAssistant, entry_id: str) -> None:
        self._hass = hass
        self._entry_id = entry_id
        self._send_callback: Callable[[str, dict[str, Any]], None] | None = None
        # Pending futures keyed by address (uppercase) or "addr/svc/char"
        self._connect_futures: dict[str, asyncio.Future] = {}
        self._read_futures: dict[str, asyncio.Future] = {}
        self._write_futures: dict[str, asyncio.Future] = {}
        # Notification callbacks: "ADDRESS/char_uuid" → list of callables
        self._notify_callbacks: dict[str, list[Callable]] = {}
        # Disconnection callbacks: "ADDRESS" → set of callables
        self._disconnect_callbacks: dict[str, set[Callable]] = {}
        # Connection slot tracking (updated via ble_connections_update events from Android)
        self._connections_free: int = 0
        self._connections_limit: int = 0
        self._allocated: list[str] = []
        self._free_slot_event: asyncio.Event = asyncio.Event()
        self._allocations_listeners: set[Callable[[], None]] = set()

    @property
    def connections_free(self) -> int:
        """Number of free BLE connection slots on the Android device."""
        return self._connections_free

    @property
    def connections_limit(self) -> int:
        """Maximum BLE connections configured on the Android device (0 = unknown)."""
        return self._connections_limit

    async def async_wait_for_free_slot(
        self, timeout: float = CONNECT_FREE_SLOT_TIMEOUT
    ) -> None:
        """Wait until a BLE connection slot is free (mirrors ESPHome behavior).

        Returns immediately if limit is unknown or a slot is already free.
        After a timeout the call returns silently — the connect attempt will
        proceed and may fail at the Android side if no slot opens up.
        """
        if self._connections_limit == 0 or self._connections_free > 0:
            return
        self._free_slot_event.clear()
        try:
            await asyncio.wait_for(self._free_slot_event.wait(), timeout=timeout)
        except TimeoutError:
            _LOGGER.debug(
                "Timed out waiting for a free BLE connection slot (limit=%d, free=%d)",
                self._connections_limit,
                self._connections_free,
            )

    # ── Send plumbing ──────────────────────────────────────────────────────────

    def set_send_callback(
        self, send_callback: Callable[[str, dict[str, Any]], None] | None
    ) -> None:
        """Register the callback used to send events to Android."""
        self._send_callback = send_callback

    def register_allocations_listener(self, listener: Callable[[], None]) -> None:
        """Register callback for BLE connection-slot updates."""
        self._allocations_listeners.add(listener)

    def unregister_allocations_listener(self, listener: Callable[[], None]) -> None:
        """Unregister callback for BLE connection-slot updates."""
        self._allocations_listeners.discard(listener)

    def _send(self, event_type: str, data: dict[str, Any]) -> None:
        if self._send_callback:
            self._send_callback(event_type, data)
        else:
            _LOGGER.warning("BLE GATT: no send callback, dropping %s", event_type)

    # ── Notification / disconnection callback registration ─────────────────────

    def register_notify_callback(
        self, address: str, char_uuid: str, cb: Callable
    ) -> None:
        """Register a per-characteristic notification callback."""
        key = f"{address.upper()}/{char_uuid.lower()}"
        self._notify_callbacks.setdefault(key, []).append(cb)

    def unregister_notify_callback(
        self, address: str, char_uuid: str, cb: Callable
    ) -> None:
        """Unregister a previously registered notification callback."""
        key = f"{address.upper()}/{char_uuid.lower()}"
        try:
            self._notify_callbacks.get(key, []).remove(cb)
        except ValueError:
            pass

    def register_disconnect_callback(self, address: str, cb: Callable) -> None:
        """Register a callback to be called when a device disconnects."""
        self._disconnect_callbacks.setdefault(address.upper(), set()).add(cb)

    def unregister_disconnect_callback(self, address: str, cb: Callable) -> None:
        """Unregister a disconnection callback."""
        self._disconnect_callbacks.get(address.upper(), set()).discard(cb)

    # ── Public API ─────────────────────────────────────────────────────────────

    async def async_connect(
        self, address: str, timeout: float = DEFAULT_CONNECT_TIMEOUT
    ) -> dict:
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
        timeout: float = GATT_OP_TIMEOUT,
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
        timeout: float = GATT_OP_TIMEOUT,
    ) -> None:
        """Write a GATT characteristic."""
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
            await asyncio.wait_for(fut, timeout=timeout)
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
                    fut.set_exception(
                        BleakError(f"BLE connect failed: {data.get('error', 'unknown')}")
                    )
            self._hass.bus.async_fire(
                f"{DOMAIN}_ble_connect_result",
                {"entry_id": self._entry_id, **data},
            )

        elif event_type == BLE_DISCONNECTED_EVENT_TYPE:
            for cb in list(self._disconnect_callbacks.pop(address, set())):
                try:
                    cb()
                except Exception:  # noqa: BLE001
                    pass
            for key in [k for k in self._notify_callbacks if k.startswith(f"{address}/")]:
                del self._notify_callbacks[key]
            for store in (self._connect_futures, self._read_futures, self._write_futures):
                for key in [k for k in store if k.upper().startswith(address)]:
                    fut = store.pop(key)
                    if not fut.done():
                        fut.set_exception(BleakError("Device disconnected"))
            self._hass.bus.async_fire(
                f"{DOMAIN}_ble_disconnected",
                {"entry_id": self._entry_id, **data},
            )

        elif event_type == BLE_READ_RESULT_EVENT_TYPE:
            key = _gatt_key(
                address, data.get("service", ""), data.get("characteristic", "")
            )
            fut = self._read_futures.pop(key, None)
            if fut and not fut.done():
                fut.set_result(data)
            self._hass.bus.async_fire(
                f"{DOMAIN}_ble_read_result",
                {"entry_id": self._entry_id, **data},
            )

        elif event_type == BLE_WRITE_RESULT_EVENT_TYPE:
            key = _gatt_key(
                address, data.get("service", ""), data.get("characteristic", "")
            )
            fut = self._write_futures.pop(key, None)
            if fut and not fut.done():
                if data.get("success", True):
                    fut.set_result(data)
                else:
                    fut.set_exception(
                        BleakError(
                            f"Write failed on characteristic"
                            f" {data.get('characteristic', 'unknown')}"
                        )
                    )
            self._hass.bus.async_fire(
                f"{DOMAIN}_ble_write_result",
                {"entry_id": self._entry_id, **data},
            )

        elif event_type == BLE_NOTIFY_EVENT_TYPE:
            char_uuid = data.get("characteristic", "").lower()
            key = f"{address}/{char_uuid}"
            raw = bytearray(b64decode(data.get("value", "")))
            for cb in list(self._notify_callbacks.get(key, [])):
                try:
                    cb(raw)
                except Exception:  # noqa: BLE001
                    pass
            self._hass.bus.async_fire(
                f"{DOMAIN}_ble_notify",
                {"entry_id": self._entry_id, **data},
            )

        elif event_type == BLE_CONNECTIONS_UPDATE_EVENT_TYPE:
            self._connections_free = data.get("free", 0)
            self._connections_limit = data.get("limit", 0)
            self._allocated = data.get("allocated", [])
            _LOGGER.debug(
                "BLE connections update: free=%d limit=%d allocated=%s",
                self._connections_free,
                self._connections_limit,
                self._allocated,
            )
            if self._connections_free > 0:
                self._free_slot_event.set()
            for listener in list(self._allocations_listeners):
                try:
                    listener()
                except Exception:  # noqa: BLE001
                    pass

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
                for key in [k for k in store if k.upper().startswith(address)]:
                    fut = store.pop(key)
                    if not fut.done():
                        fut.set_exception(BleakError(message))
            self._hass.bus.async_fire(
                f"{DOMAIN}_ble_error",
                {"entry_id": self._entry_id, **data},
            )


# ── VacaBleakClient ────────────────────────────────────────────────────────────

class VacaBleakClient(BaseBleakClient):
    """BleakClient that routes all GATT operations through VacaBleGattProxy.

    Instantiated via partial(VacaBleakClient, gatt_proxy=proxy) so that
    HaBluetoothConnector can call it as a plain constructor.
    """

    def __init__(
        self,
        address_or_ble_device: Any,
        *args: Any,
        gatt_proxy: VacaBleGattProxy,
        **kwargs: Any,
    ) -> None:
        super().__init__(address_or_ble_device, *args, **kwargs)
        if hasattr(address_or_ble_device, "address"):
            self._address = address_or_ble_device.address.upper()
        else:
            self._address = str(address_or_ble_device).upper()
        self._proxy = gatt_proxy
        self._is_connected = False
        self._mtu: int = DEFAULT_MTU
        self._disconnected_callback: Callable | None = None
        # handle → (char_uuid, notify_wrapper) — used for cleanup in stop_notify / disconnect
        self._notify_cancels: dict[int, tuple[str, Callable]] = {}

    # ── Properties ─────────────────────────────────────────────────────────────

    @property
    def is_connected(self) -> bool:
        return self._is_connected

    @property
    def mtu_size(self) -> int:
        return self._mtu

    # ── Disconnect lifecycle ───────────────────────────────────────────────────

    def set_disconnected_callback(
        self, callback: Callable | None, **kwargs: Any
    ) -> None:
        """Store a disconnection callback (called by HA after connect)."""
        self._disconnected_callback = callback

    def _async_disconnected_cleanup(self) -> None:
        """Tear down state on any disconnect path."""
        self._is_connected = False
        self.services = BleakGATTServiceCollection()
        for _handle, (char_uuid, wrapper) in list(self._notify_cancels.items()):
            self._proxy.unregister_notify_callback(self._address, char_uuid, wrapper)
        self._notify_cancels.clear()
        self._proxy.unregister_disconnect_callback(
            self._address, self._async_ble_device_disconnected
        )

    def _async_ble_device_disconnected(self) -> None:
        """Handle an unsolicited disconnection reported by Android."""
        was_connected = self._is_connected
        self._async_disconnected_cleanup()
        if was_connected and self._disconnected_callback:
            self._disconnected_callback()
            self._disconnected_callback = None

    # ── Lifecycle ──────────────────────────────────────────────────────────────

    async def _wait_for_free_connection_slot(self, timeout: float) -> None:
        """Wait for a free BLE slot before attempting connection."""
        await self._proxy.async_wait_for_free_slot(
            min(CONNECT_FREE_SLOT_TIMEOUT, timeout)
        )

    async def connect(self, **kwargs: Any) -> bool:
        """Connect to the device and populate self.services."""
        await self._wait_for_free_connection_slot(
            kwargs.get("timeout", DEFAULT_CONNECT_TIMEOUT)
        )
        self._proxy.register_disconnect_callback(
            self._address, self._async_ble_device_disconnected
        )
        try:
            result = await self._proxy.async_connect(
                self._address,
                timeout=kwargs.get("timeout", DEFAULT_CONNECT_TIMEOUT),
            )
        except BleakError:
            self._proxy.unregister_disconnect_callback(
                self._address, self._async_ble_device_disconnected
            )
            raise
        except TimeoutError as err:
            self._proxy.unregister_disconnect_callback(
                self._address, self._async_ble_device_disconnected
            )
            raise BleakError(
                f"BLE connect to {self._address} timed out"
            ) from err
        except Exception as err:
            self._proxy.unregister_disconnect_callback(
                self._address, self._async_ble_device_disconnected
            )
            raise BleakError(str(err)) from err

        self._is_connected = True
        if raw_mtu := result.get("mtu"):
            self._mtu = int(raw_mtu)
        try:
            self.services = _build_service_collection(
                result.get("services", []), self._mtu
            )
        except Exception as ex:  # noqa: BLE001
            _LOGGER.warning(
                "VacaBleakClient: failed to build service collection for %s: %s",
                self._address,
                ex,
            )
        return True

    async def disconnect(self) -> bool:
        """Disconnect from the device."""
        await self._proxy.async_disconnect(self._address)
        self._async_disconnected_cleanup()
        return True

    async def pair(self, **kwargs: Any) -> bool:
        raise NotImplementedError("VacaBleakClient does not support pairing")

    async def unpair(self) -> bool:
        raise NotImplementedError("VacaBleakClient does not support unpairing")

    # ── GATT read / write ──────────────────────────────────────────────────────

    async def read_gatt_char(
        self, characteristic: BleakGATTCharacteristic, **kwargs: Any
    ) -> bytearray:
        """Read a GATT characteristic."""
        self._raise_if_not_connected()
        try:
            data = await self._proxy.async_read_characteristic(
                self._address,
                characteristic.service_uuid,
                characteristic.uuid,
                timeout=kwargs.get("timeout", GATT_OP_TIMEOUT),
            )
        except BleakError:
            raise
        except TimeoutError as err:
            raise BleakError(
                f"Read timed out on characteristic {characteristic.uuid}"
            ) from err
        except Exception as err:
            raise BleakError(str(err)) from err
        return bytearray(data)

    async def read_gatt_descriptor(self, handle: int, **kwargs: Any) -> bytearray:
        raise NotImplementedError("VacaBleakClient does not support descriptor reads")

    async def write_gatt_char(
        self,
        characteristic: BleakGATTCharacteristic,
        data: bytes | bytearray,
        response: bool = True,
        **kwargs: Any,
    ) -> None:
        """Write to a GATT characteristic."""
        self._raise_if_not_connected()
        try:
            await self._proxy.async_write_characteristic(
                self._address,
                characteristic.service_uuid,
                characteristic.uuid,
                bytes(data),
                response=response,
                timeout=kwargs.get("timeout", GATT_OP_TIMEOUT),
            )
        except BleakError:
            raise
        except TimeoutError as err:
            raise BleakError(
                f"Write timed out on characteristic {characteristic.uuid}"
            ) from err
        except Exception as err:
            raise BleakError(str(err)) from err

    async def write_gatt_descriptor(
        self, handle: int, data: bytes | bytearray, **kwargs: Any
    ) -> None:
        raise NotImplementedError("VacaBleakClient does not support descriptor writes")

    # ── Notifications ──────────────────────────────────────────────────────────

    async def start_notify(
        self,
        characteristic: BleakGATTCharacteristic,
        callback: Callable,
        **kwargs: Any,
    ) -> None:
        """Subscribe to characteristic notifications."""
        self._raise_if_not_connected()
        handle = characteristic.handle
        if handle in self._notify_cancels:
            raise BleakError(
                f"Notifications are already enabled on characteristic"
                f" {characteristic.uuid} (handle {handle})"
            )

        char_uuid = characteristic.uuid

        def _notify_wrapper(data: bytearray) -> None:
            callback(data)

        self._notify_cancels[handle] = (char_uuid, _notify_wrapper)
        self._proxy.register_notify_callback(self._address, char_uuid, _notify_wrapper)
        self._proxy.async_subscribe_notification(
            self._address,
            characteristic.service_uuid,
            char_uuid,
            enable=True,
        )

    async def stop_notify(
        self, characteristic: BleakGATTCharacteristic, **kwargs: Any
    ) -> None:
        """Unsubscribe from characteristic notifications."""
        self._raise_if_not_connected()
        if entry := self._notify_cancels.pop(characteristic.handle, None):
            char_uuid, wrapper = entry
            self._proxy.unregister_notify_callback(self._address, char_uuid, wrapper)
            self._proxy.async_subscribe_notification(
                self._address,
                characteristic.service_uuid,
                char_uuid,
                enable=False,
            )

    # ── Helper ─────────────────────────────────────────────────────────────────

    def _raise_if_not_connected(self) -> None:
        if not self._is_connected:
            raise BleakError(f"{self._address} is not connected")


# ── Helpers ────────────────────────────────────────────────────────────────────

def _gatt_key(address: str, service: str, characteristic: str) -> str:
    return f"{address.upper()}/{service.lower()}/{characteristic.lower()}"


def _passive_mode_value() -> Any:
    """Resolve BluetoothScanningMode.PASSIVE from HA/habluetooth at runtime."""
    candidates: tuple[tuple[str, str], ...] = (
        ("homeassistant.components.bluetooth.models", "BluetoothScanningMode"),
        ("habluetooth", "BluetoothScanningMode"),
        ("habluetooth.models", "BluetoothScanningMode"),
    )
    for module_name, enum_name in candidates:
        try:
            module = importlib.import_module(module_name)
            enum_cls = getattr(module, enum_name, None)
            if enum_cls is not None and hasattr(enum_cls, "PASSIVE"):
                return enum_cls.PASSIVE
        except Exception:  # noqa: BLE001
            continue
    return None


# ── Scanner registration ───────────────────────────────────────────────────────

async def async_connect_ble_scanner(
    hass: HomeAssistant,
    entry: ConfigEntry,
    gatt_proxy: VacaBleGattProxy,
    source_override: str | None = None,
    source_model: str | None = None,
    source_device_id: str | None = None,
) -> tuple[VacaBLEScanner, CALLBACK_TYPE]:
    """Create and register a connectable BLE scanner for this config entry."""
    source = _scanner_source_from_entry(entry, source_override)

    connector = HaBluetoothConnector(
        client=partial(VacaBleakClient, gatt_proxy=gatt_proxy),
        source=source,
        can_connect=lambda: (
            gatt_proxy._send_callback is not None
            and (
                gatt_proxy.connections_limit == 0
                or gatt_proxy.connections_free > 0
            )
        ),
    )

    scanner = VacaBLEScanner(
        source,
        f"VACA {entry.title}",
        connector,
        True,  # connectable=True — full proxy, not passive-only
    )
    # Keep a hass reference for allocation manager callbacks.
    scanner._vaca_hass = hass  # type: ignore[attr-defined]
    scanner.set_proxy(gatt_proxy)

    cancel_setup = scanner.async_setup()

    unregister = async_register_scanner(
        hass,
        scanner,
        source_domain=DOMAIN,
        source_config_entry_id=entry.entry_id,
        source_model=source_model,
        source_device_id=source_device_id,
    )

    @callback
    def _async_unload() -> None:
        unregister()
        cancel_setup()

    _LOGGER.debug(
        "BLE scanner registered (connectable) for %s (%s)", entry.title, source
    )
    return scanner, _async_unload


def _scanner_source_from_entry(
    entry: ConfigEntry, source_override: str | None = None
) -> str:
    """Return a stable scanner source accepted by HA allocation manager.

    Some HA builds only include allocation data for sources that look like
    Bluetooth addresses. Use the config unique_id when it is a MAC; otherwise
    derive a deterministic locally-administered unicast MAC-like source.
    """
    if source_override:
        candidate = source_override.strip().upper()
        if _BLUETOOTH_ADDRESS_RE.match(candidate):
            return candidate

    if entry.unique_id:
        candidate = entry.unique_id.strip().upper()
        if _BLUETOOTH_ADDRESS_RE.match(candidate):
            return candidate

    digest = hashlib.sha1(entry.entry_id.encode("utf-8")).digest()
    mac = bytearray(digest[:6])
    # Locally administered (bit1=1) and unicast (bit0=0).
    mac[0] = (mac[0] | 0x02) & 0xFE
    return ":".join(f"{b:02X}" for b in mac)
