package com.msp1974.vacompanion.utils

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.msp1974.vacompanion.sensors.BleGattCallback
import com.msp1974.vacompanion.sensors.BleGattManager
import com.msp1974.vacompanion.sensors.BleGattServiceInfo
import com.msp1974.vacompanion.sensors.BleScanner
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class VacaHttpServer(
    private val context: Context,
    private val port: Int = 8080
) {
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val config = APPConfig.getInstance(context)
    var mjpegFrameProvider: (() -> ByteArray?)? = null
    var bleScanner: BleScanner? = null

    // ── BLE GATT event buffer (for polling by GATT explorer UI) ──────────────
    private data class BleEvent(val type: String, val ts: Long, val payload: String)
    private val bleEvents = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<BleEvent>>()
    private val BLE_EVENT_MAX = 200

    private fun addBleEvent(address: String, type: String, payload: String) {
        val deque = bleEvents.getOrPut(address) { ArrayDeque() }
        synchronized(deque) {
            deque.addLast(BleEvent(type, System.currentTimeMillis(), payload))
            while (deque.size > BLE_EVENT_MAX) deque.removeFirst()
        }
    }

    private val gattCallback = object : BleGattCallback {
        override fun onConnected(address: String, mtu: Int, services: List<BleGattServiceInfo>) {
            val sb = StringBuilder("{\"mtu\":$mtu,\"services\":[")
            services.forEachIndexed { si, svc ->
                if (si > 0) sb.append(",")
                sb.append("{\"uuid\":\"${svc.uuid}\",\"chars\":[")
                svc.characteristics.forEachIndexed { ci, ch ->
                    if (ci > 0) sb.append(",")
                    sb.append("{\"uuid\":\"${ch.uuid}\",\"props\":${ch.properties}}")
                }
                sb.append("]}")
            }
            sb.append("]}")
            addBleEvent(address, "connected", sb.toString())
        }
        override fun onDisconnected(address: String) {
            addBleEvent(address, "disconnected", "{}")
        }
        override fun onReadResult(address: String, serviceUuid: String, characteristicUuid: String, value: ByteArray) {
            val hex = value.joinToString("") { "%02x".format(it) }
            val ascii = value.map { if (it in 0x20..0x7e) it.toInt().toChar() else '.' }.joinToString("")
            val safeAscii = ascii.replace("\\", "\\\\").replace("\"", "\\\"")
            addBleEvent(address, "read", "{\"service\":\"$serviceUuid\",\"char\":\"$characteristicUuid\",\"hex\":\"$hex\",\"ascii\":\"$safeAscii\",\"len\":${value.size}}")
        }
        override fun onWriteResult(address: String, serviceUuid: String, characteristicUuid: String, success: Boolean) {
            addBleEvent(address, "write", "{\"service\":\"$serviceUuid\",\"char\":\"$characteristicUuid\",\"success\":$success}")
        }
        override fun onNotification(address: String, serviceUuid: String, characteristicUuid: String, value: ByteArray) {
            val hex = value.joinToString("") { "%02x".format(it) }
            val ascii = value.map { if (it in 0x20..0x7e) it.toInt().toChar() else '.' }.joinToString("")
            val safeAscii = ascii.replace("\\", "\\\\").replace("\"", "\\\"")
            addBleEvent(address, "notification", "{\"service\":\"$serviceUuid\",\"char\":\"$characteristicUuid\",\"hex\":\"$hex\",\"ascii\":\"$safeAscii\",\"len\":${value.size}}")
        }
        override fun onError(address: String, operation: String, message: String) {
            val safeMsg = message.replace("\\", "\\\\").replace("\"", "\\\"")
            addBleEvent(address, "error", "{\"op\":\"$operation\",\"msg\":\"$safeMsg\"}")
        }
    }

    var bleGattManager: BleGattManager? = null
        set(value) {
            field?.removeCallback(gattCallback)  // clean up old manager
            field = value
            value?.addCallback(gattCallback)
        }

    // i18n JS served as /i18n.js - loaded by all pages via <script src>
    private val i18nJsContent: String = "var I18N={\"back\":{\"en\":\"← Back\",\"zh-TW\":\"← 返回\",\"zh-CN\":\"← 返回\",\"de\":\"← Zurück\",\"ja\":\"← 戻る\",\"ko\":\"← 뒤로\"},\"save\":{\"en\":\"Save Settings\",\"zh-TW\":\"儲存設定\",\"zh-CN\":\"保存设置\",\"de\":\"Einstellungen speichern\",\"ja\":\"設定を保存\",\"ko\":\"설정 저장\"},\"saved_ok\":{\"en\":\"✅ Saved!\",\"zh-TW\":\"✅ 儲存成功！\",\"zh-CN\":\"✅ 保存成功！\",\"de\":\"✅ Gespeichert!\",\"ja\":\"✅ 保存しました！\",\"ko\":\"✅ 저장됨!\"},\"save_fail\":{\"en\":\"❌ Save failed\",\"zh-TW\":\"❌ 儲存失敗\",\"zh-CN\":\"❌ 保存失败\",\"de\":\"❌ Speichern fehlgeschlagen\",\"ja\":\"❌ 保存失敗\",\"ko\":\"❌ 저장 실패\"},\"refresh\":{\"en\":\"Refresh\",\"zh-TW\":\"重新整理\",\"zh-CN\":\"刷新\",\"de\":\"Aktualisieren\",\"ja\":\"更新\",\"ko\":\"새로 고침\"},\"loading\":{\"en\":\"Loading...\",\"zh-TW\":\"載入中...\",\"zh-CN\":\"加载中...\",\"de\":\"Laden...\",\"ja\":\"読み込み中...\",\"ko\":\"로딩 중...\"},\"ready\":{\"en\":\"Ready\",\"zh-TW\":\"就緒\",\"zh-CN\":\"就绪\",\"de\":\"Bereit\",\"ja\":\"準備完了\",\"ko\":\"준비\"},\"index_title\":{\"en\":\"VACA Management\",\"zh-TW\":\"VACA 管理介面\",\"zh-CN\":\"VACA 管理界面\",\"de\":\"VACA Verwaltung\",\"ja\":\"VACA 管理\",\"ko\":\"VACA 관리\"},\"index_settings\":{\"en\":\"Settings\",\"zh-TW\":\"系統設定\",\"zh-CN\":\"系统设置\",\"de\":\"Einstellungen\",\"ja\":\"設定\",\"ko\":\"설정\"},\"index_wakeword\":{\"en\":\"Wake Word\",\"zh-TW\":\"Wake Word\",\"zh-CN\":\"Wake Word\",\"de\":\"Wake Word\",\"ja\":\"Wake Word\",\"ko\":\"Wake Word\"},\"index_ble\":{\"en\":\"Bluetooth\",\"zh-TW\":\"藍牙裝置\",\"zh-CN\":\"蓝牙设备\",\"de\":\"Bluetooth\",\"ja\":\"Bluetooth\",\"ko\":\"블루투스\"},\"index_logs\":{\"en\":\"Logs\",\"zh-TW\":\"系統日誌\",\"zh-CN\":\"系统日志\",\"de\":\"Protokoll\",\"ja\":\"ログ\",\"ko\":\"로그\"},\"index_snapshot\":{\"en\":\"Snapshot\",\"zh-TW\":\"即時截圖\",\"zh-CN\":\"实时截图\",\"de\":\"Schnappschuss\",\"ja\":\"スナップショット\",\"ko\":\"스냅샷\"},\"index_status\":{\"en\":\"Device Status\",\"zh-TW\":\"裝置狀態\",\"zh-CN\":\"设备状态\",\"de\":\"Gerätestatus\",\"ja\":\"デバイス状態\",\"ko\":\"기기 상태\"},\"section_screen\":{\"en\":\"Screen\",\"zh-TW\":\"螢幕\",\"zh-CN\":\"屏幕\",\"de\":\"Bildschirm\",\"ja\":\"画面\",\"ko\":\"화면\"},\"section_volume\":{\"en\":\"Volume\",\"zh-TW\":\"音量\",\"zh-CN\":\"音量\",\"de\":\"Lautstärke\",\"ja\":\"音量\",\"ko\":\"볼륨\"},\"section_voice\":{\"en\":\"Voice Assistant\",\"zh-TW\":\"語音助理\",\"zh-CN\":\"语音助手\",\"de\":\"Sprachassistent\",\"ja\":\"音声アシスタント\",\"ko\":\"음성 비서\"},\"section_sensors\":{\"en\":\"Sensors / Motion\",\"zh-TW\":\"感測器 / 動作\",\"zh-CN\":\"传感器 / 动作\",\"de\":\"Sensoren / Bewegung\",\"ja\":\"センサー / モーション\",\"ko\":\"센서 / 동작\"},\"section_http\":{\"en\":\"HTTP Services\",\"zh-TW\":\"HTTP 服務\",\"zh-CN\":\"HTTP 服务\",\"de\":\"HTTP-Dienste\",\"ja\":\"HTTP サービス\",\"ko\":\"HTTP 서비스\"},\"section_ble\":{\"en\":\"Bluetooth Proxy\",\"zh-TW\":\"藍牙代理\",\"zh-CN\":\"蓝牙代理\",\"de\":\"Bluetooth-Proxy\",\"ja\":\"Bluetoothプロキシ\",\"ko\":\"블루투스 프록시\"},\"section_apps\":{\"en\":\"App Tracking\",\"zh-TW\":\"App 追蹤\",\"zh-CN\":\"App 追踪\",\"de\":\"App-Tracking\",\"ja\":\"アプリ追跡\",\"ko\":\"앱 추적\"},\"screen_brightness\":{\"en\":\"Brightness\",\"zh-TW\":\"螢幕亮度\",\"zh-CN\":\"屏幕亮度\",\"de\":\"Helligkeit\",\"ja\":\"輝度\",\"ko\":\"밝기\"},\"screen_auto_brightness\":{\"en\":\"Auto Brightness\",\"zh-TW\":\"自動亮度\",\"zh-CN\":\"自动亮度\",\"de\":\"Automatische Helligkeit\",\"ja\":\"自動輝度\",\"ko\":\"자동 밝기\"},\"screen_always_on\":{\"en\":\"Always On\",\"zh-TW\":\"常亮\",\"zh-CN\":\"常亮\",\"de\":\"Immer an\",\"ja\":\"常時点灯\",\"ko\":\"항상 켜짐\"},\"dark_mode\":{\"en\":\"Dark Mode\",\"zh-TW\":\"暗色模式\",\"zh-CN\":\"深色模式\",\"de\":\"Dunkelmodus\",\"ja\":\"ダークモード\",\"ko\":\"다크 모드\"},\"screen_timeout\":{\"en\":\"Screen Timeout (sec)\",\"zh-TW\":\"螢幕逾時 (秒)\",\"zh-CN\":\"屏幕超时 (秒)\",\"de\":\"Bildschirm-Timeout (Sek)\",\"ja\":\"画面タイムアウト (秒)\",\"ko\":\"화면 시간 초과 (초)\"},\"screen_orientation\":{\"en\":\"Orientation\",\"zh-TW\":\"方向\",\"zh-CN\":\"方向\",\"de\":\"Ausrichtung\",\"ja\":\"向き\",\"ko\":\"방향\"},\"orient_auto\":{\"en\":\"Auto\",\"zh-TW\":\"自動\",\"zh-CN\":\"自动\",\"de\":\"Auto\",\"ja\":\"自動\",\"ko\":\"자동\"},\"orient_portrait\":{\"en\":\"Portrait\",\"zh-TW\":\"直向\",\"zh-CN\":\"竖屏\",\"de\":\"Hochformat\",\"ja\":\"縦向き\",\"ko\":\"세로\"},\"orient_landscape\":{\"en\":\"Landscape\",\"zh-TW\":\"橫向\",\"zh-CN\":\"横屏\",\"de\":\"Querformat\",\"ja\":\"横向き\",\"ko\":\"가로\"},\"screen_saver\":{\"en\":\"Screen Saver\",\"zh-TW\":\"螢幕保護\",\"zh-CN\":\"屏幕保护\",\"de\":\"Bildschirmschoner\",\"ja\":\"スクリーンセーバー\",\"ko\":\"화면 보호기\"},\"zoom_level\":{\"en\":\"Zoom Level\",\"zh-TW\":\"縮放等級\",\"zh-CN\":\"缩放级别\",\"de\":\"Zoomstufe\",\"ja\":\"ズームレベル\",\"ko\":\"확대 수준\"},\"mute\":{\"en\":\"Mute\",\"zh-TW\":\"靜音\",\"zh-CN\":\"静音\",\"de\":\"Stumm\",\"ja\":\"ミュート\",\"ko\":\"음소거\"},\"music_volume\":{\"en\":\"Media Volume\",\"zh-TW\":\"音樂音量\",\"zh-CN\":\"媒体音量\",\"de\":\"Medienlautstärke\",\"ja\":\"メディア音量\",\"ko\":\"미디어 볼륨\"},\"notification_volume\":{\"en\":\"Notification Volume\",\"zh-TW\":\"通知音量\",\"zh-CN\":\"通知音量\",\"de\":\"Benachrichtigungslautstärke\",\"ja\":\"通知音量\",\"ko\":\"알림 볼륨\"},\"ducking_volume\":{\"en\":\"Ducking Volume\",\"zh-TW\":\"閃避音量\",\"zh-CN\":\"闪避音量\",\"de\":\"Ducking-Lautstärke\",\"ja\":\"ダッキング音量\",\"ko\":\"덕킹 볼륨\"},\"mic_gain\":{\"en\":\"Mic Gain (dB)\",\"zh-TW\":\"麥克風增益 (dB)\",\"zh-CN\":\"麦克风增益 (dB)\",\"de\":\"Mikrofon-Verstärkung (dB)\",\"ja\":\"マイクゲイン (dB)\",\"ko\":\"마이크 게인 (dB)\"},\"continue_conversation\":{\"en\":\"Continue Conversation\",\"zh-TW\":\"繼續對話\",\"zh-CN\":\"继续对话\",\"de\":\"Gespräch fortführen\",\"ja\":\"会話を続ける\",\"ko\":\"대화 계속\"},\"wake_word_engine\":{\"en\":\"Wake Word Engine\",\"zh-TW\":\"Wake Word 引擎\",\"zh-CN\":\"唤醒词引擎\",\"de\":\"Wake-Word-Engine\",\"ja\":\"Wake Word エンジン\",\"ko\":\"Wake Word 엔진\"},\"wake_word_threshold\":{\"en\":\"Wake Word Threshold\",\"zh-TW\":\"Wake Word 閾值\",\"zh-CN\":\"唤醒词阈值\",\"de\":\"Wake-Word-Schwellenwert\",\"ja\":\"Wake Word しきい値\",\"ko\":\"Wake Word 임계값\"},\"screen_on_wake_word\":{\"en\":\"Wake Screen on Detection\",\"zh-TW\":\"偵測到時喚醒螢幕\",\"zh-CN\":\"检测到时唤醒屏幕\",\"de\":\"Bildschirm bei Erkennung einschalten\",\"ja\":\"検出時に画面を起動\",\"ko\":\"감지 시 화면 켜기\"},\"enable_motion_detection\":{\"en\":\"Motion Detection\",\"zh-TW\":\"動作偵測\",\"zh-CN\":\"动作检测\",\"de\":\"Bewegungserkennung\",\"ja\":\"モーション検出\",\"ko\":\"동작 감지\"},\"motion_sensitivity\":{\"en\":\"Motion Sensitivity\",\"zh-TW\":\"動作靈敏度\",\"zh-CN\":\"动作灵敏度\",\"de\":\"Bewegungsempfindlichkeit\",\"ja\":\"モーション感度\",\"ko\":\"동작 감도\"},\"screen_on_motion\":{\"en\":\"Wake Screen on Motion\",\"zh-TW\":\"動作喚醒螢幕\",\"zh-CN\":\"动作唤醒屏幕\",\"de\":\"Bildschirm bei Bewegung\",\"ja\":\"動作で画面を起動\",\"ko\":\"동작 시 화면 켜기\"},\"screen_on_proximity\":{\"en\":\"Wake Screen on Proximity\",\"zh-TW\":\"接近感應喚醒\",\"zh-CN\":\"接近唤醒\",\"de\":\"Bildschirm bei Annäherung\",\"ja\":\"近接で画面を起動\",\"ko\":\"근접 시 화면 켜기\"},\"screen_on_bump\":{\"en\":\"Wake Screen on Bump\",\"zh-TW\":\"撞擊喚醒\",\"zh-CN\":\"碰撞唤醒\",\"de\":\"Bildschirm bei Erschütterung\",\"ja\":\"衝撃で画面を起動\",\"ko\":\"충격 시 화면 켜기\"},\"do_not_disturb\":{\"en\":\"Do Not Disturb\",\"zh-TW\":\"勿擾模式\",\"zh-CN\":\"勿扰模式\",\"de\":\"Nicht stören\",\"ja\":\"マナーモード\",\"ko\":\"방해 금지\"},\"http_server\":{\"en\":\"HTTP Server\",\"zh-TW\":\"HTTP Server\",\"zh-CN\":\"HTTP 服务器\",\"de\":\"HTTP-Server\",\"ja\":\"HTTPサーバー\",\"ko\":\"HTTP 서버\"},\"icon_server\":{\"en\":\"Icon Server\",\"zh-TW\":\"Icon Server\",\"zh-CN\":\"图标服务器\",\"de\":\"Icon-Server\",\"ja\":\"アイコンサーバー\",\"ko\":\"아이콘 서버\"},\"mjpeg_stream\":{\"en\":\"MJPEG Stream\",\"zh-TW\":\"MJPEG 串流\",\"zh-CN\":\"MJPEG 串流\",\"de\":\"MJPEG-Stream\",\"ja\":\"MJPEG ストリーム\",\"ko\":\"MJPEG 스트림\"},\"mjpeg_fps\":{\"en\":\"Stream FPS (1-30)\",\"zh-TW\":\"串流 FPS (1-30)\",\"zh-CN\":\"串流 FPS (1-30)\",\"de\":\"Stream FPS (1-30)\",\"ja\":\"ストリーム FPS (1-30)\",\"ko\":\"스트림 FPS (1-30)\"},\"mjpeg_quality\":{\"en\":\"JPEG Quality (10-100)\",\"zh-TW\":\"JPEG 畫質 (10-100)\",\"zh-CN\":\"JPEG 画质 (10-100)\",\"de\":\"JPEG-Qualität (10-100)\",\"ja\":\"JPEG 品質 (10-100)\",\"ko\":\"JPEG 품질 (10-100)\"},\"ble_proxy\":{\"en\":\"Bluetooth Proxy\",\"zh-TW\":\"藍牙代理\",\"zh-CN\":\"蓝牙代理\",\"de\":\"Bluetooth-Proxy\",\"ja\":\"Bluetoothプロキシ\",\"ko\":\"블루투스 프록시\"},\"ble_scan_mode\":{\"en\":\"Scan Mode\",\"zh-TW\":\"掃描模式\",\"zh-CN\":\"扫描模式\",\"de\":\"Scanmodus\",\"ja\":\"スキャンモード\",\"ko\":\"스캔 모드\"},\"ble_scan_low_power\":{\"en\":\"Low Power\",\"zh-TW\":\"低功耗\",\"zh-CN\":\"低功耗\",\"de\":\"Energiesparmodus\",\"ja\":\"低電力\",\"ko\":\"저전력\"},\"ble_scan_balanced\":{\"en\":\"Balanced\",\"zh-TW\":\"平衡\",\"zh-CN\":\"平衡\",\"de\":\"Ausgewogen\",\"ja\":\"バランス\",\"ko\":\"균형\"},\"ble_scan_low_latency\":{\"en\":\"Low Latency\",\"zh-TW\":\"低延遲\",\"zh-CN\":\"低延迟\",\"de\":\"Niedrige Latenz\",\"ja\":\"低レイテンシ\",\"ko\":\"저지연\"},\"ble_rssi\":{\"en\":\"RSSI Threshold (dBm)\",\"zh-TW\":\"RSSI 門檻 (dBm)\",\"zh-CN\":\"RSSI 阈值 (dBm)\",\"de\":\"RSSI-Schwellenwert (dBm)\",\"ja\":\"RSSI しきい値 (dBm)\",\"ko\":\"RSSI 임계값 (dBm)\"},\"ble_batch\":{\"en\":\"Batch Interval (ms)\",\"zh-TW\":\"批次間隔 (ms)\",\"zh-CN\":\"批量间隔 (ms)\",\"de\":\"Batch-Intervall (ms)\",\"ja\":\"バッチ間隔 (ms)\",\"ko\":\"배치 간격 (ms)\"},\"ble_uuid_filter\":{\"en\":\"UUID Filter (comma separated)\",\"zh-TW\":\"UUID 過濾 (逗號分隔)\",\"zh-CN\":\"UUID 过滤 (逗号分隔)\",\"de\":\"UUID-Filter (kommagetrennt)\",\"ja\":\"UUIDフィルター (カンマ区切り)\",\"ko\":\"UUID 필터 (쉼표 구분)\"},\"ble_uuid_placeholder\":{\"en\":\"Empty = all devices\",\"zh-TW\":\"留空 = 全部\",\"zh-CN\":\"留空 = 全部\",\"de\":\"Leer = alle Geräte\",\"ja\":\"空 = すべて\",\"ko\":\"비우면 = 모두\"},\"ble_max_connections\":{\"en\":\"Max Connections (1-10)\",\"zh-TW\":\"最大連線數 (1-10)\",\"zh-CN\":\"最大连接数 (1-10)\",\"de\":\"Max. Verbindungen (1-10)\",\"ja\":\"最大接続数 (1-10)\",\"ko\":\"최대 연결 수 (1-10)\"},\"recent_apps_enabled\":{\"en\":\"Enable app usage tracking\",\"zh-TW\":\"啟用 App 使用追蹤\",\"zh-CN\":\"启用 App 使用追踪\",\"de\":\"App-Nutzungsverfolgung aktivieren\",\"ja\":\"アプリ使用状況追跡\",\"ko\":\"앱 사용 추적 활성화\"},\"recent_apps_note\":{\"en\":\"Requires Usage Access permission\",\"zh-TW\":\"需要開啟使用記錄存取權限\",\"zh-CN\":\"需要开启使用情况访问权限\",\"de\":\"Erfordert Nutzungszugriffsberechtigung\",\"ja\":\"使用状況アクセス権限が必要\",\"ko\":\"사용 정보 접근 권한 필요\"},\"recent_apps_count\":{\"en\":\"Recent apps count (1-50)\",\"zh-TW\":\"最近使用 App 數量 (1-50)\",\"zh-CN\":\"最近使用 App 数量 (1-50)\",\"de\":\"Anzahl zuletzt genutzter Apps\",\"ja\":\"最近使用したアプリ数\",\"ko\":\"최근 앱 수\"},\"frequent_apps_count\":{\"en\":\"Frequent apps count (1-50)\",\"zh-TW\":\"常用 App 數量 (1-50)\",\"zh-CN\":\"常用 App 数量 (1-50)\",\"de\":\"Anzahl häufig genutzter Apps\",\"ja\":\"よく使うアプリ数\",\"ko\":\"자주 쓰는 앱 수\"},\"open_usage_settings\":{\"en\":\"Open Usage Access Settings\",\"zh-TW\":\"開啟使用記錄存取設定\",\"zh-CN\":\"开启使用情况访问设置\",\"de\":\"Nutzungszugriff öffnen\",\"ja\":\"使用状況アクセスを開く\",\"ko\":\"사용 정보 접근 설정\"},\"ble_nearby\":{\"en\":\"Nearby Bluetooth Devices\",\"zh-TW\":\"附近藍牙裝置\",\"zh-CN\":\"附近蓝牙设备\",\"de\":\"Bluetooth-Geräte in der Nähe\",\"ja\":\"近くのBluetoothデバイス\",\"ko\":\"근처 블루투스 기기\"},\"ble_scanning\":{\"en\":\"● Scanning\",\"zh-TW\":\"● 掃描中\",\"zh-CN\":\"● 扫描中\",\"de\":\"● Wird gescannt\",\"ja\":\"● スキャン中\",\"ko\":\"● 스캔 중\"},\"ble_not_scanning\":{\"en\":\"○ Proxy not enabled\",\"zh-TW\":\"○ 藍牙代理未開啟\",\"zh-CN\":\"○ 蓝牙代理未开启\",\"de\":\"○ Proxy nicht aktiviert\",\"ja\":\"○ プロキシ未有効\",\"ko\":\"○ 프록시 비활성화\"},\"ble_no_devices\":{\"en\":\"No devices detected\",\"zh-TW\":\"沒有偵測到裝置\",\"zh-CN\":\"未检测到设备\",\"de\":\"Keine Geräte erkannt\",\"ja\":\"デバイスなし\",\"ko\":\"기기 없음\"},\"col_name\":{\"en\":\"Name\",\"zh-TW\":\"名稱\",\"zh-CN\":\"名称\",\"de\":\"Name\",\"ja\":\"名前\",\"ko\":\"이름\"},\"col_mac\":{\"en\":\"MAC\",\"zh-TW\":\"MAC\",\"zh-CN\":\"MAC\",\"de\":\"MAC\",\"ja\":\"MAC\",\"ko\":\"MAC\"},\"col_rssi_h\":{\"en\":\"RSSI\",\"zh-TW\":\"RSSI\",\"zh-CN\":\"RSSI\",\"de\":\"RSSI\",\"ja\":\"RSSI\",\"ko\":\"RSSI\"},\"col_services\":{\"en\":\"Services\",\"zh-TW\":\"服務\",\"zh-CN\":\"服务\",\"de\":\"Dienste\",\"ja\":\"サービス\",\"ko\":\"서비스\"},\"col_unknown\":{\"en\":\"(unknown)\",\"zh-TW\":\"(未知)\",\"zh-CN\":\"(未知)\",\"de\":\"(unbekannt)\",\"ja\":\"(不明)\",\"ko\":\"(알 수 없음)\"},\"logs_title\":{\"en\":\"System Logs\",\"zh-TW\":\"系統日誌\",\"zh-CN\":\"系统日志\",\"de\":\"Systemprotokoll\",\"ja\":\"システムログ\",\"ko\":\"시스템 로그\"},\"logs_all\":{\"en\":\"All\",\"zh-TW\":\"全部\",\"zh-CN\":\"全部\",\"de\":\"Alle\",\"ja\":\"すべて\",\"ko\":\"모두\"},\"logs_filter_ph\":{\"en\":\"Filter keyword...\",\"zh-TW\":\"過濾關鍵字...\",\"zh-CN\":\"过滤关键字...\",\"de\":\"Stichwort...\",\"ja\":\"キーワード...\",\"ko\":\"키워드...\"},\"logs_auto\":{\"en\":\"Auto\",\"zh-TW\":\"自動\",\"zh-CN\":\"自动\",\"de\":\"Auto\",\"ja\":\"自動\",\"ko\":\"자동\"},\"logs_stop\":{\"en\":\"Stop\",\"zh-TW\":\"停止\",\"zh-CN\":\"停止\",\"de\":\"Stopp\",\"ja\":\"停止\",\"ko\":\"정지\"},\"logs_clear\":{\"en\":\"Clear\",\"zh-TW\":\"清除\",\"zh-CN\":\"清除\",\"de\":\"Leeren\",\"ja\":\"クリア\",\"ko\":\"지우기\"},\"logs_scroll\":{\"en\":\"Auto scroll\",\"zh-TW\":\"自動捲動\",\"zh-CN\":\"自动滚动\",\"de\":\"Auto-Scroll\",\"ja\":\"自動スクロール\",\"ko\":\"자동 스크롤\"},\"logs_fail\":{\"en\":\"Load failed: \",\"zh-TW\":\"載入失敗：\",\"zh-CN\":\"加载失败：\",\"de\":\"Fehler: \",\"ja\":\"失敗：\",\"ko\":\"실패: \"},\"ww_title\":{\"en\":\"Wake Word Manager\",\"zh-TW\":\"Wake Word 管理\",\"zh-CN\":\"唤醒词管理\",\"de\":\"Wake-Word-Verwaltung\",\"ja\":\"Wake Word 管理\",\"ko\":\"Wake Word 관리\"},\"ww_type\":{\"en\":\"Type\",\"zh-TW\":\"類型\",\"zh-CN\":\"类型\",\"de\":\"Typ\",\"ja\":\"タイプ\",\"ko\":\"유형\"},\"ww_action\":{\"en\":\"Action\",\"zh-TW\":\"操作\",\"zh-CN\":\"操作\",\"de\":\"Aktion\",\"ja\":\"操作\",\"ko\":\"작업\"},\"ww_builtin\":{\"en\":\"Built-in\",\"zh-TW\":\"內建\",\"zh-CN\":\"内置\",\"de\":\"Integriert\",\"ja\":\"組み込み\",\"ko\":\"내장\"},\"ww_custom\":{\"en\":\"Custom\",\"zh-TW\":\"自訂\",\"zh-CN\":\"自定义\",\"de\":\"Benutzerdefiniert\",\"ja\":\"カスタム\",\"ko\":\"사용자 정의\"},\"ww_active\":{\"en\":\"Active\",\"zh-TW\":\"使用中\",\"zh-CN\":\"使用中\",\"de\":\"Aktiv\",\"ja\":\"使用中\",\"ko\":\"활성\"},\"ww_activate\":{\"en\":\"Activate\",\"zh-TW\":\"啟用\",\"zh-CN\":\"启用\",\"de\":\"Aktivieren\",\"ja\":\"有効にする\",\"ko\":\"활성화\"},\"ww_delete\":{\"en\":\"Delete\",\"zh-TW\":\"刪除\",\"zh-CN\":\"删除\",\"de\":\"Löschen\",\"ja\":\"削除\",\"ko\":\"삭제\"},\"ww_del_confirm\":{\"en\":\"Confirm delete?\",\"zh-TW\":\"確定刪除？\",\"zh-CN\":\"确定删除？\",\"de\":\"Wirklich löschen?\",\"ja\":\"削除しますか？\",\"ko\":\"삭제하시겠습니까?\"},\"ww_upload_title\":{\"en\":\"Upload Wake Word (.onnx)\",\"zh-TW\":\"上傳 Wake Word (.onnx)\",\"zh-CN\":\"上传唤醒词 (.onnx)\",\"de\":\"Wake Word hochladen (.onnx)\",\"ja\":\"Wake Wordをアップロード (.onnx)\",\"ko\":\"Wake Word 업로드 (.onnx)\"},\"ww_drag\":{\"en\":\"Drag .onnx here, or\",\"zh-TW\":\"拖拉 .onnx 到此處，或\",\"zh-CN\":\"拖拽 .onnx 到此处，或\",\"de\":\".onnx hier ablegen, oder\",\"ja\":\".onnxをここにドラッグ、または\",\"ko\":\".onnx 파일을 여기에 드래그하거나\"},\"ww_only_onnx\":{\"en\":\"Only .onnx supported\",\"zh-TW\":\"只支援 .onnx\",\"zh-CN\":\"仅支持 .onnx\",\"de\":\"Nur .onnx\",\"ja\":\".onnxのみ\",\"ko\":\".onnx만 지원\"},\"ww_uploading\":{\"en\":\"Uploading...\",\"zh-TW\":\"上傳中...\",\"zh-CN\":\"上传中...\",\"de\":\"Hochladen...\",\"ja\":\"アップロード中...\",\"ko\":\"업로드 중...\"},\"ww_upload_ok\":{\"en\":\"Uploaded! Reloading...\",\"zh-TW\":\"上傳成功！重新載入...\",\"zh-CN\":\"上传成功！重新加载...\",\"de\":\"Hochgeladen! Neu laden...\",\"ja\":\"完了！再読み込み中...\",\"ko\":\"완료! 다시 로드 중...\"},\"ww_upload_fail\":{\"en\":\"Upload failed\",\"zh-TW\":\"上傳失敗\",\"zh-CN\":\"上传失败\",\"de\":\"Fehler\",\"ja\":\"失敗\",\"ko\":\"실패\"},\"ww_del_fail\":{\"en\":\"Delete failed\",\"zh-TW\":\"刪除失敗\",\"zh-CN\":\"删除失败\",\"de\":\"Löschen fehlgeschlagen\",\"ja\":\"削除失敗\",\"ko\":\"삭제 실패\"},\"ww_act_fail\":{\"en\":\"Activate failed\",\"zh-TW\":\"啟用失敗\",\"zh-CN\":\"启用失败\",\"de\":\"Aktivierung fehlgeschlagen\",\"ja\":\"有効化失敗\",\"ko\":\"활성화 실패\"},\"logs_lines\":{\"en\":\"lines\",\"zh-TW\":\"行\",\"zh-CN\":\"行\",\"de\":\"Zeilen\",\"ja\":\"行\",\"ko\":\"줄\"}};function t(k){var l=navigator.language||\"en\",s=l.substring(0,2),d=I18N[k];if(!d)return k;return d[l]||d[s+\"-TW\"]||d[s+\"-CN\"]||d[s]||d[\"en\"]||k;}"

    // Common HTML head with i18n
    private fun htmlHead(title: String, extraCss: String = "") = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>$title</title>")
        append("<style>")
        append("*{box-sizing:border-box}body{font-family:sans-serif;max-width:800px;margin:0 auto;padding:1rem;background:#f5f5f5}")
        append("h1{font-size:1.3rem}h2{font-size:1rem;color:#555;margin:1.5rem 0 .5rem;border-bottom:1px solid #ddd;padding-bottom:.3rem}")
        append(".card{background:#fff;border-radius:12px;padding:1rem 1.2rem;margin-bottom:1rem;border:1px solid #eee}")
        append(".row{display:flex;align-items:center;justify-content:space-between;padding:.5rem 0;border-bottom:1px solid #f0f0f0}")
        append(".row:last-child{border-bottom:none}.row label{font-size:14px;color:#333;flex:1}")
        append(".toggle{position:relative;width:44px;height:24px;flex-shrink:0}")
        append(".toggle input{opacity:0;width:0;height:0}")
        append(".slider{position:absolute;cursor:pointer;inset:0;background:#ccc;border-radius:24px;transition:.3s}")
        append(".slider:before{content:\"\";position:absolute;height:18px;width:18px;left:3px;bottom:3px;background:#fff;border-radius:50%;transition:.3s}")
        append("input:checked+.slider{background:#4caf50}input:checked+.slider:before{transform:translateX(20px)}")
        append("input[type=range]{width:140px}input[type=number],input[type=text]{padding:4px 6px;border:1px solid #ccc;border-radius:6px}")
        append("input[type=number]{width:90px}input[type=text]{width:200px}")
        append("select{padding:4px 6px;border:1px solid #ccc;border-radius:6px}")
        append(".val{font-size:12px;color:#888;margin-left:6px;min-width:30px;text-align:right}")
        append("a.back{display:inline-block;margin-bottom:1rem;color:#555;text-decoration:none;font-size:13px}")
        append("button{background:#4caf50;color:#fff;border:none;border-radius:8px;padding:8px 16px;cursor:pointer;font-size:13px}")
        append("button.danger{background:#f44336}button.secondary{background:#555}")
        if (extraCss.isNotEmpty()) append(extraCss)
        append("</style>")
        // Load i18n BEFORE closing head so it's available synchronously when body scripts run
        append("<script src=\"/i18n.js\"></script>")
        append("</head><body>")
    }

    fun start() {
        if (running) return
        running = true
        thread(name = "VacaHttpServer") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true  // Allow immediate reuse after stop
                ss.bind(java.net.InetSocketAddress(port))
                serverSocket = ss
                Timber.d("VACA HTTP server started on port $port")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    thread { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running) Timber.e("VACA HTTP server error: $e")
                running = false  // Reset so start() can be retried
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            val method = requestLine.substringBefore(" ")
            val rawPath = requestLine.substringAfter(" ").substringBefore(" ")
            val path = rawPath.substringBefore("?")
            val query = rawPath.substringAfter("?", "")
            fun qp(key: String): String {
                val raw = query.split("&").firstOrNull { it.startsWith("$key=") }?.substringAfter("=") ?: return ""
                return try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
            }

            // IMPORTANT: drain all HTTP headers before responding
            // Without this, the next request's data leaks into this connection
            val headers = mutableMapOf<String, String>()
            var headerLine = reader.readLine()
            while (headerLine != null && headerLine.isNotEmpty()) {
                val colon = headerLine.indexOf(": ")
                if (colon > 0) headers[headerLine.substring(0, colon).lowercase()] = headerLine.substring(colon + 2)
                headerLine = reader.readLine()
            }

            when {
                path == "/icon"                              -> handleIcon(socket, qp("pkg"))
                path == "/stream"                           -> handleMjpegStream(socket)
                path == "/snapshot"                         -> handleSnapshot(socket)
                path == "/status"                           -> handleStatus(socket)
                path == "/settings" && method == "GET"      -> handleSettingsPage(socket)
                path == "/settings/set" && method == "POST" -> handleSettingsSet(socket, reader, headers)
                path == "/wakeword" && method == "GET"      -> handleWakeWordPage(socket)
                path == "/wakeword/list"                    -> handleWakeWordList(socket)
                path == "/wakeword/upload" && method == "POST" -> handleWakeWordUpload(socket, reader, headers)
                path == "/wakeword/delete"                  -> handleWakeWordDelete(socket, qp("name"))
                path == "/wakeword/activate"                -> handleWakeWordActivate(socket, qp("name"))
                path == "/ble/devices"                      -> handleBleDevices(socket)
                path == "/ble/connect"   && method == "POST" -> handleBleConnect(socket, qp("address"))
                path == "/ble/disconnect" && method == "POST" -> handleBleDisconnect(socket, qp("address"))
                path == "/ble/gatt"      && method == "GET"  -> handleBleGattPage(socket, qp("address"))
                path == "/ble/read"                          -> handleBleRead(socket, qp("address"), qp("service"), qp("char"))
                path == "/ble/write"     && method == "POST" -> handleBleWrite(socket, reader, headers, qp("address"), qp("service"), qp("char"))
                path == "/ble/notify"    && method == "POST" -> handleBleNotify(socket, qp("address"), qp("service"), qp("char"), qp("enable"))
                path == "/ble/events"                        -> handleBleEvents(socket, qp("address"), qp("since"))
                path == "/ble" && method == "GET"           -> handleBlePage(socket)
                path == "/logs" && method == "GET"          -> handleLogsPage(socket)
                path == "/logs/data"                        -> handleLogsData(socket, qp("level"), qp("filter"), qp("lines"))
                path == "/i18n.js"                              -> handleI18nJs(socket)
                path == "/"                                 -> handleIndex(socket)
                else                                        -> sendError(socket, 404, "Not Found")
            }
        } catch (e: Exception) {
            Timber.e("Error handling request: $e")
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    // ── Index ────────────────────────────────────────────────────────────────

    private fun handleIndex(socket: Socket) {
        val sb = StringBuilder()
        sb.append(htmlHead("VACA", ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px}.card-link{text-decoration:none;color:#333;background:#fff;border-radius:12px;padding:1.2rem;text-align:center;border:1px solid #eee;transition:box-shadow .2s}.card-link:hover{box-shadow:0 4px 12px rgba(0,0,0,.1)}.icon{font-size:2rem;margin-bottom:.5rem}.lbl{font-size:14px;font-weight:500}"))
        sb.append("<h1 id=\"t_title\"></h1><div class=\"grid\">")
        sb.append("<a class=\"card-link\" href=\"/settings\"><div class=\"icon\">&#x1F6E0;</div><div class=\"lbl\" id=\"t_settings\"></div></a>")
        sb.append("<a class=\"card-link\" href=\"/wakeword\"><div class=\"icon\">&#x1F3A4;</div><div class=\"lbl\" id=\"t_ww\"></div></a>")
        sb.append("<a class=\"card-link\" href=\"/ble\"><div class=\"icon\">&#x1F4E1;</div><div class=\"lbl\" id=\"t_ble\"></div></a>")
        sb.append("<a class=\"card-link\" href=\"/logs\"><div class=\"icon\">&#x1F4CB;</div><div class=\"lbl\" id=\"t_logs\"></div></a>")
        sb.append("<a class=\"card-link\" href=\"/snapshot\"><div class=\"icon\">&#x1F4F7;</div><div class=\"lbl\" id=\"t_snap\"></div></a>")
        sb.append("<a class=\"card-link\" href=\"/status\"><div class=\"icon\">&#x1F4CA;</div><div class=\"lbl\" id=\"t_status\"></div></a>")
        sb.append("</div><script>")
        sb.append("document.getElementById('t_title').textContent=t('index_title');")
        sb.append("document.getElementById('t_settings').textContent=t('index_settings');")
        sb.append("document.getElementById('t_ww').textContent=t('index_wakeword');")
        sb.append("document.getElementById('t_ble').textContent=t('index_ble');")
        sb.append("document.getElementById('t_logs').textContent=t('index_logs');")
        sb.append("document.getElementById('t_snap').textContent=t('index_snapshot');")
        sb.append("document.getElementById('t_status').textContent=t('index_status');")
        sb.append("document.title=t('index_title');")
        sb.append("</script></body></html>")
        sendString(socket, sb.toString(), "text/html; charset=utf-8")
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    private fun checked(v: Boolean) = if (v) "checked" else ""
    private fun sel(a: Int, b: Int) = if (a == b) "selected" else ""
    private fun selS(a: String, b: String) = if (a == b) "selected" else ""

    private fun handleSettingsPage(socket: Socket) {
        val sb = StringBuilder()
        sb.append(htmlHead("VACA Settings"))
        sb.append("<a class=\"back\" href=\"/\" id=\"t_back\"></a>")
        sb.append("<h1 id=\"t_title\"></h1><form id=\"frm\">")

        // Screen section
        sb.append("<div class=\"card\"><h2 id=\"s_screen\"></h2>")
        sb.append(settingsRow("screen_brightness", "screen_brightness", """<input type="range" name="screen_brightness" min="1" max="100" value="${(config.screenBrightness*100).toInt()}"><span class="val" id="v_sb">${(config.screenBrightness*100).toInt()}</span>"""))
        sb.append(settingsToggle("screen_auto_brightness", "screen_auto_brightness", config.screenAutoBrightness))
        sb.append(settingsToggle("screen_always_on", "screen_always_on", config.screenAlwaysOn))
        sb.append(settingsToggle("dark_mode", "dark_mode", config.darkMode))
        sb.append(settingsRow("screen_timeout", "screen_timeout", """<input type="number" name="screen_timeout" min="5" max="3600" value="${config.screenTimeout/1000}">"""))
        sb.append("<div class=\"row\"><label id=\"l_orient\"></label><select name=\"screen_orientation_mode\">")
        sb.append("<option value=\"auto\" ${selS(config.screenOrientationMode,"auto")} id=\"o_auto\"></option>")
        sb.append("<option value=\"portrait\" ${selS(config.screenOrientationMode,"portrait")} id=\"o_portrait\"></option>")
        sb.append("<option value=\"landscape\" ${selS(config.screenOrientationMode,"landscape")} id=\"o_landscape\"></option>")
        sb.append("</select></div>")
        sb.append(settingsToggle("screen_saver", "screen_saver", config.screenSaver))
        sb.append(settingsRow("zoom_level", "zoom_level", """<input type="number" name="zoom_level" min="0" max="10" value="${config.zoomLevel}">"""))
        sb.append("</div>")

        // Volume section
        sb.append("<div class=\"card\"><h2 id=\"s_volume\"></h2>")
        sb.append(settingsToggle("mute", "mute", config.isMuted))
        sb.append(settingsRow("music_volume", "music_volume", """<input type="range" name="music_volume" min="0" max="15" value="${config.musicVolume}"><span class="val" id="v_mv">${config.musicVolume}</span>"""))
        sb.append(settingsRow("notification_volume", "notification_volume", """<input type="range" name="notification_volume" min="0" max="15" value="${config.notificationVolume}"><span class="val" id="v_nv">${config.notificationVolume}</span>"""))
        sb.append(settingsRow("ducking_volume", "ducking_volume", """<input type="range" name="ducking_volume" min="0" max="10" value="${config.duckingVolume}"><span class="val" id="v_dv">${config.duckingVolume}</span>"""))
        sb.append(settingsRow("mic_gain", "mic_gain", """<input type="number" name="mic_gain" min="-20" max="20" value="${config.micGain}">"""))
        sb.append("</div>")

        // Voice section
        sb.append("<div class=\"card\"><h2 id=\"s_voice\"></h2>")
        sb.append(settingsToggle("continue_conversation", "continue_conversation", config.continueConversation))
        sb.append("<div class=\"row\"><label id=\"l_ww_engine\"></label><select name=\"wake_word_engine\">")
        sb.append("<option value=\"openwakeword\" ${selS(config.wakeWordEngine,"openwakeword")}>OpenWakeWord</option>")
        sb.append("<option value=\"microwakeword\" ${selS(config.wakeWordEngine,"microwakeword")}>MicroWakeWord</option>")
        sb.append("</select></div>")
        sb.append(settingsRow("wake_word_threshold", "wake_word_threshold", """<input type="range" name="wake_word_threshold" min="1" max="10" value="${(config.wakeWordThreshold*10).toInt()}"><span class="val" id="v_wt">${(config.wakeWordThreshold*10).toInt()}</span>"""))
        sb.append(settingsToggle("screen_on_wake_word", "screen_on_wake_word", config.screenOnWakeWord))
        sb.append("</div>")

        // Sensors section
        sb.append("<div class=\"card\"><h2 id=\"s_sensors\"></h2>")
        sb.append(settingsToggle("enable_motion_detection", "enable_motion_detection", config.enableMotionDetection))
        sb.append(settingsRow("motion_sensitivity", "motion_detection_sensitivity", """<input type="range" name="motion_detection_sensitivity" min="0" max="10" value="${config.motionDetectionSensitivity}"><span class="val" id="v_ms">${config.motionDetectionSensitivity}</span>"""))
        sb.append(settingsToggle("screen_on_motion", "screen_on_motion", config.screenOnMotion))
        sb.append(settingsToggle("screen_on_proximity", "screen_on_proximity", config.screenOnProximity))
        sb.append(settingsToggle("screen_on_bump", "screen_on_bump", config.screenOnBump))
        sb.append(settingsToggle("do_not_disturb", "do_not_disturb", config.doNotDisturb))
        sb.append("</div>")

        // HTTP section
        sb.append("<div class=\"card\"><h2 id=\"s_http\"></h2>")
        sb.append(settingsToggle("http_server", "http_server_enabled", config.httpServerEnabled))
        sb.append(settingsToggle("icon_server", "icon_server_enabled", config.iconServerEnabled))
        sb.append(settingsToggle("mjpeg_stream", "mjpeg_stream_enabled", config.mjpegStreamEnabled))
        sb.append(settingsRow("mjpeg_fps", "mjpeg_fps", """<input type="range" name="mjpeg_fps" min="1" max="30" value="${config.mjpegFps}"><span class="val" id="v_mf">${config.mjpegFps}</span>"""))
        sb.append(settingsRow("mjpeg_quality", "mjpeg_quality", """<input type="range" name="mjpeg_quality" min="10" max="100" value="${config.mjpegQuality}"><span class="val" id="v_mq">${config.mjpegQuality}</span>"""))

        sb.append("</div>")

        // BLE section - show location warning if needed
        val lmSettings = context.getSystemService(android.content.Context.LOCATION_SERVICE)
            as? android.location.LocationManager
        val locationOnSettings = lmSettings?.isLocationEnabled ?: true

        sb.append("<div class=\"card\"><h2 id=\"s_ble\"></h2>")
        if (!locationOnSettings) {
            sb.append("<div style=\"background:#fff3cd;border:1px solid #ffc107;border-radius:8px;padding:8px 12px;margin-bottom:8px;font-size:12px\" id=\"t_ble_loc_warn\"></div>")
        }
        sb.append(settingsToggle("ble_proxy", "ble_proxy_enabled", config.bleProxyEnabled))
        sb.append("<div class=\"row\"><label id=\"l_ble_scan_mode\"></label><select name=\"ble_scan_mode\">")
        sb.append("<option value=\"0\" ${sel(config.bleScanMode,0)} id=\"o_lp\"></option>")
        sb.append("<option value=\"1\" ${sel(config.bleScanMode,1)} id=\"o_bal\"></option>")
        sb.append("<option value=\"2\" ${sel(config.bleScanMode,2)} id=\"o_ll\"></option>")
        sb.append("</select></div>")
        sb.append(settingsRow("ble_rssi", "ble_rssi_threshold", """<input type="number" name="ble_rssi_threshold" min="-100" max="-30" value="${config.bleRssiThreshold}">"""))
        sb.append(settingsRow("ble_batch", "ble_batch_interval_ms", """<input type="number" name="ble_batch_interval_ms" min="100" max="5000" value="${config.bleBatchIntervalMs}">"""))
        sb.append("<div class=\"row\"><label id=\"l_ble_uuid\"></label><input type=\"text\" name=\"ble_uuid_filter\" value=\"${config.bleUuidFilter}\" id=\"ble_uuid_input\"></div>")
        sb.append(settingsRow("ble_max_connections", "ble_max_connections", """<input type="number" name="ble_max_connections" min="1" max="10" value="${config.bleMaxConnections}">"""))
        sb.append("</div>")

        sb.append("<div class=\"card\"><h2 id=\"s_apps\"></h2>")
        sb.append(settingsToggle("recent_apps_enabled", "recent_apps_enabled", config.recentAppsEnabled))
        sb.append(settingsRow("recent_apps_count", "recent_apps_count", """<input type="number" name="recent_apps_count" min="1" max="50" value="${config.recentAppsCount}">"""))
        sb.append(settingsRow("frequent_apps_count", "frequent_apps_count", """<input type="number" name="frequent_apps_count" min="1" max="50" value="${config.frequentAppsCount}">"""))
        sb.append("<div class=\"row\"><label id=\"l_usage_note\" style=\"color:#888;font-size:12px\"></label><button type=\"button\" onclick=\"openUsageSettings()\" id=\"t_usage_btn\" style=\"font-size:11px;padding:4px 10px;background:#555\"></button></div>")
        sb.append("</div>")
        sb.append("<button type=\"submit\" id=\"t_save\"></button><div id=\"status\"></div></form>")

        // JS: i18n labels + slider values + submit
        sb.append("<script>")
        sb.append("var labelMap={screen_brightness:'screen_brightness',screen_auto_brightness:'screen_auto_brightness',screen_always_on:'screen_always_on',dark_mode:'dark_mode',screen_timeout:'screen_timeout',screen_saver:'screen_saver',zoom_level:'zoom_level',mute:'mute',music_volume:'music_volume',notification_volume:'notification_volume',ducking_volume:'ducking_volume',mic_gain:'mic_gain',continue_conversation:'continue_conversation',wake_word_threshold:'wake_word_threshold',screen_on_wake_word:'screen_on_wake_word',enable_motion_detection:'enable_motion_detection',motion_detection_sensitivity:'motion_sensitivity',screen_on_motion:'screen_on_motion',screen_on_proximity:'screen_on_proximity',screen_on_bump:'screen_on_bump',do_not_disturb:'do_not_disturb',http_server_enabled:'http_server',icon_server_enabled:'icon_server',mjpeg_stream_enabled:'mjpeg_stream',mjpeg_fps:'mjpeg_fps',mjpeg_quality:'mjpeg_quality',ble_proxy_enabled:'ble_proxy',ble_rssi_threshold:'ble_rssi',ble_batch_interval_ms:'ble_batch'};")
        sb.append("document.getElementById('t_back').textContent=t('back');")
        sb.append("document.getElementById('t_title').textContent=t('index_settings');")
        sb.append("document.getElementById('t_save').textContent=t('save');")
        sb.append("document.getElementById('s_screen').textContent=t('section_screen');")
        sb.append("document.getElementById('s_volume').textContent=t('section_volume');")
        sb.append("document.getElementById('s_voice').textContent=t('section_voice');")
        sb.append("document.getElementById('s_sensors').textContent=t('section_sensors');")
        sb.append("document.getElementById('s_http').textContent=t('section_http');")
        sb.append("document.getElementById('s_ble').textContent=t('section_ble');")
        if (!locationOnSettings) {
            sb.append("var blw=document.getElementById('t_ble_loc_warn');if(blw)blw.textContent=t('ble_location_required');")
        }
        sb.append("document.getElementById('l_orient').textContent=t('screen_orientation');")
        sb.append("document.getElementById('o_auto').textContent=t('orient_auto');")
        sb.append("document.getElementById('o_portrait').textContent=t('orient_portrait');")
        sb.append("document.getElementById('o_landscape').textContent=t('orient_landscape');")
        sb.append("document.getElementById('l_ww_engine').textContent=t('wake_word_engine');")
        sb.append("document.getElementById('l_ble_scan_mode').textContent=t('ble_scan_mode');")
        sb.append("document.getElementById('o_lp').textContent=t('ble_scan_low_power');")
        sb.append("document.getElementById('o_bal').textContent=t('ble_scan_balanced');")
        sb.append("document.getElementById('o_ll').textContent=t('ble_scan_low_latency');")
        sb.append("document.getElementById('l_ble_uuid').textContent=t('ble_uuid_filter');")
        sb.append("document.getElementById('ble_uuid_input').placeholder=t('ble_uuid_placeholder');")
        sb.append("document.getElementById('s_apps').textContent=t('section_apps');")
        sb.append("document.getElementById('l_usage_note').textContent=t('recent_apps_note');")
        sb.append("document.getElementById('t_usage_btn').textContent=t('open_usage_settings');")
        sb.append("function openUsageSettings(){alert(t('recent_apps_note'));}")
        // Apply i18n to label rows
        sb.append("document.querySelectorAll('[data-i18n]').forEach(function(el){el.textContent=t(el.getAttribute('data-i18n'));});")
        // Slider live update
        sb.append("var smap={screen_brightness:'v_sb',music_volume:'v_mv',notification_volume:'v_nv',ducking_volume:'v_dv',wake_word_threshold:'v_wt',motion_detection_sensitivity:'v_ms',mjpeg_fps:'v_mf',mjpeg_quality:'v_mq'};")
        sb.append("document.querySelectorAll('input[type=range]').forEach(function(el){var id=smap[el.name];if(id){var v=document.getElementById(id);if(v)el.addEventListener('input',function(){v.textContent=el.value;});}});")
        // Checkboxes
        sb.append("var cbs=['screen_auto_brightness','screen_always_on','dark_mode','screen_saver','mute','continue_conversation','screen_on_wake_word','enable_motion_detection','screen_on_motion','screen_on_proximity','screen_on_bump','do_not_disturb','http_server_enabled','icon_server_enabled','mjpeg_stream_enabled','ble_proxy_enabled'];")
        // Submit
        sb.append("document.getElementById('frm').addEventListener('submit',function(e){")
        sb.append("e.preventDefault();var fd=new FormData(e.target);var obj={};")
        sb.append("cbs.forEach(function(k){obj[k]=false;});")
        sb.append("for(var p of fd.entries()){obj[p[0]]=p[1]==='on'?true:p[1];}")
        sb.append("fetch('/settings/set',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(obj)})")
        sb.append(".then(function(r){var el=document.getElementById('status');el.textContent=r.ok?t('saved_ok'):t('save_fail');el.style.color=r.ok?'green':'red';setTimeout(function(){el.textContent='';},3000);});});")
        sb.append("</script></body></html>")
        sendString(socket, sb.toString(), "text/html; charset=utf-8")
    }

    private fun settingsRow(i18nKey: String, inputName: String, inputHtml: String) =
        "<div class=\"row\"><label data-i18n=\"$i18nKey\"></label>$inputHtml</div>"

    private fun settingsToggle(i18nKey: String, name: String, value: Boolean) =
        "<div class=\"row\"><label data-i18n=\"$i18nKey\"></label><label class=\"toggle\"><input type=\"checkbox\" name=\"$name\" ${checked(value)}><span class=\"slider\"></span></label></div>"

    private fun handleSettingsSet(socket: Socket, reader: java.io.BufferedReader, headers: Map<String, String> = emptyMap()) {
        try {
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyChars = CharArray(length).also { reader.read(it) }
            val body = String(String(bodyChars).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            val json = org.json.JSONObject(body)
            json.keys().forEach { key ->
                when (key) {
                    "screen_brightness"            -> config.screenBrightness = json.getInt(key).toFloat() / 100f
                    "screen_auto_brightness"       -> config.screenAutoBrightness = json.getBoolean(key)
                    "screen_always_on"             -> config.screenAlwaysOn = json.getBoolean(key)
                    "dark_mode"                    -> config.darkMode = json.getBoolean(key)
                    "screen_timeout"               -> config.screenTimeout = json.getInt(key) * 1000
                    "screen_orientation_mode"      -> config.screenOrientationMode = json.getString(key)
                    "screen_saver"                 -> config.screenSaver = json.getBoolean(key)
                    "zoom_level"                   -> config.zoomLevel = json.getInt(key)
                    "mute"                         -> config.isMuted = json.getBoolean(key)
                    "music_volume"                 -> config.musicVolume = json.getInt(key)
                    "notification_volume"          -> config.notificationVolume = json.getInt(key)
                    "ducking_volume"               -> config.duckingVolume = json.getInt(key)
                    "mic_gain"                     -> config.micGain = json.getInt(key)
                    "continue_conversation"        -> config.continueConversation = json.getBoolean(key)
                    "wake_word_engine"             -> config.wakeWordEngine = json.getString(key)
                    "wake_word_threshold"          -> config.wakeWordThreshold = json.getInt(key).toFloat() / 10f
                    "screen_on_wake_word"          -> config.screenOnWakeWord = json.getBoolean(key)
                    "enable_motion_detection"      -> config.enableMotionDetection = json.getBoolean(key)
                    "motion_detection_sensitivity" -> config.motionDetectionSensitivity = json.getInt(key)
                    "screen_on_motion"             -> config.screenOnMotion = json.getBoolean(key)
                    "screen_on_proximity"          -> config.screenOnProximity = json.getBoolean(key)
                    "screen_on_bump"               -> config.screenOnBump = json.getBoolean(key)
                    "do_not_disturb"               -> config.doNotDisturb = json.getBoolean(key)
                    "http_server_enabled"          -> config.httpServerEnabled = json.getBoolean(key)
                    "icon_server_enabled"          -> config.iconServerEnabled = json.getBoolean(key)
                    "mjpeg_stream_enabled"         -> config.mjpegStreamEnabled = json.getBoolean(key)
                    "mjpeg_fps"                    -> config.mjpegFps = json.getInt(key)
                    "mjpeg_quality"                -> config.mjpegQuality = json.getInt(key)
                    "ble_proxy_enabled"            -> config.bleProxyEnabled = json.getBoolean(key)
                    "ble_scan_mode"                -> config.bleScanMode = json.getInt(key)
                    "ble_rssi_threshold"           -> config.bleRssiThreshold = json.getInt(key)
                    "ble_batch_interval_ms"        -> config.bleBatchIntervalMs = json.getLong(key)
                    "ble_uuid_filter"              -> config.bleUuidFilter = json.getString(key)
                    "ble_max_connections"          -> config.bleMaxConnections = json.getInt(key)
                    "recent_apps_enabled"          -> config.recentAppsEnabled = json.getBoolean(key)
                    "recent_apps_count"            -> config.recentAppsCount = json.getInt(key)
                    "frequent_apps_count"          -> config.frequentAppsCount = json.getInt(key)
                }
            }
            // If BLE max connections changed, push updated slot state to HA immediately
            if (json.has("ble_max_connections")) {
                bleGattManager?.broadcastCurrentState()
            }
            sendString(socket, "{\"status\":\"ok\"}", "application/json")
        } catch (e: Exception) {
            Timber.e("Settings set error: $e")
            sendError(socket, 500, "Error: ${e.message}")
        }
    }

    // ── BLE devices ───────────────────────────────────────────────────────────

    private fun handleBlePage(socket: Socket) {
        val scanning = config.bleProxyEnabled
        val sb = StringBuilder()
        sb.append(htmlHead("VACA BLE", "table{width:100%;border-collapse:collapse;font-size:13px}th{text-align:left;padding:.5rem .4rem;border-bottom:2px solid #eee;color:#666;font-weight:500}td{padding:.5rem .4rem;border-bottom:1px solid #f5f5f5}.rssi-bar{height:8px;border-radius:4px;background:#e0e0e0;display:inline-block;width:60px;position:relative;vertical-align:middle;margin-left:6px}.rssi-fill{height:100%;border-radius:4px;position:absolute}.badge{font-size:10px;padding:2px 6px;border-radius:10px;background:#e3f2fd;color:#1565c0;margin-left:4px}.scan-status{font-size:12px;margin-bottom:.5rem}.empty{text-align:center;color:#aaa;padding:2rem}"))
        // Check location services status
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
            as? android.location.LocationManager
        val locationOn = lm?.isLocationEnabled ?: true

        sb.append("<a class=\"back\" href=\"/\" id=\"t_back\"></a>")
        sb.append("<h1 id=\"t_title\"></h1>")
        if (!locationOn) {
            sb.append("<div style=\"background:#fff3cd;border:1px solid #ffc107;border-radius:8px;padding:10px 14px;margin-bottom:12px;font-size:13px\" id=\"t_loc_warn\"></div>")
        }
        sb.append("<div class=\"scan-status\" style=\"color:${if (scanning) "#4caf50" else "#f44336"}\" id=\"t_scan_status\"></div>")
        sb.append("<button onclick=\"load()\" id=\"t_refresh\"></button><br><br>")
        sb.append("<div class=\"card\"><table>")
        sb.append("<thead><tr><th id=\"t_col_name\"></th><th id=\"t_col_mac\"></th><th id=\"t_col_rssi\"></th><th id=\"t_col_svc\"></th><th></th></tr></thead>")
        sb.append("<tbody id=\"tbody\"><tr><td colspan=\"5\" class=\"empty\" id=\"t_loading\"></td></tr></tbody>")
        sb.append("</table></div>")
        sb.append("<script>")
        sb.append("document.getElementById('t_back').textContent=t('back');")
        sb.append("document.getElementById('t_title').textContent=t('ble_nearby');")
        sb.append("document.getElementById('t_refresh').textContent=t('refresh');")
        sb.append("document.getElementById('t_col_name').textContent=t('col_name');")
        sb.append("document.getElementById('t_col_mac').textContent=t('col_mac');")
        sb.append("document.getElementById('t_col_rssi').textContent=t('col_rssi_h');")
        sb.append("document.getElementById('t_col_svc').textContent=t('col_services');")
        sb.append("document.getElementById('t_loading').textContent=t('loading');")
        sb.append("document.getElementById('t_scan_status').textContent=t('${if (scanning) "ble_scanning" else "ble_not_scanning"}');")
        if (!locationOn) {
            sb.append("var lw=document.getElementById('t_loc_warn');if(lw)lw.textContent=t('ble_location_required');")
        }
        sb.append("function rssiColor(r){return r>=-60?'#4caf50':r>=-80?'#ff9800':'#f44336';}")
        sb.append("function rssiWidth(r){return Math.max(0,Math.min(100,(r+100)*2))+'%';}")
        sb.append("function load(){fetch('/ble/devices').then(function(r){return r.json();}).then(function(d){")
        sb.append("var tb=document.getElementById('tbody');")
        sb.append("if(!d.devices||d.devices.length===0){tb.innerHTML='<tr><td colspan=\"5\" class=\"empty\">'+t('ble_no_devices')+'</td></tr>';return;}")
        sb.append("var rows='';d.devices.forEach(function(dev){")
        sb.append("var badges='';if(dev.service_uuids){dev.service_uuids.forEach(function(u){badges+='<span class=\"badge\">'+u.substring(0,8)+'</span>';});}")
        sb.append("rows+='<tr><td><b>'+(dev.name||t('col_unknown'))+'</b></td>'")
        sb.append("+'<td style=\"font-family:monospace;font-size:12px\">'+dev.address+'</td>'")
        sb.append("+'<td>'+dev.rssi+' dBm <span class=\"rssi-bar\"><span class=\"rssi-fill\" style=\"width:'+rssiWidth(dev.rssi)+';background:'+rssiColor(dev.rssi)+'\"></span></span></td>'")
        sb.append("+'<td>'+badges+'</td>'")
        sb.append("+'<td><a href=\"/ble/gatt?address='+encodeURIComponent(dev.address)+'\" style=\"font-size:12px;color:#1565c0;text-decoration:none;padding:3px 8px;border:1px solid #1565c0;border-radius:6px\">&#x1F50D; Explore</a></td>'")
        sb.append("+'</tr>';});")
        sb.append("tb.innerHTML=rows;}).catch(function(){document.getElementById('tbody').innerHTML='<tr><td colspan=\"5\" class=\"empty\" style=\"color:#f44336\">Load failed</td></tr>';});}")
        sb.append("load();setInterval(load,3000);")
        sb.append("</script></body></html>")
        sendString(socket, sb.toString(), "text/html; charset=utf-8")
    }

    // ── BLE GATT Explorer ─────────────────────────────────────────────────────

    private fun handleBleConnect(socket: Socket, address: String) {
        if (address.isBlank()) { sendError(socket, 400, "Missing address"); return }
        if (bleGattManager == null) { sendError(socket, 503, "BLE not enabled"); return }
        bleGattManager!!.connect(address)
        sendString(socket, "{\"status\":\"connecting\",\"address\":\"$address\"}", "application/json")
    }

    private fun handleBleDisconnect(socket: Socket, address: String) {
        if (address.isBlank()) { sendError(socket, 400, "Missing address"); return }
        bleGattManager?.disconnect(address)
        sendString(socket, "{\"status\":\"disconnecting\",\"address\":\"$address\"}", "application/json")
    }

    private fun handleBleRead(socket: Socket, address: String, service: String, char: String) {
        if (address.isBlank() || service.isBlank() || char.isBlank()) {
            sendError(socket, 400, "Missing address/service/char"); return
        }
        if (bleGattManager == null) { sendError(socket, 503, "BLE not enabled"); return }
        bleGattManager!!.readCharacteristic(address, service, char)
        sendString(socket, "{\"status\":\"reading\"}", "application/json")
    }

    private fun handleBleWrite(socket: Socket, reader: java.io.BufferedReader, headers: Map<String, String>, address: String, service: String, char: String) {
        if (address.isBlank() || service.isBlank() || char.isBlank()) {
            sendError(socket, 400, "Missing address/service/char"); return
        }
        if (bleGattManager == null) { sendError(socket, 503, "BLE not enabled"); return }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buf = CharArray(length.coerceAtMost(512))
            val read = reader.read(buf, 0, buf.size)
            String(buf, 0, read.coerceAtLeast(0)).trim()
        } else ""
        // Accept hex string like "0102abcd"
        val bytes = try {
            body.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) { sendError(socket, 400, "Invalid hex: $body"); return }
        bleGattManager!!.writeCharacteristic(address, service, char, bytes)
        sendString(socket, "{\"status\":\"writing\"}", "application/json")
    }

    private fun handleBleNotify(socket: Socket, address: String, service: String, char: String, enable: String) {
        if (address.isBlank() || service.isBlank() || char.isBlank()) {
            sendError(socket, 400, "Missing address/service/char"); return
        }
        if (bleGattManager == null) { sendError(socket, 503, "BLE not enabled"); return }
        bleGattManager!!.setNotification(address, service, char, enable != "false")
        sendString(socket, "{\"status\":\"ok\"}", "application/json")
    }

    private fun handleBleEvents(socket: Socket, address: String, sinceParam: String) {
        if (address.isBlank()) { sendError(socket, 400, "Missing address"); return }
        val since = sinceParam.toLongOrNull() ?: 0L
        val deque = bleEvents[address]
        val events = if (deque != null) {
            synchronized(deque) { deque.filter { it.ts > since }.toList() }
        } else emptyList()
        val sb = StringBuilder("{\"events\":[")
        events.forEachIndexed { i, e ->
            if (i > 0) sb.append(",")
            sb.append("{\"type\":\"${e.type}\",\"ts\":${e.ts},\"data\":${e.payload}}")
        }
        val connected = bleGattManager?.getConnectedAddresses()?.contains(address) ?: false
        sb.append("],\"connected\":$connected}")
        sendString(socket, sb.toString(), "application/json")
    }

    private fun handleBleGattPage(socket: Socket, address: String) {
        if (address.isBlank()) { sendError(socket, 400, "Missing address"); return }
        val safeAddr = address.replace("\"", "").replace("<", "").replace(">", "")
        val addrJs = safeAddr.replace(":", "%3A")
        val sb = StringBuilder()
        val css = """
            .svc-card{background:#fff;border-radius:10px;border:1px solid #e0e0e0;margin-bottom:10px;overflow:hidden}
            .svc-header{background:#f5f5f5;padding:8px 12px;font-size:12px;font-weight:600;color:#333;cursor:pointer;display:flex;justify-content:space-between;align-items:center}
            .svc-body{padding:0 12px}
            .char-row{padding:8px 0;border-bottom:1px solid #f0f0f0}
            .char-row:last-child{border-bottom:none}
            .char-uuid{font-family:monospace;font-size:11px;color:#555;margin-bottom:3px}
            .prop-badge{font-size:10px;padding:1px 5px;border-radius:8px;background:#e8f5e9;color:#2e7d32}
            .prop-badge.write{background:#fff3e0;color:#e65100}
            .prop-badge.notify{background:#e3f2fd;color:#1565c0}
            .char-btns{display:flex;flex-wrap:wrap;gap:6px;margin:4px 0}
            .btn-sm{font-size:11px;padding:3px 8px;border-radius:6px;border:none;cursor:pointer;background:#4caf50;color:#fff}
            .btn-sm.secondary{background:#555}
            .btn-sm.danger{background:#f44336}
            .btn-sm.notify-btn{background:#1565c0}
            .btn-sm.subbed{background:#7b1fa2}
            .val-box{font-family:monospace;font-size:11px;background:#f9f9f9;border:1px solid #eee;border-radius:6px;padding:6px 8px;margin-top:4px;word-break:break-all;width:100%;box-sizing:border-box}
            .val-line{display:block;color:#333;margin-bottom:2px}
            .val-label{color:#999;font-size:10px;margin-right:4px;display:inline-block;min-width:54px}
            .notify-hist{margin-top:4px;font-family:monospace;font-size:10px;background:#1a1a2e;border-radius:6px;padding:4px 8px;max-height:120px;overflow-y:auto;display:none}
            .notify-hist-entry{color:#aef;border-bottom:1px solid #333;padding:2px 0}
            .notify-hist-time{color:#888;margin-right:6px}
            .adv-panel{background:#e8f4fd;border:1px solid #b3d9f7;border-radius:10px;padding:10px 14px;margin-bottom:12px;font-size:12px;display:none}
            .adv-panel h3{margin:0 0 6px;font-size:12px;color:#1565c0}
            .adv-row{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:3px}
            .adv-label{color:#888;min-width:90px;font-size:11px}
            .adv-val{font-family:monospace;color:#333;word-break:break-all;font-size:11px}
            .status-bar{font-size:12px;padding:6px 12px;border-radius:8px;margin-bottom:12px;display:inline-block}
            .status-connected{background:#e8f5e9;color:#2e7d32}
            .status-disconnected{background:#ffebee;color:#c62828}
            .status-connecting{background:#fff8e1;color:#f57f17}
            .status-failed{background:#fce4ec;color:#880e4f}
            #event-log{font-family:monospace;font-size:11px;background:#111;color:#eee;border-radius:8px;padding:8px;height:160px;overflow-y:auto;margin-top:10px;white-space:pre-wrap;word-break:break-all}
        """.trimIndent().replace("\n", "")
        sb.append(htmlHead("BLE Explorer", css))
        sb.append("<a class=\"back\" href=\"/ble\">\u2190 Back</a>")
        sb.append("<h1 style=\"font-size:1.1rem;margin:.5rem 0\">&#x1F4E1; BLE Explorer</h1>")
        sb.append("<div style=\"font-family:monospace;font-size:12px;color:#555;margin-bottom:8px\">$safeAddr</div>")
        sb.append("<div class=\"adv-panel\" id=\"adv-panel\"></div>")
        sb.append("<div id=\"status\" class=\"status-bar status-disconnected\">Disconnected</div>")
        sb.append("<div style=\"display:flex;gap:8px;margin-bottom:12px\">")
        sb.append("<button class=\"btn-sm\" id=\"btnConnect\" onclick=\"doConnect()\">Connect</button>")
        sb.append("<button class=\"btn-sm danger\" id=\"btnDisc\" onclick=\"doDisconnect()\" style=\"display:none\">Disconnect</button>")
        sb.append("</div>")
        sb.append("<div id=\"services\"></div>")
        sb.append("<h2 style=\"font-size:.95rem;color:#555;margin:.8rem 0 .3rem\">&#x1F4CB; Log</h2>")
        sb.append("<div id=\"event-log\"></div>")
        sb.append("""<script>
var ADDR=decodeURIComponent("$addrJs");
var since=0,connected=false,services=[];
var subscribedChars={};
var notifyHistory={};
var propNames={1:'BROADCAST',2:'READ',4:'WRITE_NR',8:'WRITE',16:'NOTIFY',32:'INDICATE',64:'AUTH_WRITE',128:'EXT_PROP'};
function propBadges(p){var s='';for(var k in propNames){if(p&k)s+='<span class="prop-badge'+(p&(8|4)?' write':'')+(p&(16|32)?' notify':'')+'">'+propNames[k]+'</span> ';}return s;}
function log(msg){var el=document.getElementById('event-log');el.textContent+=new Date().toLocaleTimeString()+' '+msg+'\n';el.scrollTop=el.scrollHeight;}
function setStatus(st,cls){var el=document.getElementById('status');el.textContent=st;el.className='status-bar '+(cls||(st==='Connected'?'status-connected':st.indexOf('Connecting')===0?'status-connecting':'status-disconnected'));connected=(st==='Connected');document.getElementById('btnConnect').style.display=connected?'none':'inline-block';document.getElementById('btnDisc').style.display=connected?'inline-block':'none';}
function parseHex(s){return s.replace(/\s+/g,'').replace(/[^0-9a-fA-F]/g,'');}
function hexToFormats(hex){var sp=hex.match(/.{1,2}/g)||[];var by=sp.map(function(h){return parseInt(h,16);});var utf=by.map(function(b){return(b>=32&&b<=126)?String.fromCharCode(b):'.';}).join('');var u16=by.length>=2?(by[0]|(by[1]<<8)):null;var u32=by.length>=4?((by[0]|(by[1]<<8)|(by[2]<<16)|(by[3]<<24))>>>0):null;return{spaced:sp.join(' '),decimal:by.join(' '),utf8:utf,len:by.length,u16le:u16,u32le:u32};}
function renderValBox(id,hex,ascii){var el=document.getElementById(id);if(!el||!hex)return;var f=hexToFormats(hex);var h='<span class="val-line"><span class="val-label">HEX</span>'+f.spaced+'</span>';h+='<span class="val-line"><span class="val-label">STR</span>'+ascii+'</span>';h+='<span class="val-line"><span class="val-label">DEC</span>['+f.decimal+']</span>';if(f.u16le!==null)h+='<span class="val-line"><span class="val-label">uint16LE</span>'+f.u16le+'</span>';if(f.u32le!==null)h+='<span class="val-line"><span class="val-label">uint32LE</span>'+f.u32le+'</span>';h+='<span class="val-line"><span class="val-label">LEN</span>'+f.len+' bytes</span>';el.innerHTML=h;}
function addNotifyHistory(charUuid,hex,ascii,ts){if(!hex)return;if(!notifyHistory[charUuid])notifyHistory[charUuid]=[];var hist=notifyHistory[charUuid];var dt=new Date(ts);var tm=dt.getHours().toString().padStart(2,'0')+':'+dt.getMinutes().toString().padStart(2,'0')+':'+dt.getSeconds().toString().padStart(2,'0')+'.'+dt.getMilliseconds().toString().padStart(3,'0');hist.unshift({hex:hex,ascii:ascii,tm:tm});if(hist.length>20)hist.pop();var hid='hist_'+charUuid.replace(/-/g,'_');var el=document.getElementById(hid);if(el)el.innerHTML=hist.map(function(e){return'<div class="notify-hist-entry"><span class="notify-hist-time">'+e.tm+'</span>'+e.hex+' ('+e.ascii+')</div>';}).join('');}
function toggleHistory(charUuid){var el=document.getElementById('hist_'+charUuid.replace(/-/g,'_'));if(el)el.style.display=el.style.display==='block'?'none':'block';}
function renderServices(svcs){services=svcs;var el=document.getElementById('services');if(!svcs||!svcs.length){el.innerHTML='<div style="color:#aaa;font-size:13px">No services</div>';return;}var h='';svcs.forEach(function(svc,si){h+='<div class="svc-card"><div class="svc-header" onclick="toggleSvc('+si+')"><span>'+svc.uuid+'</span><span id="arrow'+si+'">&#9660;</span></div><div class="svc-body" id="svcbody'+si+'">';svc.chars.forEach(function(ch){var hasRead=ch.props&2,hasWrite=ch.props&(4|8),hasNotify=ch.props&(16|32);var vid='val_'+ch.uuid.replace(/-/g,'_');var hid='hist_'+ch.uuid.replace(/-/g,'_');var isSub=!!subscribedChars[ch.uuid];h+='<div class="char-row"><div class="char-uuid">'+ch.uuid+'</div><div>'+propBadges(ch.props)+'</div><div class="char-btns">';if(hasRead)h+='<button class="btn-sm" onclick="doRead(\''+svc.uuid+'\',\''+ch.uuid+'\')">Read</button>';if(hasWrite)h+='<button class="btn-sm secondary" onclick="doWrite(\''+svc.uuid+'\',\''+ch.uuid+'\')">Write</button>';if(hasNotify){h+='<button class="btn-sm '+(isSub?'subbed':'notify-btn')+'" id="subbtn_'+ch.uuid.replace(/-/g,'_')+'" data-sub="'+(isSub?'1':'0')+'" onclick="doNotify(\''+svc.uuid+'\',\''+ch.uuid+'\',this)">'+(isSub?'Unsubscribe':'Subscribe')+'</button>';h+='<button class="btn-sm secondary" onclick="toggleHistory(\''+ch.uuid+'\')">History</button>';}h+='</div><div class="val-box" id="'+vid+'"> </div>';if(hasNotify)h+='<div class="notify-hist" id="'+hid+'"></div>';h+='</div>';});h+='</div></div>';});el.innerHTML=h;}
function toggleSvc(i){var b=document.getElementById('svcbody'+i),a=document.getElementById('arrow'+i);var v=b.style.display==='none';b.style.display=v?'':'none';a.innerHTML=v?'&#9660;':'&#9654;';}
function doConnect(){setStatus('Connecting...');fetch('/ble/connect?address='+encodeURIComponent(ADDR),{method:'POST'}).then(function(r){if(!r.ok){setStatus('Connection Failed (HTTP '+r.status+')','status-bar status-failed');log('Connect error: HTTP '+r.status+' (is BLE proxy enabled?)');}}).catch(function(e){setStatus('Connection Failed','status-bar status-failed');log('Connect error: '+e);});}
function doDisconnect(){fetch('/ble/disconnect?address='+encodeURIComponent(ADDR),{method:'POST'}).catch(function(e){log('Disconnect error: '+e);});}
function doRead(svc,ch){fetch('/ble/read?address='+encodeURIComponent(ADDR)+'&service='+svc+'&char='+ch).catch(function(e){log('Read error: '+e);});}
function doWrite(svc,ch){var input=prompt('Enter hex value to write (space-separated or plain, e.g. 01 FF 0A or 01FF0A):');if(!input)return;var hex=parseHex(input);if(!hex||hex.length%2!==0){alert('Invalid hex: must be even number of hex digits');return;}fetch('/ble/write?address='+encodeURIComponent(ADDR)+'&service='+svc+'&char='+ch,{method:'POST',headers:{'Content-Type':'text/plain','Content-Length':hex.length.toString()},body:hex}).catch(function(e){log('Write error: '+e);});}
function doNotify(svc,ch,btn){var enable=btn.dataset.sub!=='1';fetch('/ble/notify?address='+encodeURIComponent(ADDR)+'&service='+svc+'&char='+ch+'&enable='+enable,{method:'POST'}).then(function(){subscribedChars[ch]=enable;btn.textContent=enable?'Unsubscribe':'Subscribe';btn.className='btn-sm '+(enable?'subbed':'notify-btn');btn.dataset.sub=enable?'1':'0';log((enable?'Subscribed to ':'Unsubscribed from ')+ch);}).catch(function(e){log('Notify error: '+e);});}
function renderAdvPanel(dev){var el=document.getElementById('adv-panel');if(!el||!dev)return;var h='<h3>&#x1F4E1; Advertisement Data</h3>';h+='<div class="adv-row"><span class="adv-label">RSSI</span><span class="adv-val">'+dev.rssi+' dBm</span></div>';h+='<div class="adv-row"><span class="adv-label">TX Power</span><span class="adv-val">'+(dev.tx_power>-128?dev.tx_power+' dBm':'N/A')+'</span></div>';h+='<div class="adv-row"><span class="adv-label">Last Seen</span><span class="adv-val">'+new Date(dev.last_seen).toLocaleTimeString()+'</span></div>';if(dev.service_uuids&&dev.service_uuids.length)h+='<div class="adv-row"><span class="adv-label">Services</span><span class="adv-val">'+dev.service_uuids.join('<br>')+'</span></div>';if(dev.manufacturer_data){var mfr=Object.keys(dev.manufacturer_data).map(function(k){return'ID '+k+': '+dev.manufacturer_data[k];}).join('<br>');if(mfr)h+='<div class="adv-row"><span class="adv-label">Mfr Data</span><span class="adv-val">'+mfr+'</span></div>';}if(dev.service_data){var sd=Object.keys(dev.service_data).map(function(k){return k+': '+dev.service_data[k];}).join('<br>');if(sd)h+='<div class="adv-row"><span class="adv-label">Svc Data</span><span class="adv-val">'+sd+'</span></div>';}el.innerHTML=h;el.style.display='block';}
function fetchAdvData(){fetch('/ble/devices').then(function(r){return r.json();}).then(function(d){if(d.devices){var dev=d.devices.find(function(x){return x.address===ADDR;});if(dev)renderAdvPanel(dev);}}).catch(function(){});}
function poll(){fetch('/ble/events?address='+encodeURIComponent(ADDR)+'&since='+since).then(function(r){return r.json();}).then(function(d){if(d.events)d.events.forEach(function(e){since=Math.max(since,e.ts);if(e.type==='connected'){setStatus('Connected');renderServices(e.data.services);log('Connected \u2014 '+e.data.services.length+' service(s), MTU='+(e.data.mtu||'?'));}else if(e.type==='disconnected'){setStatus('Disconnected');document.getElementById('services').innerHTML='';log('Disconnected');}else if(e.type==='read'){renderValBox('val_'+e.data.char.replace(/-/g,'_'),e.data.hex,e.data.ascii);log('Read '+e.data.char+': 0x'+e.data.hex+' ('+e.data.ascii+')');}else if(e.type==='notification'){renderValBox('val_'+e.data.char.replace(/-/g,'_'),e.data.hex,e.data.ascii);addNotifyHistory(e.data.char,e.data.hex,e.data.ascii,e.ts);log('Notify '+e.data.char+': 0x'+e.data.hex+' ('+e.data.ascii+')');}else if(e.type==='write'){log('Write '+e.data.char+': '+(e.data.success?'OK':'FAILED'));}else if(e.type==='error'){setStatus('Connection Failed: '+e.data.msg,'status-bar status-failed');log('Error ['+e.data.op+']: '+e.data.msg);}});}).catch(function(){});setTimeout(poll,600);}
fetchAdvData();
poll();
</script></body></html>""")
        sendString(socket, sb.toString(), "text/html; charset=utf-8")
    }

    private fun handleBleDevices(socket: Socket) {
        val devices = bleScanner?.getNearbyDevices() ?: emptyList()
        val sb = StringBuilder()
        sb.append("{\"devices\":[")
        devices.forEachIndexed { i, d ->
            if (i > 0) sb.append(",")
            val name = d.name.jsonEscape()
            sb.append("{\"address\":\"${d.address}\",\"name\":\"$name\",\"rssi\":${d.rssi}")
            sb.append(",\"last_seen\":${d.lastSeen}")
            sb.append(",\"service_uuids\":[${d.serviceUuids.joinToString(",") { "\"${it.jsonEscape()}\"" }}]")
            sb.append(",\"manufacturer_id\":${d.manufacturerId ?: "null"}")
            sb.append(",\"tx_power\":${d.txPower}")
            sb.append(",\"manufacturer_data\":{${d.manufacturerData.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }}}")
            sb.append(",\"service_data\":{${d.serviceData.entries.joinToString(",") { (k, v) -> "\"${k.jsonEscape()}\":\"$v\"" }}}}")
        }
        sb.append("],\"count\":${devices.size},\"scanning\":${config.bleProxyEnabled}}")
        sendString(socket, sb.toString(), "application/json")
    }

    /** Escape a string for safe embedding inside a JSON double-quoted value. */
    private fun String.jsonEscape(): String = buildString {
        for (c in this@jsonEscape) {
            when (c) {
                '"'  -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    // ── Icon ──────────────────────────────────────────────────────────────────

    private fun handleIcon(socket: Socket, pkg: String) {
        if (!config.iconServerEnabled) { sendError(socket, 503, "Icon server disabled"); return }
        if (pkg.isEmpty()) { sendError(socket, 400, "Missing pkg"); return }
        val bytes = getIconBytes(pkg) ?: run { sendError(socket, 404, "Icon not found"); return }
        sendBytes(socket, bytes, "image/png", cache = true)
    }

    private fun getIconBytes(packageName: String): ByteArray? = try {
        val d = context.packageManager.getApplicationIcon(packageName)
        val bmp = drawableToBitmap(d)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    } catch (e: PackageManager.NameNotFoundException) { null }
    catch (e: Exception) { null }

    private fun drawableToBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        Canvas(bmp).also { d.setBounds(0, 0, 192, 192); d.draw(it) }
        return bmp
    }

    // ── MJPEG ─────────────────────────────────────────────────────────────────

    private fun handleMjpegStream(socket: Socket) {
        if (!config.mjpegStreamEnabled) { sendError(socket, 503, "Stream disabled"); return }
        val out = socket.getOutputStream()
        try {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-cache\r\n\r\n".toByteArray())
            out.flush()
            while (running && !socket.isClosed) {
                val fps = config.mjpegFps.coerceIn(1, 30)
                val intervalMs = 1000L / fps
                val frame = mjpegFrameProvider?.invoke()
                if (frame != null) {
                    out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                    out.write(frame)
                    out.write("\r\n".toByteArray())
                    out.flush()
                }
                Thread.sleep(intervalMs)
            }
        } catch (e: Exception) { Timber.d("MJPEG client disconnected") }
    }

    private fun handleSnapshot(socket: Socket) {
        if (!config.mjpegStreamEnabled) { sendError(socket, 503, "Stream disabled"); return }
        val frame = mjpegFrameProvider?.invoke() ?: run { sendError(socket, 503, "No frame available"); return }
        sendBytes(socket, frame, "image/jpeg")
    }

    // ── Status ────────────────────────────────────────────────────────────────

    private fun handleStatus(socket: Socket) {
        val json = buildString {
            append("{\"app_version\":\"${config.version}\"")
            append(",\"screen_on\":${config.screenOn}")
            append(",\"muted\":${config.isMuted}")
            append(",\"music_volume\":${config.musicVolume}")
            append(",\"notification_volume\":${config.notificationVolume}")
            append(",\"dark_mode\":${config.darkMode}")
            append(",\"do_not_disturb\":${config.doNotDisturb}")
            append(",\"current_path\":\"${config.currentPath}\"")
            append(",\"http_server_enabled\":${config.httpServerEnabled}")
            append(",\"icon_server_enabled\":${config.iconServerEnabled}")
            append(",\"mjpeg_stream_enabled\":${config.mjpegStreamEnabled}")
            append(",\"mjpeg_fps\":${config.mjpegFps}")
            append(",\"mjpeg_quality\":${config.mjpegQuality}")
            append(",\"ble_proxy_enabled\":${config.bleProxyEnabled}")
            append(",\"ble_scan_mode\":${config.bleScanMode}")
            append(",\"ble_rssi_threshold\":${config.bleRssiThreshold}")
            append(",\"ble_batch_interval_ms\":${config.bleBatchIntervalMs}")
            append(",\"ble_max_connections\":${config.bleMaxConnections}")
            append("}")
        }
        sendString(socket, json, "application/json")
    }

    // ── Wake word ─────────────────────────────────────────────────────────────

    private fun handleWakeWordPage(socket: Socket) {
        val wakeWords = WakeWords(context).getWakeWords()
        val current = config.wakeWord
        val sb = StringBuilder()
        sb.append(htmlHead("VACA Wake Word", ".badge{font-size:10px;padding:2px 6px;border-radius:10px;margin-left:6px;background:#e8f5e9;color:#2e7d32}table{width:100%;border-collapse:collapse;font-size:14px}th{text-align:left;padding:.5rem;border-bottom:2px solid #eee;color:#666;font-weight:500}td{padding:.5rem;border-bottom:1px solid #f5f5f5}button.sm{padding:4px 12px;margin:2px;cursor:pointer;border-radius:6px;border:1px solid #ccc;font-size:12px;background:#fff;color:#333}.upload-area{border:2px dashed #aaa;padding:1.5rem;text-align:center;border-radius:8px}#status{margin-top:.5rem;font-weight:bold;font-size:13px}"))
        sb.append("<a class=\"back\" href=\"/\" id=\"t_back\"></a>")
        sb.append("<h1 id=\"t_title\"></h1>")
        sb.append("<div class=\"card\"><table><thead><tr><th id=\"t_col_name\"></th><th id=\"t_col_type\"></th><th id=\"t_col_action\"></th></tr></thead><tbody>")
        wakeWords.forEach { (key, ww) ->
            val activeBadge = if (key == current) "<span class=\"badge\" id=\"t_active_badge\"></span>" else ""
            val deleteBtn = if (!ww.builtIn) "<button class=\"sm\" onclick=\"deleteWW('$key')\" id=\"t_del_btn_$key\"></button>" else ""
            val activateBtn = if (key != current) "<button class=\"sm\" onclick=\"activateWW('$key')\" id=\"t_act_btn_$key\"></button>" else ""
            sb.append("<tr><td>${ww.name}$activeBadge</td><td id=\"t_type_$key\"></td><td>$activateBtn $deleteBtn</td></tr>")
        }
        sb.append("</tbody></table></div>")
        sb.append("<div class=\"card\"><h2 id=\"t_upload_title\"></h2>")
        sb.append("<div class=\"upload-area\" id=\"drop\"><p id=\"t_drag\"></p>")
        sb.append("<input type=\"file\" id=\"fileInput\" accept=\".onnx\"></div>")
        sb.append("<div id=\"status\"></div></div>")
        sb.append("<script>")
        sb.append("document.getElementById('t_back').textContent=t('back');")
        sb.append("document.getElementById('t_title').textContent=t('ww_title');")
        sb.append("document.getElementById('t_col_name').textContent=t('col_name');")
        sb.append("document.getElementById('t_col_type').textContent=t('ww_type');")
        sb.append("document.getElementById('t_col_action').textContent=t('ww_action');")
        sb.append("document.getElementById('t_upload_title').textContent=t('ww_upload_title');")
        sb.append("document.getElementById('t_drag').textContent=t('ww_drag');")
        val activeBadgeEl = if (wakeWords.any { it.key == current }) "var ab=document.getElementById('t_active_badge');if(ab)ab.textContent=t('ww_active');" else ""
        sb.append(activeBadgeEl)
        wakeWords.forEach { (key, ww) ->
            sb.append("var te=document.getElementById('t_type_$key');if(te)te.textContent=t('${if (ww.builtIn) "ww_builtin" else "ww_custom"}');")
            if (!ww.builtIn) sb.append("var db=document.getElementById('t_del_btn_$key');if(db)db.textContent=t('ww_delete');")
            if (key != current) sb.append("var ab2=document.getElementById('t_act_btn_$key');if(ab2)ab2.textContent=t('ww_activate');")
        }
        sb.append("function setStatus(msg,ok){var el=document.getElementById('status');el.textContent=msg;el.style.color=ok?'green':'red';}")
        sb.append("document.getElementById('fileInput').addEventListener('change',function(){if(this.files.length>0)uploadFile(this.files[0]);});")
        sb.append("var drop=document.getElementById('drop');")
        sb.append("drop.addEventListener('dragover',function(e){e.preventDefault();drop.style.borderColor='#333';});")
        sb.append("drop.addEventListener('dragleave',function(){drop.style.borderColor='#aaa';});")
        sb.append("drop.addEventListener('drop',function(e){e.preventDefault();drop.style.borderColor='#aaa';if(e.dataTransfer.files.length>0)uploadFile(e.dataTransfer.files[0]);});")
        sb.append("function uploadFile(file){")
        sb.append("if(!file.name.endsWith('.onnx')){setStatus(t('ww_only_onnx'),false);return;}")
        sb.append("setStatus(t('ww_uploading'),true);")
        sb.append("var xhr=new XMLHttpRequest();xhr.open('POST','/wakeword/upload');xhr.setRequestHeader('X-Filename',file.name);")
        sb.append("xhr.onload=function(){if(xhr.status===200){setStatus(t('ww_upload_ok'),true);setTimeout(function(){location.reload();},2000);}else{setStatus(t('ww_upload_fail'),false);}};")
        sb.append("xhr.onerror=function(){setStatus(t('ww_upload_fail'),false);};")
        sb.append("xhr.send(file);}")
        sb.append("function deleteWW(key){if(!confirm(t('ww_del_confirm')))return;fetch('/wakeword/delete?name='+key,{method:'DELETE'}).then(function(r){if(r.ok)location.reload();else alert(t('ww_del_fail'));});}")
        sb.append("function activateWW(key){fetch('/wakeword/activate?name='+key,{method:'POST'}).then(function(r){if(r.ok)location.reload();else alert(t('ww_act_fail'));});}")
        sb.append("</script></body></html>")
        sendString(socket, sb.toString(), "text/html; charset=utf-8")
    }

    private fun handleWakeWordList(socket: Socket) {
        val wakeWords = WakeWords(context).getWakeWords()
        val current = config.wakeWord
        val items = wakeWords.entries.joinToString(",") { (key, ww) ->
            "{\"key\":\"$key\",\"name\":\"${ww.name}\",\"active\":${key == current},\"builtin\":${ww.builtIn}}"
        }
        sendString(socket, "{\"wake_words\":[$items]}", "application/json")
    }

    private fun handleWakeWordUpload(socket: Socket, reader: java.io.BufferedReader, headers: Map<String, String> = emptyMap()) {
        try {
            val filename = headers["x-filename"] ?: "custom.onnx"
            val length = headers["content-length"]?.toIntOrNull()
                ?: run { sendError(socket, 400, "Missing Content-Length"); return }
            if (!filename.endsWith(".onnx")) { sendError(socket, 400, "Only .onnx supported"); return }
            val dir = File(context.filesDir, "vaca").also { it.mkdirs() }
            val safeName = filename.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
            val file = File(dir, safeName)
            val chars = CharArray(length)
            var totalRead = 0
            while (totalRead < length) {
                val read = reader.read(chars, totalRead, length - totalRead)
                if (read < 0) break
                totalRead += read
            }
            file.writeBytes(String(chars, 0, totalRead).toByteArray(Charsets.ISO_8859_1))
            Timber.d("WakeWord uploaded: ${file.name} (${file.length()} bytes)")
            sendString(socket, "{\"status\":\"ok\",\"file\":\"${file.name}\",\"size\":${file.length()}}", "application/json")
        } catch (e: Exception) {
            Timber.e("WakeWord upload error: $e")
            sendError(socket, 500, "Upload failed: ${e.message}")
        }
    }

    private fun handleWakeWordDelete(socket: Socket, name: String) {
        val file = File(File(context.filesDir, "vaca"), "$name.onnx")
        if (file.exists() && file.delete()) sendString(socket, "{\"status\":\"ok\"}", "application/json")
        else sendError(socket, 404, "File not found")
    }

    private fun handleWakeWordActivate(socket: Socket, name: String) {
        config.wakeWord = name
        sendString(socket, "{\"status\":\"ok\",\"active\":\"$name\"}", "application/json")
    }

    // ── Logs ──────────────────────────────────────────────────────────────────

    private fun handleLogsPage(socket: Socket) {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>VACA Logs</title>")
        sb.append("<style>*{box-sizing:border-box}body{font-family:sans-serif;max-width:900px;margin:0 auto;padding:1rem;background:#111;color:#eee}")
        sb.append("h1{font-size:1.3rem;color:#eee}.toolbar{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:.8rem;align-items:center}")
        sb.append("select,input{background:#222;color:#eee;border:1px solid #444;border-radius:6px;padding:5px 8px;font-size:13px}")
        sb.append("button{background:#4caf50;color:#fff;border:none;border-radius:6px;padding:5px 14px;cursor:pointer;font-size:13px}")
        sb.append("button.danger{background:#f44336}button.secondary{background:#555}")
        sb.append("#log-box{background:#000;border-radius:8px;padding:1rem;height:65vh;overflow-y:auto;font-family:monospace;font-size:12px;line-height:1.6;white-space:pre-wrap;word-break:break-all}")
        sb.append(".D{color:#aaa}.I{color:#4caf50}.W{color:#ff9800}.E{color:#f44336}.V{color:#777}")
        sb.append("#status-bar{font-size:11px;color:#666;margin-top:.4rem}")
        sb.append("a.back{color:#aaa;text-decoration:none;font-size:13px;display:inline-block;margin-bottom:.8rem}")
        sb.append("label{font-size:13px;color:#aaa}</style>")
        sb.append("<script src=\"/i18n.js\"></script>")
        sb.append("</head><body>")
        sb.append("<a class=\"back\" href=\"/\" id=\"t_back\"></a>")
        sb.append("<h1 id=\"t_title\"></h1>")
        sb.append("<div class=\"toolbar\">")
        sb.append("<select id=\"level\"><option value=\"V\" id=\"t_all\"></option><option value=\"D\">Debug</option><option value=\"I\" selected>Info</option><option value=\"W\">Warning</option><option value=\"E\">Error</option></select>")
        sb.append("<input type=\"text\" id=\"filter\" style=\"width:160px\">")
        sb.append("<input type=\"number\" id=\"lines\" value=\"200\" min=\"50\" max=\"2000\" style=\"width:70px\">")
        sb.append("<button onclick=\"loadLogs()\" id=\"t_refresh\"></button>")
        sb.append("<button class=\"secondary\" id=\"autoBtn\" onclick=\"toggleAuto()\"></button>")
        sb.append("<button class=\"danger\" onclick=\"clearDisplay()\" id=\"t_clear\"></button>")
        sb.append("<label><input type=\"checkbox\" id=\"scroll\" checked> <span id=\"t_scroll\"></span></label>")
        sb.append("</div>")
        sb.append("<div id=\"log-box\"></div><div id=\"status-bar\" id=\"t_ready\"></div>")
        sb.append("<script>")
        sb.append("document.getElementById('t_back').textContent=t('back');")
        sb.append("document.getElementById('t_title').textContent=t('logs_title');")
        sb.append("document.getElementById('t_all').textContent=t('logs_all');")
        sb.append("document.getElementById('t_refresh').textContent=t('refresh');")
        sb.append("document.getElementById('autoBtn').textContent=t('logs_auto');")
        sb.append("document.getElementById('t_clear').textContent=t('logs_clear');")
        sb.append("document.getElementById('t_scroll').textContent=t('logs_scroll');")
        sb.append("document.getElementById('filter').placeholder=t('logs_filter_ph');")
        sb.append("document.getElementById('status-bar').textContent=t('ready');")
        sb.append("var autoTimer=null;")
        sb.append("function levelClass(line){var m=line.match(/\\s+([DVIWEF])\\s+/);return m?m[1]:'V';}")
        sb.append("function colorLine(line,filter){var cls=levelClass(line);var escaped=line.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');if(filter){var re=new RegExp('('+filter.replace(/[.*+?{}()|\\[\\]\\\\]/g,'\\\\$&')+')','gi');escaped=escaped.replace(re,'<span style=\"background:#ff0;color:#000\">$1</span>');}return '<span class=\"'+cls+'\">'+escaped+'</span>';}")
        sb.append("function loadLogs(){var level=document.getElementById('level').value;var filter=document.getElementById('filter').value;var lines=document.getElementById('lines').value;document.getElementById('status-bar').textContent=t('loading');fetch('/logs/data?level='+level+'&filter='+encodeURIComponent(filter)+'&lines='+lines).then(function(r){return r.json();}).then(function(d){var box=document.getElementById('log-box');box.innerHTML=d.lines.map(function(l){return colorLine(l,filter);}).join('\\n');document.getElementById('status-bar').textContent=d.lines.length+' '+t('logs_lines')+' | '+new Date().toLocaleTimeString();if(document.getElementById('scroll').checked)box.scrollTop=box.scrollHeight;}).catch(function(e){document.getElementById('status-bar').textContent=t('logs_fail')+e;});}")
        sb.append("function toggleAuto(){var btn=document.getElementById('autoBtn');if(autoTimer){clearInterval(autoTimer);autoTimer=null;btn.textContent=t('logs_auto');}else{autoTimer=setInterval(loadLogs,2000);btn.textContent=t('logs_stop');loadLogs();}}")
        sb.append("function clearDisplay(){document.getElementById('log-box').innerHTML='';}")
        sb.append("loadLogs();")
        sb.append("</script></body></html>")
        sendString(socket, sb.toString(), "text/html; charset=utf-8")
    }

    private fun handleLogsData(socket: Socket, level: String, filter: String, lines: String) {
        try {
            val lineCount = lines.toIntOrNull()?.coerceIn(50, 2000) ?: 200
            val logLevel = when (level.uppercase()) {
                "D" -> "*:D"; "I" -> "*:I"; "W" -> "*:W"; "E" -> "*:E"; else -> "*:V"
            }
            val process = ProcessBuilder("logcat", "-d", "-t", lineCount.toString(), "-v", "time", logLevel)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            val filtered = if (filter.isNotBlank()) output.filter { it.contains(filter, ignoreCase = true) } else output
            val sb = StringBuilder()
            sb.append("{\"lines\":[")
            filtered.forEachIndexed { i, line ->
                if (i > 0) sb.append(",")
                val escaped = line
                    .replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\r", "").replace("\n", "")
                    .replace("\t", "  ")
                    .filter { c -> c.code >= 0x20 }
                sb.append("\"$escaped\"")
            }
            sb.append("],\"total\":${filtered.size}}")
            sendString(socket, sb.toString(), "application/json")
        } catch (e: Exception) {
            sendString(socket, "{\"lines\":[\"Error: ${e.message}\"],\"total\":1}", "application/json")
        }
    }


    // ── i18n JS endpoint ──────────────────────────────────────────────────────

    private fun handleI18nJs(socket: Socket) {
        val bytes = i18nJsContent.toByteArray(Charsets.UTF_8)
        val out = java.io.DataOutputStream(socket.getOutputStream())
        out.writeBytes("HTTP/1.1 200 OK\r\nContent-Type: application/javascript; charset=utf-8\r\n")
        out.writeBytes("Content-Length: ${bytes.size}\r\n")
        out.writeBytes("Cache-Control: max-age=300\r\n\r\n")
        out.write(bytes)
        out.flush()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendBytes(socket: Socket, data: ByteArray, contentType: String, cache: Boolean = false) {
        val out = DataOutputStream(socket.getOutputStream())
        out.writeBytes("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${data.size}\r\nAccess-Control-Allow-Origin: *\r\n")
        if (cache) out.writeBytes("Cache-Control: max-age=86400\r\n")
        out.writeBytes("\r\n"); out.write(data); out.flush()
    }

    private fun sendString(socket: Socket, body: String, contentType: String) =
        sendBytes(socket, body.toByteArray(Charsets.UTF_8), contentType)

    private fun sendError(socket: Socket, code: Int, message: String) {
        try {
            val out = DataOutputStream(socket.getOutputStream())
            out.writeBytes("HTTP/1.1 $code $message\r\nContent-Length: ${message.length}\r\n\r\n$message")
            out.flush()
        } catch (e: Exception) {}
    }
}
