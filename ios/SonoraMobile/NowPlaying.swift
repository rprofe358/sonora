import AVFoundation
import MediaPlayer
import UIKit

/// La sesión de audio del sistema, los controles de la pantalla de bloqueo y del Centro de
/// control, y la información de lo que suena.
@MainActor
final class NowPlaying {
    private var artworkURL: String?
    private var artwork: MPMediaItemArtwork?
    private var observers: [NSObjectProtocol] = []

    func attach(engine: Engine) {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, policy: .longFormAudio)
            try session.setActive(true)
        } catch {
            print("audio: no se pudo activar la sesión: \(error)")
        }

        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { _ in engine.play(); return .success }
        center.pauseCommand.addTarget { _ in engine.pause(); return .success }
        center.togglePlayPauseCommand.addTarget { _ in engine.toggle(); return .success }
        center.nextTrackCommand.addTarget { _ in engine.next(); return .success }
        center.previousTrackCommand.addTarget { _ in engine.previous(); return .success }
        center.changePlaybackPositionCommand.addTarget { event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            engine.seek(positionMs: UInt64(max(0, event.positionTime) * 1000))
            return .success
        }

        let notifications = NotificationCenter.default
        observers.append(notifications.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { note in
            guard let info = note.userInfo,
                  let raw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let kind = AVAudioSession.InterruptionType(rawValue: raw)
            else { return }
            switch kind {
            case .began:
                engine.pause()
            case .ended:
                let options = AVAudioSession.InterruptionOptions(
                    rawValue: info[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                )
                if options.contains(.shouldResume) {
                    try? AVAudioSession.sharedInstance().setActive(true)
                    engine.play()
                }
            @unknown default:
                break
            }
        })
        observers.append(notifications.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
        ) { note in
            // Al desconectar los audífonos se pausa, como hace cualquier app de música.
            guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable
            else { return }
            engine.pause()
        })
    }

    func update(_ state: PlaybackState) {
        let center = MPNowPlayingInfoCenter.default()
        guard let track = state.track else {
            center.nowPlayingInfo = nil
            center.playbackState = .stopped
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artists,
            MPMediaItemPropertyAlbumTitle: track.album,
            MPMediaItemPropertyPlaybackDuration: Double(state.durationMs) / 1000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(state.positionMs) / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: state.status == .playing ? 1.0 : 0.0,
        ]
        if track.cover == artworkURL, let artwork {
            info[MPMediaItemPropertyArtwork] = artwork
        } else {
            loadArtwork(track.cover)
        }
        center.nowPlayingInfo = info
        center.playbackState = state.status == .playing ? .playing : .paused
    }

    private func loadArtwork(_ cover: String?) {
        artworkURL = cover
        artwork = nil
        guard let cover else { return }
        Task {
            guard let image = await ImagePipeline.shared.image(cover), self.artworkURL == cover
            else { return }
            let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            self.artwork = artwork
            var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            info[MPMediaItemPropertyArtwork] = artwork
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }
}
