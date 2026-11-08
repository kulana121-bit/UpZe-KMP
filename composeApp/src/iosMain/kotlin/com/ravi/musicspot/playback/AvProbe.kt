package com.ravi.musicspot.playback

import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAudioMixInputParameters
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.setVolume
import platform.CoreMedia.CMTime
import platform.Foundation.NSObject
import platform.Foundation.NSURL
import platform.Foundation.setValue

// Temporary compile probe: maps which AVFoundation binding shapes exist in Kotlin/Native 2.1.
internal fun probeAsset(item: AVPlayerItem) {
    val a = item.asset
    val tracks = a.tracksWithMediaType(AVMediaTypeAudio)
    val first = tracks.firstOrNull() as? AVAssetTrack
    println(first)
}

internal fun probeParams(track: AVAssetTrack, time: CMTime) {
    val params = AVAudioMixInputParameters(assetTrack = track)
    params.setVolume(0.5f, time)
    val mix = AVMutableAudioMix()
    mix.inputParameters = listOf(params)
    println(mix)
}

internal fun probeItemMix(item: AVPlayerItem, mix: AVMutableAudioMix) {
    item.audioMix = mix
}

internal fun probeUrlAsset(url: NSURL) {
    val asset = AVURLAsset(uRL = url, options = null)
    println(asset.tracksWithMediaType(AVMediaTypeAudio).size)
}

internal fun probeKvc(player: AVPlayer) {
    val o: NSObject = player
    o.setValue(0.5f, forKey = "volume")
}
