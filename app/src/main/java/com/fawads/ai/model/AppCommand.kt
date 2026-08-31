package com.fawads.ai.model

/**
 * A structured command parsed from the user's spoken text.
 * [params] carries extra arguments (app name, contact name, number, message, etc.).
 */
data class AppCommand(
    val type: String,
    val params: Map<String, String> = emptyMap()
)

object CommandType {
    const val OPEN_APP = "OPEN_APP"
    const val CLOSE_APP = "CLOSE_APP"
    const val CALL = "CALL"
    const val SMS = "SMS"
    const val WHATSAPP_MSG = "WHATSAPP_MSG"
    const val WHATSAPP_CALL = "WHATSAPP_CALL"
    const val PRIME_CALL = "PRIME_CALL"
    const val PRIME_MSG = "PRIME_MSG"
    const val VOLUME_UP = "VOLUME_UP"
    const val VOLUME_DOWN = "VOLUME_DOWN"
    const val FLASHLIGHT_ON = "FLASHLIGHT_ON"
    const val FLASHLIGHT_OFF = "FLASHLIGHT_OFF"
    const val WIFI_ON = "WIFI_ON"
    const val WIFI_OFF = "WIFI_OFF"
    const val BLUETOOTH_ON = "BLUETOOTH_ON"
    const val BLUETOOTH_OFF = "BLUETOOTH_OFF"
    const val MUTE = "MUTE"
    const val UNMUTE = "UNMUTE"
    const val STOP = "STOP"
    const val NONE = "NONE"

    // Extended features
    const val ALARM = "ALARM"
    const val TIMER = "TIMER"
    const val REMINDER = "REMINDER"
    const val ADD_NOTE = "ADD_NOTE"
    const val OPEN_NOTES = "OPEN_NOTES"
    const val WEATHER = "WEATHER"
    const val NEWS = "NEWS"
    const val CRYPTO = "CRYPTO"
    const val PLAY_MUSIC = "PLAY_MUSIC"
    const val SEARCH_YOUTUBE = "SEARCH_YOUTUBE"
    const val SEARCH_WEB = "SEARCH_WEB"
    const val OPEN_SETTINGS = "OPEN_SETTINGS"
}
