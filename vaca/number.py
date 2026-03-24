"""Number entities for Wyoming integration."""

from __future__ import annotations

from typing import TYPE_CHECKING, Final, Any

from homeassistant.components.number import NumberEntityDescription, RestoreNumber
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .const import DOMAIN
from .devices import VASatelliteDevice
from .entity import VASatelliteEntity

if TYPE_CHECKING:
    from homeassistant.components.wyoming import DomainDataItem

_MAX_MIC_GAIN: Final = 100
_MIN_SOUND_VOLUME: Final = 0
_MAX_SOUND_VOLUME: Final = 10


async def async_setup_entry(
    hass: HomeAssistant,
    config_entry: ConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up number entities."""
    item: DomainDataItem = hass.data[DOMAIN][config_entry.entry_id]
    device: VASatelliteDevice = item.device  # type: ignore[assignment]

    # Setup is only forwarded for satellites
    assert item.device is not None

    entities = []

    entities.extend(
        [
            WyomingSatelliteMicGainNumber(device),
            WyomingSatelliteNotificationVolumeNumber(device),
            WyomingSatelliteMusicVolumeNumber(device),
            WyomingSatelliteDuckingVolumeNumber(device),
            WyomingSatelliteScreenBrightnessNumber(device),
            WyomingSatelliteWakeWordThresholdNumber(device),
            WyomingSatelliteZoomLevelNumber(device),
        ]
    )

    if device.capabilities and device.capabilities.get("has_front_camera"):
        entities.append(WyomingSatelliteMotionDetectionSensitivityNumber(device))
    if (
        device.capabilities
        and device.capabilities.get("proximity_sensor_type") == "raw"
    ):
        entities.append(WyomingSatelliteRawProximityThresholdNumber(device))
    if device.supportBump():
        entities.append(WyomingSatelliteBumpDetectionSensitivityNumber(device))
    entities.append(WyomingSatelliteRecentAppsCountNumber(device))
    entities.append(WyomingSatelliteFrequentAppsCountNumber(device))
    entities.append(WyomingSatelliteBleRssiThresholdNumber(device))
    entities.append(WyomingSatelliteBleBatchIntervalNumber(device))
    entities.append(WyomingSatelliteMjpegFpsNumber(device))
    entities.append(WyomingSatelliteMjpegQualityNumber(device))
    async_add_entities(entities)


class BaseNumberEntity(VASatelliteEntity, RestoreNumber):
    """Base class for number entities."""

    async def async_added_to_hass(self) -> None:
        """When entity is added to Home Assistant."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            try:
                await self.async_set_native_value(float(state.state))
            except (ValueError, TypeError):
                pass

    async def async_set_native_value(self, value: float) -> None:
        """Set new value."""
        await self.update_number(value)

    async def update_number(self, value: float, send_to_device: bool = True) -> None:
        """Update number value."""
        self._attr_native_value = int(
            max(self._attr_native_min_value, min(self._attr_native_max_value, value))
        )
        self.async_write_ha_state()

        if send_to_device:
            self._device.set_custom_setting(self.entity_description.key, value)


class BaseFeedbackNumber(BaseNumberEntity):
    """Base class for numbers that receive feedback from device."""

    _listener_class = "settings_update"

    async def async_added_to_hass(self) -> None:
        """Call when entity about to be added to hass."""
        await super().async_added_to_hass()

        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                f"{DOMAIN}_{self._device.device_id}_{self._listener_class}",
                self.status_update,
            )
        )

    async def status_update(self, data: dict[str, Any]) -> None:
        """Handle status update."""
        if settings := data.get("settings"):
            if self.entity_description.key in settings:
                setting_value = settings[self.entity_description.key]
                await self.update_number(setting_value, send_to_device=False)


class WyomingSatelliteMicGainNumber(BaseNumberEntity):
    """Entity to represent mic gain amount."""

    entity_description = NumberEntityDescription(
        key="mic_gain",
        translation_key="mic_gain",
        icon="mdi:microphone-plus",
        entity_category=EntityCategory.CONFIG,
    )
    _attr_should_poll = False
    _attr_native_min_value = -10
    _attr_native_max_value = 10
    _attr_native_value = 0

    async def async_set_native_value(self, value: float) -> None:
        """Set new value."""
        mic_gain = int(max(-10, min(10, value)))
        self._attr_native_value = mic_gain
        self.async_write_ha_state()
        self._device.set_custom_setting(self.entity_description.key, mic_gain)


class WyomingSatelliteNotificationVolumeNumber(BaseFeedbackNumber):
    """Entity to represent notification volume multiplier."""

    entity_description = NumberEntityDescription(
        key="notification_volume",
        translation_key="notification_volume",
        icon="mdi:speaker-message",
        entity_category=EntityCategory.CONFIG,
    )
    _attr_should_poll = False
    _attr_native_min_value = _MIN_SOUND_VOLUME
    _attr_native_max_value = _MAX_SOUND_VOLUME
    _attr_native_step = 1
    _attr_native_value = 5

    async def async_added_to_hass(self) -> None:
        """When entity is added to Home Assistant."""
        await super().async_added_to_hass()
        self._attr_native_max_value = self._device.getMaxNotificationVolume()
        last_number_data = await self.async_get_last_number_data()
        if (last_number_data is not None) and (
            last_number_data.native_value is not None
        ):
            await self.async_set_native_value(last_number_data.native_value)

    async def async_set_native_value(self, value: float) -> None:
        """Set new value."""
        self._attr_native_max_value = self._device.getMaxNotificationVolume()
        await super().async_set_native_value(value)


class WyomingSatelliteMusicVolumeNumber(BaseFeedbackNumber):
    """Entity to represent media volume multiplier."""

    entity_description = NumberEntityDescription(
        key="music_volume",
        translation_key="music_volume",
        icon="mdi:music",
        entity_category=EntityCategory.CONFIG,
    )
    _attr_should_poll = False
    _attr_native_min_value = _MIN_SOUND_VOLUME
    _attr_native_max_value = _MAX_SOUND_VOLUME
    _attr_native_step = 1
    _attr_native_value = 5

    async def async_added_to_hass(self) -> None:
        """When entity is added to Home Assistant."""
        await super().async_added_to_hass()
        self._attr_native_max_value = self._device.getMaxMusicVolume()
        last_number_data = await self.async_get_last_number_data()
        if (last_number_data is not None) and (
            last_number_data.native_value is not None
        ):
            await self.async_set_native_value(last_number_data.native_value)

    async def async_set_native_value(self, value: float) -> None:
        """Set new value."""
        self._attr_native_max_value = self._device.getMaxMusicVolume()
        await super().async_set_native_value(value)


class WyomingSatelliteDuckingVolumeNumber(BaseNumberEntity):
    """Entity to represent media volume multiplier."""

    entity_description = NumberEntityDescription(
        key="ducking_volume",
        translation_key="ducking_volume",
        icon="mdi:volume-low",
        entity_category=EntityCategory.CONFIG,
    )
    _attr_should_poll = False
    _attr_native_min_value = _MIN_SOUND_VOLUME
    _attr_native_max_value = _MAX_SOUND_VOLUME
    _attr_native_step = 1
    _attr_native_value = 1

    async def async_added_to_hass(self) -> None:
        """When entity is added to Home Assistant."""
        await super().async_added_to_hass()
        self._attr_native_max_value = self._device.getMaxMusicVolume()
        last_number_data = await self.async_get_last_number_data()
        if (last_number_data is not None) and (
            last_number_data.native_value is not None
        ):
            await self.async_set_native_value(last_number_data.native_value)

    async def async_set_native_value(self, value: float) -> None:
        """Set new value."""
        self._attr_native_value = int(
            max(self._attr_native_min_value, min(self._attr_native_max_value, value))
        )
        self.async_write_ha_state()
        self._device.set_custom_setting(self.entity_description.key, value)


class WyomingSatelliteScreenBrightnessNumber(VASatelliteEntity, RestoreNumber):
    """Entity to represent auto gain amount."""

    entity_description = NumberEntityDescription(
        key="screen_brightness",
        translation_key="screen_brightness",
        icon="mdi:brightness-4",
        entity_category=EntityCategory.CONFIG,
    )
    _attr_should_poll = False
    _attr_native_min_value = 0
    _attr_native_max_value = 100
    _attr_native_value = 50

    async def async_added_to_hass(self) -> None:
        """When entity is added to Home Assistant."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            try:
                await self.async_set_native_value(float(state.state))
            except (ValueError, TypeError):
                pass

    async def async_set_native_value(self, value: float) -> None:
        """Set new value."""
        screen_brightness = int(max(0, min(100, value)))
        self._attr_native_value = screen_brightness
        self.async_write_ha_state()
        self._device.set_custom_setting(self.entity_description.key, screen_brightness)


class WyomingSatelliteWakeWordThresholdNumber(VASatelliteEntity, RestoreNumber):
    """Entity to represent wake word trigger threshold."""

    entity_description = NumberEntityDescription(
        key="wake_word_threshold",
        translation_key="wake_word_threshold",
        icon="mdi:account-voice",
        entity_category=EntityCategory.CONFIG,
    )
    _attr_should_poll = False
    _attr_native_min_value = 0
    _attr_native_max_value = 10
    _attr_native_value = 6

    async def async_added_to_hass(self) -> None:
        """When entity is added to Home Assistant."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            try:
                await self.async_set_native_value(float(state.state))
            except (ValueError, TypeError):
                pass

    async def async_set_native_value(self, value: float) -> None:
        """Set new value."""
        value = int(max(0, min(10, value)))
        self._attr_native_value = value
        self.async_write_ha_state()
        self._device.set_custom_setting(self.entity_description.key, value)


class WyomingSatelliteZoomLevelNumber(VASatelliteEntity, RestoreNumber):
    """Entity to represent zoom level."""

    entity_description = NumberEntityDescription(
        key="zoom_level",
        translation_key="zoom_level",
        icon="mdi:magnify-plus",
        entity_category=EntityCategory.CONFIG,
    )
    _attr_should_poll = False
    _attr_native_min_value = 0
    _attr_native_max_value = 2.5
    _attr_native_step = 0.1
    _attr_native_value = 0

    async def async_added_to_hass(self) -> None:
        """When entity is added to Home Assistant."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            try:
                await self.async_set_native_value(float(state.state))
            except (ValueError, TypeError):
                pass

    async def async_set_native_value(self, value: float) -> None:
        """Set new value."""
        value = max(0, min(self._attr_native_max_value, value))
        self._attr_native_value = value
        self.async_write_ha_state()
        self._device.set_custom_setting(
            self.entity_description.key, int(value * 100) + 60 if value > 0 else 0
        )


class WyomingSatelliteMotionDetectionSensitivityNumber(
    VASatelliteEntity, RestoreNumber
):
    """Entity to represent zoom level."""

    entity_description = NumberEntityDescription(
        key="motion_detection_sensitivity",
        translation_key="motion_detection_sensitivity",
        icon="mdi:tune-variant",
        entity_category=EntityCategory.CONFIG,
    )
    _attr_should_poll = False
    _attr_native_min_value = 0
    _attr_native_max_value = 100
    _attr_native_step = 1
    _attr_native_value = 70

    async def async_added_to_hass(self) -> None:
        """When entity is added to Home Assistant."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            try:
                await self.async_set_native_value(float(state.state))
            except (ValueError, TypeError):
                pass

    async def async_set_native_value(self, value: float) -> None:
        """Set new value."""
        value = max(0, min(self._attr_native_max_value, value))
        self._attr_native_value = value
        self.async_write_ha_state()
        # Sensitivity is sent as 0-50 scale
        self._device.set_custom_setting(self.entity_description.key, int(value / 2))


class WyomingSatelliteBumpDetectionSensitivityNumber(VASatelliteEntity, RestoreNumber):
    """Entity to represent bump sensitivity."""

    entity_description = NumberEntityDescription(
        key="bump_sensitivity",
        translation_key="bump_sensitivity",
        icon="mdi:tune-variant",
        entity_category=EntityCategory.CONFIG,
    )
    _attr_should_poll = False
    _attr_native_min_value = 0
    _attr_native_max_value = 10
    _attr_native_step = 1
    _attr_native_value = 8

    async def async_added_to_hass(self) -> None:
        """When entity is added to Home Assistant."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            try:
                await self.async_set_native_value(float(state.state))
            except (ValueError, TypeError):
                pass

    async def async_set_native_value(self, value: float) -> None:
        """Set new value."""
        value = max(0, min(self._attr_native_max_value, value))
        self._attr_native_value = value
        self.async_write_ha_state()
        # Sensitivity is sent as 1-10 scale
        self._device.set_custom_setting(self.entity_description.key, 11 - value)


class WyomingSatelliteRawProximityThresholdNumber(VASatelliteEntity, RestoreNumber):
    """Entity to represent raw proximity threshold."""

    entity_description = NumberEntityDescription(
        key="raw_proximity_threshold",
        translation_key="raw_proximity_threshold",
        icon="mdi:radar",
        entity_category=EntityCategory.CONFIG,
    )
    _attr_should_poll = False
    _attr_native_min_value = 0
    _attr_native_max_value = 1000
    _attr_native_value = 300

    async def async_added_to_hass(self) -> None:
        """When entity is added to Home Assistant."""
        await super().async_added_to_hass()

        state = await self.async_get_last_state()
        if state is not None:
            try:
                await self.async_set_native_value(float(state.state))
            except (ValueError, TypeError):
                pass

    async def async_set_native_value(self, value: float) -> None:
        """Set new value."""
        value = int(max(0, min(self._attr_native_max_value, value)))
        self._attr_native_value = value
        self.async_write_ha_state()
        self._device.set_custom_setting(self.entity_description.key, value)

class SettingsBackedNumberMixin:
    """Mixin for number entities that read initial value from device custom_settings."""

    _setting_key: str = ""
    _default_value: float = 0

    async def async_added_to_hass(self) -> None:
        """Initialize value from device settings or last state."""
        await super().async_added_to_hass()
        # Try to get current value from device settings
        settings = (self._device.custom_settings or {})
        if self._setting_key and self._setting_key in settings:
            val = settings[self._setting_key]
            self._attr_native_value = float(val)
            self.async_write_ha_state()
        elif self._attr_native_value == 0 and self._default_value != 0:
            self._attr_native_value = self._default_value
            self.async_write_ha_state()

        # Also subscribe to settings updates
        from homeassistant.helpers.dispatcher import async_dispatcher_connect
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                f"{DOMAIN}_{self._device.device_id}_settings_update",
                self._on_settings_update,
            )
        )

    def _on_settings_update(self, data: dict) -> None:
        """Handle settings update from device."""
        settings = data.get("settings", {})
        if self._setting_key and self._setting_key in settings:
            self._attr_native_value = float(settings[self._setting_key])
            self.async_write_ha_state()

    async def async_set_native_value(self, value: float) -> None:
        """Set value and send to device."""
        self._attr_native_value = value
        self.async_write_ha_state()
        self._device.set_custom_setting(
            self._setting_key or self.entity_description.key, value
        )


class WyomingSatelliteRecentAppsCountNumber(SettingsBackedNumberMixin, BaseNumberEntity):
    """Number of recent apps to track."""
    entity_description = NumberEntityDescription(
        key="recent_apps_count",
        translation_key="recent_apps_count",
        icon="mdi:history",
        native_min_value=1,
        native_max_value=50,
        native_step=1,
        entity_category=EntityCategory.CONFIG,
    )
    _setting_key = "recent_apps_count"
    _default_value = 10


class WyomingSatelliteFrequentAppsCountNumber(SettingsBackedNumberMixin, BaseNumberEntity):
    """Number of frequent apps to track."""
    entity_description = NumberEntityDescription(
        key="frequent_apps_count",
        translation_key="frequent_apps_count",
        icon="mdi:star-outline",
        native_min_value=1,
        native_max_value=50,
        native_step=1,
        entity_category=EntityCategory.CONFIG,
    )
    _setting_key = "frequent_apps_count"
    _default_value = 10

class WyomingSatelliteBleRssiThresholdNumber(SettingsBackedNumberMixin, BaseNumberEntity):
    """BLE RSSI threshold."""
    entity_description = NumberEntityDescription(
        key="ble_rssi_threshold",
        translation_key="ble_rssi_threshold",
        icon="mdi:signal",
        native_min_value=-100,
        native_max_value=-30,
        native_step=1,
        native_unit_of_measurement="dBm",
        entity_category=EntityCategory.CONFIG,
    )
    _setting_key = "ble_rssi_threshold"
    _default_value = -100


class WyomingSatelliteBleBatchIntervalNumber(SettingsBackedNumberMixin, BaseNumberEntity):
    """BLE batch interval."""
    entity_description = NumberEntityDescription(
        key="ble_batch_interval_ms",
        translation_key="ble_batch_interval_ms",
        icon="mdi:timer-outline",
        native_min_value=100,
        native_max_value=5000,
        native_step=100,
        native_unit_of_measurement="ms",
        entity_category=EntityCategory.CONFIG,
    )
    _setting_key = "ble_batch_interval_ms"
    _default_value = 500


class WyomingSatelliteMjpegFpsNumber(SettingsBackedNumberMixin, BaseNumberEntity):
    """MJPEG stream FPS."""
    entity_description = NumberEntityDescription(
        key="mjpeg_fps",
        translation_key="mjpeg_fps",
        icon="mdi:video",
        native_min_value=1,
        native_max_value=30,
        native_step=1,
        entity_category=EntityCategory.CONFIG,
    )
    _setting_key = "mjpeg_fps"
    _default_value = 10


class WyomingSatelliteMjpegQualityNumber(SettingsBackedNumberMixin, BaseNumberEntity):
    """MJPEG JPEG quality."""
    entity_description = NumberEntityDescription(
        key="mjpeg_quality",
        translation_key="mjpeg_quality",
        icon="mdi:image-size-select-large",
        native_min_value=10,
        native_max_value=100,
        native_step=5,
        entity_category=EntityCategory.CONFIG,
    )
    _setting_key = "mjpeg_quality"
    _default_value = 70
