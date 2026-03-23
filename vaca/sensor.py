"""Sensor for Wyoming."""

from __future__ import annotations

from functools import reduce
import logging
from typing import TYPE_CHECKING, Any

from homeassistant.components.sensor import (
    RestoreSensor,
    SensorDeviceClass,
    SensorEntityDescription,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import LIGHT_LUX, PERCENTAGE, EntityCategory
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback
from homeassistant.util.dt import now, parse_datetime

from .const import DOMAIN
from .devices import VASatelliteDevice
from .entity import VASatelliteEntity

if TYPE_CHECKING:
    from homeassistant.components.wyoming import DomainDataItem

UNKNOWN: str = "unknown"

_LOGGER = logging.getLogger(__name__)


async def async_setup_entry(
    hass: HomeAssistant,
    config_entry: ConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up sensor entities."""
    item: DomainDataItem = hass.data[DOMAIN][config_entry.entry_id]
    device: VASatelliteDevice = item.device  # type: ignore[assignment]

    # Setup is only forwarded for satellites
    assert item.device is not None

    entities = [
        WyomingSatelliteSTTSensor(device),
        WyomingSatelliteTTSSensor(device),
        WyomingSatelliteIntentSensor(device),
        WyomingSatelliteOrientationSensor(device),
        WyomingSatelliteBrowserPathSensor(device),
        WyomingSatelliteInstalledAppsSensor(device),
        WyomingSatelliteHttpServerSensor(device),
        WyomingSatelliteWebServerSensor(device),
        WyomingSatelliteRecentAppsSensor(device),
        WyomingSatelliteFrequentAppsSensor(device),
    ]

    if capabilities := device.capabilities:
        if capabilities.get("app_version"):
            entities.append(WyomingSatelliteAppVersionSensor(device))
        if capabilities.get("has_battery"):
            entities.append(WyomingSatelliteBatteryLevelSensor(device))
        if device.has_light_sensor():
            entities.append(WyomingSatelliteLightSensor(device))
        if capabilities.get("has_front_camera"):
            entities.append(WyomingSatelliteLastMotionSensor(device))

    async_add_entities(entities)


class WyomingSatelliteSTTSensor(VASatelliteEntity, RestoreSensor):
    """Entity to represent STT sensor for satellite."""

    entity_description = SensorEntityDescription(
        key="stt",
        translation_key="stt",
        icon="mdi:microphone-message",
    )
    _attr_native_value = UNKNOWN

    async def async_added_to_hass(self) -> None:
        """Call when entity about to be added to hass."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            self._value_changed(state.state)

        self._device.set_stt_listener(self._value_changed)

    @callback
    def _value_changed(self, value: str) -> None:
        """Call when value changed."""
        if value:
            if len(value) > 254:
                # Limit the length of the value to avoid issues with Home Assistant
                value = value[:252] + ".."
            self._attr_native_value = value
            self.async_write_ha_state()


class WyomingSatelliteTTSSensor(VASatelliteEntity, RestoreSensor):
    """Entity to represent TTS sensor for satellite."""

    entity_description = SensorEntityDescription(
        key="tts", translation_key="tts", icon="mdi:speaker-message"
    )
    _attr_native_value = UNKNOWN

    async def async_added_to_hass(self) -> None:
        """Call when entity about to be added to hass."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            self._value_changed(state.state)

        self._device.set_tts_listener(self._value_changed)

    @callback
    def _value_changed(self, value: str) -> None:
        """Call when value changed."""
        if value:
            if len(value) > 254:
                # Limit the length of the value to avoid issues with Home Assistant
                value = value[:252] + ".."
            self._attr_native_value = value
            self.async_write_ha_state()


class WyomingSatelliteIntentSensor(VASatelliteEntity, RestoreSensor):
    """Entity to represent intent sensor for satellite."""

    entity_description = SensorEntityDescription(
        key="intent", translation_key="intent", icon="mdi:message-bulleted"
    )
    _attr_native_value = UNKNOWN

    async def async_added_to_hass(self) -> None:
        """Call when entity about to be added to hass."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            self._attr_native_value = state.state
            self.async_write_ha_state()

        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                f"{DOMAIN}_{self._device.device_id}_intent_output",
                self.status_update,
            )
        )

    @callback
    def status_update(self, data: dict[str, Any]) -> None:
        """Update entity."""
        if data and data.get("intent_output"):
            value = str(
                self.get_key("intent_output.response.speech.plain.speech", data)
            )
            if value:
                if len(value) > 254:
                    # Limit the length of the value to avoid issues with Home Assistant
                    value = value[:252] + ".."
                self._attr_native_value = value

            self._attr_extra_state_attributes = data
            self.async_write_ha_state()

    def get_key(
        self, dot_notation_path: str, data: dict
    ) -> dict[str, dict | str | int] | str | int | None:
        """Try to get a deep value from a dict based on a dot-notation."""

        try:
            if "." in dot_notation_path:
                dn_list = dot_notation_path.split(".")
            else:
                dn_list = [dot_notation_path]
            return reduce(dict.get, dn_list, data)  # type: ignore[return-value]
        except (TypeError, KeyError):
            return None


class _WyomingSatelliteDeviceSensorBase(VASatelliteEntity, RestoreSensor):
    """Base class for device sensors."""

    _attr_native_value = 0
    _listener_class = "status_update"

    async def async_added_to_hass(self) -> None:
        """Call when entity about to be added to hass."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            if self.entity_description.device_class == SensorDeviceClass.TIMESTAMP:
                self._attr_native_value = self._get_timestamp_from_string(state.state)
            else:
                self._attr_native_value = state.state
            self.async_write_ha_state()

        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                f"{DOMAIN}_{self._device.device_id}_{self._listener_class}",
                self.status_update,
            )
        )

    def _get_native_value(self, value: Any) -> Any:
        """Get the native value from the data."""
        if isinstance(value, (int, float)):
            return value
        if isinstance(value, str):
            if value.isdigit():
                return int(value)
            return value
        return value

    def _get_timestamp_from_string(self, timestamp_str: str) -> Any:
        """Convert timestamp string to datetime object."""
        if timestamp_str.startswith("1970-01-01"):
            return None
        if parsed_time := parse_datetime(timestamp_str):
            if parsed_time > now(parsed_time.tzinfo):
                return now(parsed_time.tzinfo)
            return parsed_time
        return None

    @callback
    def status_update(self, data: dict[str, Any]) -> None:
        """Update entity."""
        if self._listener_class == "status_update":
            if sensors := data.get("sensors"):
                if self.entity_description.key in sensors:
                    if (
                        self.entity_description.device_class
                        == SensorDeviceClass.TIMESTAMP
                    ):
                        # Handle timestamp conversion
                        timestamp_str = sensors[self.entity_description.key]
                        self._attr_native_value = self._get_timestamp_from_string(
                            timestamp_str
                        )
                    else:
                        self._attr_native_value = self._get_native_value(
                            sensors[self.entity_description.key]
                        )
                    self.async_write_ha_state()
        elif self._listener_class == "capabilities_update":
            if self._device.capabilities and self._device.capabilities.get(
                self.entity_description.key
            ):
                self._attr_native_value = self._get_native_value(
                    self._device.capabilities[self.entity_description.key]
                )
                self.async_write_ha_state()


class WyomingSatelliteLightSensor(_WyomingSatelliteDeviceSensorBase):
    """Entity to represent light sensor for satellite."""

    entity_description = SensorEntityDescription(
        key="light",
        translation_key="light_level",
        device_class=SensorDeviceClass.ILLUMINANCE,
        native_unit_of_measurement=LIGHT_LUX,
        suggested_display_precision=0,
    )


class WyomingSatelliteOrientationSensor(_WyomingSatelliteDeviceSensorBase):
    """Entity to represent orientation sensor for satellite."""

    _attr_native_value = UNKNOWN
    entity_description = SensorEntityDescription(
        key="orientation", translation_key="orientation", icon="mdi:screen-rotation"
    )


class WyomingSatelliteBatteryLevelSensor(_WyomingSatelliteDeviceSensorBase):
    """Entity to represent battery level sensor for satellite."""

    entity_description = SensorEntityDescription(
        key="battery_level",
        translation_key="battery_level",
        device_class=SensorDeviceClass.BATTERY,
        native_unit_of_measurement=PERCENTAGE,
    )


class WyomingSatelliteBrowserPathSensor(_WyomingSatelliteDeviceSensorBase):
    """Entity to represent browser path sensor for satellite."""

    _attr_native_value = UNKNOWN
    entity_description = SensorEntityDescription(
        key="current_path", translation_key="current_path", icon="mdi:web"
    )


class WyomingSatelliteLastMotionSensor(_WyomingSatelliteDeviceSensorBase):
    """Entity to represent last motion for satellite."""

    _attr_native_value = UNKNOWN
    entity_description = SensorEntityDescription(
        key="last_motion",
        translation_key="last_motion",
        icon="mdi:motion-sensor",
        device_class=SensorDeviceClass.TIMESTAMP,
    )


class WyomingSatelliteAppVersionSensor(_WyomingSatelliteDeviceSensorBase):
    """Entity to represent app version sensor for satellite."""

    _listener_class = "capabilities_update"
    _attr_native_value = UNKNOWN
    entity_description = SensorEntityDescription(
        key="app_version",
        translation_key="app_version",
        icon="mdi:application",
        entity_category=EntityCategory.DIAGNOSTIC,
    )

    def _get_native_value(self, value: Any) -> Any:
        """Get the native value from the data."""
        return value if value is not None else UNKNOWN

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        """Return entity attributes."""
        return {
            "device_signature": self.get_capability("device_signature"),
            "android_version": self.get_capability("release"),
            "webview_version": self.get_capability("webview_version"),
            "has_battery": self.get_capability("has_battery"),
            "has_front_camera": self.get_capability("has_front_camera"),
            "has_light_sensor": self._device.has_light_sensor(),
            "sensors": self.get_sensor_names(),
        }

    def get_capability(self, capability: str) -> Any:
        """Get a specific capability from the device."""
        if self._device.capabilities is None:
            return UNKNOWN
        return self._device.capabilities.get(capability, UNKNOWN)

    def get_sensor_names(self) -> list[str] | None:
        """Get the names of all sensors."""
        if self._device.capabilities and (
            sensors := self._device.capabilities.get("sensors")
        ):
            return [sensor.get("name") for sensor in sensors]
        return None

class WyomingSatelliteInstalledAppsSensor(VASatelliteEntity, RestoreSensor):
    """Entity to represent installed apps list for satellite."""

    _listener_class = "capabilities_update"
    _attr_native_value = 0
    entity_description = SensorEntityDescription(
        key="installed_apps",
        translation_key="installed_apps",
        icon="mdi:apps",
        entity_category=EntityCategory.DIAGNOSTIC,
    )

    async def async_added_to_hass(self) -> None:
        """Call when entity about to be added to hass."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            self._attr_native_value = state.state
            self.async_write_ha_state()

        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                f"{DOMAIN}_{self._device.device_id}_capabilities_update",
                self._capabilities_updated,
            )
        )

    @callback
    def _capabilities_updated(self, data: dict[str, Any]) -> None:
        """Update entity when capabilities event is received."""
        if not self._device.capabilities:
            return

        installed_apps: list[dict] = self._device.capabilities.get("installed_apps", [])
        entry = self.hass.config_entries.async_get_entry(
            self.registry_entry.config_entry_id
        )
        ha_host = entry.data.get("host", "") if entry else ""
        # Use icon_server_port from capabilities if available, fallback to default 8080
        icon_server_port = self._device.capabilities.get("icon_server_port", 8080)

        apps_with_icon = [
            {
                **app,
                "icon_url": f"http://{ha_host}:{icon_server_port}/icon?pkg={app['package_name']}"
            }
            for app in installed_apps
        ]

        self._attr_native_value = len(installed_apps)
        self._attr_extra_state_attributes = {
            "installed_apps": apps_with_icon,
        }
        self.async_write_ha_state()

class WyomingSatelliteHttpServerSensor(VASatelliteEntity, RestoreSensor):
    """Sensor that exposes the HTTP server URL and all available endpoints."""

    _attr_native_value = "unavailable"
    _attr_icon = "mdi:web"
    entity_description = SensorEntityDescription(
        key="http_server",
        translation_key="http_server_url",
        icon="mdi:web",
        entity_category=EntityCategory.DIAGNOSTIC,
    )

    async def async_added_to_hass(self) -> None:
        """Call when entity about to be added to hass."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            self._attr_native_value = state.state
            self.async_write_ha_state()

        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                f"{DOMAIN}_{self._device.device_id}_capabilities_update",
                self._capabilities_updated,
            )
        )

    @callback
    def _capabilities_updated(self, data: dict[str, Any]) -> None:
        """Update when capabilities received."""
        if not self._device.capabilities:
            return

        entry = self.hass.config_entries.async_get_entry(
            self.registry_entry.config_entry_id
        )
        host = entry.data.get("host", "") if entry else ""
        port = self._device.capabilities.get("icon_server_port", 8080)
        base = f"http://{host}:{port}"

        http_enabled = self._device.custom_settings and self._device.custom_settings.get("http_server_enabled", False)

        self._attr_native_value = base if http_enabled else "disabled"
        self._attr_extra_state_attributes = {
            "base_url": base,
            "enabled": http_enabled,
            "endpoints": {
                "首頁 / 導航": {
                    "url": f"{base}/",
                    "description": "所有功能的入口頁面",
                },
                "系統設定": {
                    "url": f"{base}/settings",
                    "description": "螢幕、音量、語音、BLE 等所有設定",
                },
                "Wake Word 管理": {
                    "url": f"{base}/wakeword",
                    "description": "管理和上傳 wake word (.onnx) 檔案",
                },
                "附近藍牙裝置": {
                    "url": f"{base}/ble",
                    "description": "即時顯示附近 BLE 裝置列表（每 3 秒更新）",
                },
                "系統日誌": {
                    "url": f"{base}/logs",
                    "description": "即時查看 app 日誌，支援等級過濾和關鍵字搜尋",
                },
                "裝置狀態 (JSON)": {
                    "url": f"{base}/status",
                    "description": "裝置目前狀態的 JSON 格式資料",
                },
                "即時截圖": {
                    "url": f"{base}/snapshot",
                    "description": "取得前鏡頭即時截圖（需開啟 MJPEG 串流）",
                },
                "MJPEG 串流": {
                    "url": f"{base}/stream",
                    "description": "前鏡頭即時視訊串流，可直接在瀏覽器或 VLC 開啟",
                },
                "App Icon": {
                    "url": f"{base}/icon?pkg=com.example.app",
                    "description": "取得指定 app 的圖示，將 com.example.app 換成目標套件名稱",
                },
                "Wake Word 列表 (JSON)": {
                    "url": f"{base}/wakeword/list",
                    "description": "取得所有已安裝 wake word 的 JSON 資料",
                },
                "BLE 裝置列表 (JSON)": {
                    "url": f"{base}/ble/devices",
                    "description": "附近 BLE 裝置的 JSON 格式資料",
                },
            },
            "services": {
                "launch_app": {
                    "description": "透過語音或 automation 開啟 Android app",
                    "example": {
                        "service": "vaca.launch_app",
                        "data": {"package_name": "com.netflix.mediaclient"},
                    },
                },
            },
        }
        self.async_write_ha_state()

class WyomingSatelliteWebServerSensor(VASatelliteEntity, RestoreSensor):
    """Sensor that shows web server URL and available endpoints."""

    entity_description = SensorEntityDescription(
        key="web_server",
        translation_key="web_server",
        icon="mdi:web",
        entity_category=EntityCategory.DIAGNOSTIC,
    )
    _attr_native_value = "disabled"

    async def async_added_to_hass(self) -> None:
        """Call when entity about to be added to hass."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            self._attr_native_value = state.state
            self.async_write_ha_state()

        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                f"{DOMAIN}_{self._device.device_id}_settings_update",
                self._settings_updated,
            )
        )
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                f"{DOMAIN}_{self._device.device_id}_capabilities_update",
                self._capabilities_updated,
            )
        )

    def _build_attrs(self) -> None:
        """Build state and attributes from current device state."""
        entry = self.hass.config_entries.async_get_entry(
            self.registry_entry.config_entry_id
        )
        if not entry:
            return
        host = entry.data.get("host", "")
        port = 8080
        base = f"http://{host}:{port}"
        enabled = (self._device.custom_settings or {}).get("http_server_enabled", False)

        self._attr_native_value = base if enabled else "disabled"
        self._attr_extra_state_attributes = {
            "enabled": enabled,
            "base_url": base,
            "endpoints": {
                "首頁": f"{base}/",
                "系統設定": f"{base}/settings",
                "Wake Word 管理": f"{base}/wakeword",
                "藍牙裝置列表": f"{base}/ble",
                "系統日誌": f"{base}/logs",
                "即時截圖": f"{base}/snapshot",
                "MJPEG 串流": f"{base}/stream",
                "裝置狀態 API": f"{base}/status",
                "BLE 裝置 API": f"{base}/ble/devices",
                "Wake Word 列表 API": f"{base}/wakeword/list",
                "App Icon API": f"{base}/icon?pkg=com.example.app",
            } if enabled else {},
            "說明": "HTTP Server 開啟後，可在瀏覽器直接造訪以上連結管理裝置設定。" if enabled else "請在 VACA 裝置頁面開啟 HTTP Server 開關。",
        }
        self.async_write_ha_state()

    @callback
    def _settings_updated(self, data: dict[str, Any]) -> None:
        """Handle settings update."""
        self._build_attrs()

    @callback
    def _capabilities_updated(self, data: dict[str, Any]) -> None:
        """Handle capabilities update."""
        self._build_attrs()

class WyomingSatelliteRecentAppsSensor(VASatelliteEntity, RestoreSensor):
    """Sensor showing recently used apps list."""

    entity_description = SensorEntityDescription(
        key="recent_apps",
        translation_key="recent_apps",
        icon="mdi:history",
        entity_category=EntityCategory.DIAGNOSTIC,
    )
    _attr_native_value = "unavailable"
    _attr_extra_state_attributes: dict = {}

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        state = await self.async_get_last_state()
        if state is not None:
            self._attr_native_value = state.state
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                f"{DOMAIN}_{self._device.device_id}_capabilities_update",
                self._capabilities_updated,
            )
        )

    @callback
    def _capabilities_updated(self, data: dict[str, Any]) -> None:
        caps = data.get("capabilities", data)
        recent = caps.get("recent_apps", [])
        entry = self.hass.config_entries.async_get_entry(
            self.registry_entry.config_entry_id
        )
        host = entry.data.get("host", "") if entry else ""
        port = 8080

        apps_with_icons = []
        for app in recent:
            pkg = app.get("package_name", "")
            apps_with_icons.append({
                "package_name": pkg,
                "label": app.get("label", ""),
                "category": app.get("category", "other"),
                "last_used": app.get("last_used", 0),
                "use_count": app.get("use_count", 0),
                "icon_url": f"http://{host}:{port}/icon?pkg={pkg}" if host else "",
            })

        self._attr_native_value = str(len(apps_with_icons))
        self._attr_extra_state_attributes = {
            "recent_apps": apps_with_icons,
            "count": len(apps_with_icons),
            "has_usage_permission": caps.get("has_usage_stats_permission", False),
        }
        self.async_write_ha_state()


class WyomingSatelliteFrequentAppsSensor(VASatelliteEntity, RestoreSensor):
    """Sensor showing frequently used apps list."""

    entity_description = SensorEntityDescription(
        key="frequent_apps",
        translation_key="frequent_apps",
        icon="mdi:star-outline",
        entity_category=EntityCategory.DIAGNOSTIC,
    )
    _attr_native_value = "unavailable"
    _attr_extra_state_attributes: dict = {}

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        state = await self.async_get_last_state()
        if state is not None:
            self._attr_native_value = state.state
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                f"{DOMAIN}_{self._device.device_id}_capabilities_update",
                self._capabilities_updated,
            )
        )

    @callback
    def _capabilities_updated(self, data: dict[str, Any]) -> None:
        caps = data.get("capabilities", data)
        frequent = caps.get("frequent_apps", [])
        entry = self.hass.config_entries.async_get_entry(
            self.registry_entry.config_entry_id
        )
        host = entry.data.get("host", "") if entry else ""
        port = 8080

        apps_with_icons = []
        for app in frequent:
            pkg = app.get("package_name", "")
            apps_with_icons.append({
                "package_name": pkg,
                "label": app.get("label", ""),
                "category": app.get("category", "other"),
                "last_used": app.get("last_used", 0),
                "use_count": app.get("use_count", 0),
                "icon_url": f"http://{host}:{port}/icon?pkg={pkg}" if host else "",
            })

        self._attr_native_value = str(len(apps_with_icons))
        self._attr_extra_state_attributes = {
            "frequent_apps": apps_with_icons,
            "count": len(apps_with_icons),
        }
        self.async_write_ha_state()
