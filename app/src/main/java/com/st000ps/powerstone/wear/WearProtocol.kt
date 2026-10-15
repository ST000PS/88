package com.st000ps.powerstone.wear

/**
 * What the phone and Pixel Watch apps say to each other over the Wearable Data Layer.
 * Shared by both modules (the watch module compiles this file too).
 *
 * Phone → watch: the [NOW] data item (now playing, kept in sync) and the [SESSION] reply.
 * Watch → phone: [CMD] messages and a [SESSION_REQUEST].
 */
object WearProtocol {
    const val NOW = "/88/now"
    const val CMD = "/88/cmd"
    const val SESSION_REQUEST = "/88/session/request"
    const val SESSION = "/88/session"

    // [NOW] data map keys
    const val TITLE = "title"
    const val ARTIST = "artist"
    const val RELEASE = "release"
    const val BPM = "bpm"
    const val KEY = "key"
    const val PLAYING = "playing"
    const val HAS_TRACK = "hasTrack"
    const val FAV = "fav"
    const val CAN_FAV = "canFav"
    const val POSITION = "positionMs"
    const val POSITION_AT = "positionAt" // wall clock when POSITION was read
    const val DURATION = "durationMs"
    const val EQ = "eq"                  // EqState as JSON
    const val ART = "art"                // Asset: small PNG cover
    const val RADIO = "radioBpm"         // heart-rate radio target, 0 = off
    const val KIND = "kind"              // queue kind (SHUFFLE, ALBUM, RADIO, ...)

    // [CMD] payloads (UTF-8)
    const val PLAY_PAUSE = "play_pause"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val TOGGLE_FAV = "fav"
    const val SHUFFLE = "shuffle"
    const val EQ_PREFIX = "eq:"          // + EqState JSON
    const val RADIO_PREFIX = "radio:"    // + bpm, or "off"
}
