"""Bluetooth proxy support for VACA - registers Android as a BLE remote scanner."""

from __future__ import annotations

import asyncio
import logging
from base64 import b64decode, b64encode
from collections.abc import Callable
from typing import Any

from bleak.backends.client import BaseBleakClient
from bleak.backends.service import BleakGATTServiceCollection
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


# ── Stub GATT types for BleakGATTServiceCollection ────────────────────────────

class _FakeGATTChar:
    """Minimal characteristic stub — duck-typed for BleakGATTServiceCollection."""

    def __init__(
        self,
        uuid: str,
        handle: int,
        service_uuid: str,
        service_handle: int,
        properties: int,
    ) -> None:
        self.uuid = uuid.lower()
        self.handle = handle
        self.service_uuid = service_uuid.lower()
        self.service_handle = service_handle
        self.properties = properties
        self.descriptors: list = []
        self.description = uuid


class _FakeGATTService:
    """Minimal service stub — duck-typed for BleakGATTServiceCollection."""

    def __init__(self, uuid: str, handle: int) -> None:
        self.uuid = uuid.lower()
        self.handle = handle
        self.characteristics: list[_FakeGATTChar] = []
        self.description = uuid

    def get_characteristic(self, specifier: Any) -> _FakeGATTChar | None:
        uuid_str = str(specifier).lower()
        for char in self.characteristics:
            if char.uuid == uuid_str:
                return char
        return None


def _build_service_collection(services_data: list[dict]) -> BleakGATTServiceCollection:
    """Build a BleakGATTServiceCollection from the Android ble_connect_result data."""
    coll = BleakGATTServiceCollection()
    handle = 1
    for svc_data in services_data:
        svc = _FakeGATTService(svc_data.get("uuid", ""), handle)
        handle += 1
        coll.add_service(svc)  # Must be added BEFORE its characteristics
        for char_data in svc_data.get("characteristics", []):
            char = _FakeGATTChar(
                uuid=char_data.get("uuid", ""),
                handle=handle,
                service_uuid=svc.uuid,
                service_handle=svc.handle,
                properties=char_data.get("properties", 0),
            )
            handle += 1
            coll.add_characteristic(char)  # Appends to svc.characteristics internally
    return coll


# ── VacaBLEScanner ─────────────────────────────────────────────────────────────

class VacaBLEScanner(BaseHaRemoteScanner):
    """BLE scanner that receives advertisements from the Android device via Wyoming."""

    @property
    def scanning(self) -> bool:
        """Always report as scanning — VACA is continuously scanning on the Android side."""
        return True

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
        """Initialise the GATT proxy."""
        self._hass = hass
        self._entry_id = entry_id
        self._send_callback: Callable[[str, dict[str, Any]], None] | None = None
        # Pending futures keyed by address (uppercase) or "addr/svc/char"
        self._connect_futures: dict[str, asyncio.Future] = {}
        self._read_futures: dict[str, asyncio.Future] = {}
        self._write_futures: dict[str, asyncio.Future] = {}
        # Notification callbacks: "ADDRESS/char_uuid" → list of callables
        self._notify_callbacks: dict[str, list[Callable]] = {}
        # Disconnection callbacks: "ADDRESS" → list of callables
        self._disconnect_callbacks: dict[str, list[Callable]] = {}

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
        cbs = self._notify_callbacks.get(key, [])
        try:
            cbs.remove(cb)
        except ValueError:
            pass

    def register_disconnect_callback(self, address: str, cb: Callable) -> None:
        """Register a callback to be called when a device disconnects."""
        self._disconnect_callbacks.setdefault(address.upper(), []).append(cb)

    def unregister_disconnect_callback(self, address: str, cb: Callable) -> None:
        """Unregister a disconnection callback."""
        cbs = self._disconnect_callbacks.get(address.upper(), [])
        try:
            cbs.remove(cb)
        except ValueError:
            pass

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
            # Call registered disconnect callbacks
            for cb in list(self._disconnect_callbacks.pop(address, [])):
                try:
                    cb()
                except Exception:  # noqa: BLE001
                    pass
            # Clean up notify callbacks for this device
            for key in list(self._notify_callbacks.keys()):
                if key.startswith(f"{address}/"):
                    del self._notify_callbacks[key]
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


# ── VacaBleakClient ────────────────────────────────────────────────────────────

class VacaBleakClient(BaseBleakClient):
    """BleakClient that routes all GATT operations through VacaBleGattProxy.

    Never instantiated directly — use make_for_proxy() to get a subclass with
    the proxy pre-bound, then pass that class to HaBluetoothConnector.
    """

    # Populated by make_for_proxy(); must be set before any instance is created.
    _bound_proxy: VacaBleGattProxy | None = None

    @classmethod
    def make_for_proxy(cls, proxy: VacaBleGattProxy) -> type[VacaBleakClient]:
        """Return a subclass with *proxy* pre-bound as a class attribute."""
        return type(f"VacaBleakClient_{entry_id_of(proxy)}", (cls,), {"_bound_proxy": proxy})

    # ── Lifecycle ──────────────────────────────────────────────────────────────

    def __init__(self, address_or_ble_device: Any, **kwargs: Any) -> None:
        super().__init__(address_or_ble_device, **kwargs)
        if hasattr(address_or_ble_device, "address"):
            self._address = address_or_ble_device.address.upper()
        else:
            self._address = str(address_or_ble_device).upper()
        self._is_connected = False
        self._mtu = 23  # default minimum BLE MTU
        self._dc_callback: Callable | None = None

        if self._bound_proxy is None:
            raise RuntimeError(
                "VacaBleakClient has no bound proxy; use make_for_proxy() first."
            )
        self._proxy: VacaBleGattProxy = self._bound_proxy  # type: ignore[assignment]

    @property
    def is_connected(self) -> bool:
        return self._is_connected

    @property
    def mtu_size(self) -> int:
        return self._mtu

    def set_disconnected_callback(
        self,
        callback: Callable | None,
        **kwargs: Any,
    ) -> None:
        """Store a callback to be fired when the device disconnects."""
        if self._dc_callback is not None:
            self._proxy.unregister_disconnect_callback(self._address, self._dc_callback)
            self._dc_callback = None
        if callback is not None:
            def _dc_wrapper() -> None:
                self._is_connected = False
                callback(self)
            self._dc_callback = _dc_wrapper
            self._proxy.register_disconnect_callback(self._address, self._dc_callback)

    async def connect(self, **kwargs: Any) -> bool:
        """Connect to the device and populate self.services."""
        try:
            result = await self._proxy.async_connect(
                self._address,
                timeout=kwargs.get("timeout", 10.0),
            )
        except Exception as ex:
            _LOGGER.debug("VacaBleakClient connect(%s) failed: %s", self._address, ex)
            return False

        self._is_connected = True
        try:
            self.services = _build_service_collection(result.get("services", []))
        except Exception as ex:  # noqa: BLE001
            _LOGGER.warning("VacaBleakClient: failed to build service collection for %s: %s", self._address, ex)
        return True

    async def disconnect(self) -> bool:
        """Disconnect from the device."""
        await self._proxy.async_disconnect(self._address)
        self._is_connected = False
        return True

    async def pair(self, **kwargs: Any) -> bool:
        raise NotImplementedError("VacaBleakClient does not support pairing")

    async def unpair(self) -> bool:
        raise NotImplementedError("VacaBleakClient does not support unpairing")

    # ── GATT read / write ──────────────────────────────────────────────────────

    async def read_gatt_char(self, char_specifier: Any, **kwargs: Any) -> bytearray:
        """Read a characteristic.  char_specifier may be UUID str or object."""
        svc_uuid, char_uuid = self._resolve_char(char_specifier)
        data = await self._proxy.async_read_characteristic(
            self._address, svc_uuid, char_uuid,
            timeout=kwargs.get("timeout", 5.0),
        )
        return bytearray(data)

    async def read_gatt_descriptor(self, handle: int, **kwargs: Any) -> bytearray:
        raise NotImplementedError("VacaBleakClient does not support descriptor reads")

    async def write_gatt_char(
        self,
        char_specifier: Any,
        data: bytes | bytearray,
        response: bool = True,
        **kwargs: Any,
    ) -> None:
        """Write to a characteristic."""
        svc_uuid, char_uuid = self._resolve_char(char_specifier)
        await self._proxy.async_write_characteristic(
            self._address, svc_uuid, char_uuid,
            bytes(data), response=response,
            timeout=kwargs.get("timeout", 5.0),
        )

    async def write_gatt_descriptor(
        self, handle: int, data: bytes | bytearray, **kwargs: Any
    ) -> None:
        raise NotImplementedError("VacaBleakClient does not support descriptor writes")

    # ── Notifications ──────────────────────────────────────────────────────────

    async def start_notify(
        self, char_specifier: Any, callback: Callable, **kwargs: Any
    ) -> None:
        """Subscribe to characteristic notifications."""
        svc_uuid, char_uuid = self._resolve_char(char_specifier)

        # Pass the characteristic object (new bleak API) so integrations can
        # access .uuid / .handle / .properties on the sender argument.
        char_obj = self.services.get_characteristic(char_uuid)

        def _notify_wrapper(data: bytearray) -> None:
            # char_obj is our _FakeGATTChar which has .uuid / .handle / .properties
            callback(char_obj, data)

        self._proxy.register_notify_callback(self._address, char_uuid, _notify_wrapper)
        self._proxy.async_subscribe_notification(
            self._address, svc_uuid, char_uuid, enable=True
        )

    async def stop_notify(self, char_specifier: Any, **kwargs: Any) -> None:
        """Unsubscribe from characteristic notifications."""
        svc_uuid, char_uuid = self._resolve_char(char_specifier)
        # Remove all callbacks for this characteristic
        key = f"{self._address.upper()}/{char_uuid}"
        self._proxy._notify_callbacks.pop(key, None)
        self._proxy.async_subscribe_notification(
            self._address, svc_uuid, char_uuid, enable=False
        )

    # ── Helper ─────────────────────────────────────────────────────────────────

    def _resolve_char(self, specifier: Any) -> tuple[str, str]:
        """Return (service_uuid, char_uuid) from any bleak char specifier."""
        if hasattr(specifier, "uuid") and hasattr(specifier, "service_uuid"):
            return str(specifier.service_uuid).lower(), str(specifier.uuid).lower()
        uuid_str = str(specifier).lower()
        char = self.services.get_characteristic(uuid_str)
        if char is not None:
            return str(char.service_uuid).lower(), str(char.uuid).lower()
        raise KeyError(f"Characteristic {specifier!r} not found in service collection")


def entry_id_of(proxy: VacaBleGattProxy) -> str:
    """Return a short identifier string for use in dynamic class names."""
    return proxy._entry_id[:8]


# ── Helpers ────────────────────────────────────────────────────────────────────

def _gatt_key(address: str, service: str, characteristic: str) -> str:
    return f"{address.upper()}/{service.lower()}/{characteristic.lower()}"


# ── Scanner registration ───────────────────────────────────────────────────────

async def async_connect_ble_scanner(
    hass: HomeAssistant, entry: ConfigEntry, gatt_proxy: VacaBleGattProxy
) -> tuple[VacaBLEScanner, CALLBACK_TYPE]:
    """Create and register a connectable BLE scanner for this config entry."""
    source = f"vaca_{entry.unique_id or entry.entry_id}"

    connector = HaBluetoothConnector(
        client=VacaBleakClient.make_for_proxy(gatt_proxy),
        source=source,
        # Only report connectable when the satellite TCP link is up (send_callback set)
        can_connect=lambda: gatt_proxy._send_callback is not None,
    )

    scanner = VacaBLEScanner(
        source,
        f"VACA {entry.title}",
        connector,
        True,  # connectable=True — full proxy, not passive-only
    )

    cancel_setup = scanner.async_setup()

    unregister = async_register_scanner(
        hass,
        scanner,
        source_domain=DOMAIN,
        source_config_entry_id=entry.entry_id,
        connectable=True,
    )

    @callback
    def _async_unload() -> None:
        unregister()
        cancel_setup()

    _LOGGER.debug("BLE scanner registered (connectable) for %s (%s)", entry.title, source)
    return scanner, _async_unload
