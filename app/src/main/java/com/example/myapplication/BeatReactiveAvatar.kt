package com.example.myapplication

interface BeatReactiveAvatar {
    fun setAudioLevel(level: Float)
    fun setDanceStyle(style: DanceStyle)
    fun setPlaybackState(state: PlaybackDanceState)
    fun onSongChanged()
    fun onBeat(event: BeatEvent)
}

@Suppress("unused")
private val beatReactiveAvatarContract = arrayOf(
    BeatReactiveAvatar::class,
    BeatReactiveAvatar::setAudioLevel,
    BeatReactiveAvatar::setDanceStyle,
    BeatReactiveAvatar::setPlaybackState,
    BeatReactiveAvatar::onSongChanged,
    BeatReactiveAvatar::onBeat,
)

