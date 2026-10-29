import AVFoundation
import Flutter
import Foundation
import UIKit

/// Flutter calls copyPixelBuffer on its raster thread. AVFoundation and session
/// state stay on MainActor; only this retained pixel buffer crosses that boundary.
private final class IOSComparisonPixelStore: @unchecked Sendable {
  private let lock = NSLock()
  private var buffer: CVPixelBuffer?

  func store(_ value: CVPixelBuffer?) {
    lock.lock(); buffer = value; lock.unlock()
  }

  func copy() -> Unmanaged<CVPixelBuffer>? {
    lock.lock(); defer { lock.unlock() }
    return buffer.map { Unmanaged.passRetained($0) }
  }
}

@MainActor
private final class IOSComparisonDisplayTarget: NSObject {
  weak var owner: IOSComparisonPlayer?
  @objc func tick(_ link: CADisplayLink) { owner?.displayTick(link) }
}

/// One AVPlayer owns both composed pictures and the reference audio clock.
/// Composition time is reference source time minus referenceStartUs; practice
/// speed changes the player rate, never the target = reference * scale + offset.
@MainActor
final class IOSComparisonPlayer: NSObject, FlutterTexture {
  private struct Configuration {
    var referenceURL: URL
    var targetURL: URL
    var offsetUs: Int64
    var timeScale: Double
    var width: Int
    var height: Int
    var sideBySide: Bool
    var practice: ComparisonPracticeDto
  }

  private let registry: FlutterTextureRegistry
  private nonisolated let pixels = IOSComparisonPixelStore()
  private var textureId: Int64 = -1
  private var config: Configuration?
  private var composition: IOSComparisonComposition?
  private var player: AVPlayer?
  private var item: AVPlayerItem?
  private var output: AVPlayerItemVideoOutput?
  private var displayLink: CADisplayLink?
  private var displayTarget: IOSComparisonDisplayTarget?
  private var backgroundObserver: NSObjectProtocol?
  private var foregroundObserver: NSObjectProtocol?
  private var endObserver: NSObjectProtocol?
  private var loopBoundaryObserver: Any?
  private var loopRevision: UInt64 = 0
  private var referenceIndexTask: Task<IOSComparisonFrameIndex, Error>?
  private var targetIndexTask: Task<IOSComparisonFrameIndex, Error>?
  private var referenceIndex: IOSComparisonFrameIndex?
  private var targetIndex: IOSComparisonFrameIndex?
  private var scopedURLs: [URL] = []
  private var generation: UInt64 = 0
  private var seekRevision: UInt64 = 0
  private var pendingUs: Int64 = 0
  private var speed: Double = 1
  private var wantsPlay = false
  private var foreground = true
  private var ready = false
  private var seeking = false
  private var loopSeeking = false
  private var closed = false
  private var playbackError: String?
  private var presentedReferenceUs: Int64?
  private var presentedTargetUs: Int64?

  init(registry: FlutterTextureRegistry) {
    self.registry = registry
    super.init()
    textureId = registry.register(self)
    let target = IOSComparisonDisplayTarget()
    target.owner = self
    displayTarget = target
    let link = CADisplayLink(target: target, selector: #selector(IOSComparisonDisplayTarget.tick(_:)))
    link.add(to: .main, forMode: .common)
    displayLink = link
    backgroundObserver = NotificationCenter.default.addObserver(
      forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
    ) { [weak self] _ in Task { @MainActor in self?.enteredBackground() } }
    foregroundObserver = NotificationCenter.default.addObserver(
      forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main
    ) { [weak self] _ in Task { @MainActor in self?.foreground = true } }
  }

  nonisolated func copyPixelBuffer() -> Unmanaged<CVPixelBuffer>? { pixels.copy() }

  func open(referenceURL: URL, targetURL: URL, offsetUs: Int64, positionUs: Int64,
    speed: Double, width: Int, height: Int, sideBySide: Bool,
    practice: ComparisonPracticeDto, timeScale: Double) async throws -> ComparisonPlaybackDto {
    try requireOpen()
    try validate(offsetUs: offsetUs, scale: timeScale, width: width, height: height, speed: speed)
    referenceIndexTask?.cancel(); targetIndexTask?.cancel()
    referenceIndex = nil; targetIndex = nil
    for url in scopedURLs { url.stopAccessingSecurityScopedResource() }
    scopedURLs = []
    for url in Set([referenceURL, targetURL]) where url.isFileURL {
      if url.startAccessingSecurityScopedResource() { scopedURLs.append(url) }
    }
    referenceIndexTask = Task.detached(priority: .userInitiated) {
      try IOSComparisonFrameIndex.read(url: referenceURL, isCancelled: { Task.isCancelled })
    }
    targetIndexTask = Task.detached(priority: .userInitiated) {
      try IOSComparisonFrameIndex.read(url: targetURL, isCancelled: { Task.isCancelled })
    }
    self.speed = speed
    let next = Configuration(referenceURL: referenceURL, targetURL: targetURL,
      offsetUs: offsetUs, timeScale: timeScale, width: width, height: height,
      sideBySide: sideBySide, practice: practice)
    return try await rebuild(next, positionUs: max(0, positionUs), sourcePositionUs: nil, resume: false)
  }

  func control(command: ComparisonCommand, valueUs: Int64, speed newSpeed: Double) async throws -> ComparisonPlaybackDto {
    try requireReady()
    switch command {
    case .play:
      if let loop = loopRange() {
        if !loop.contains(positionUs()) { try await seek(to: loop.lowerBound, resume: false) }
      } else if let composition, positionUs() >= max(0, composition.durationUs - 100_000) {
        try await seek(to: 0, resume: false)
      }
      wantsPlay = foreground
      reconcile()
    case .pause:
      pendingUs = positionUs(); wantsPlay = false; player?.pause()
      try await seek(to: pendingUs, resume: false)
    case .seek:
      try await seek(to: valueUs, resume: wantsPlay)
    case .setOffset:
      return try await setAlignment(offsetUs: valueUs, timeScale: 1)
    case .setSpeed:
      guard [0.5, 0.75, 1].contains(newSpeed) else { throw failure("练习速度无效。") }
      pendingUs = positionUs()
      speed = newSpeed
      reconcile()
    }
    return state()
  }

  func setAlignment(offsetUs: Int64, timeScale: Double) async throws -> ComparisonPlaybackDto {
    try requireReady()
    guard var next = config, let composition else { throw failure("请先打开两段视频。") }
    try validate(offsetUs: offsetUs, scale: timeScale, width: next.width, height: next.height, speed: speed)
    let source = composition.referenceStartUs + positionUs()
    let oldStart = composition.referenceStartUs
    next.offsetUs = offsetUs; next.timeScale = timeScale
    return try await rebuild(next, positionUs: 0, sourcePositionUs: source,
      resume: false, previousReferenceStartUs: oldStart)
  }

  func resize(width: Int, height: Int, sideBySide: Bool) async throws -> ComparisonPlaybackDto {
    try requireReady()
    guard var next = config else { throw failure("请先打开两段视频。") }
    try validate(offsetUs: next.offsetUs, scale: next.timeScale, width: width, height: height, speed: speed)
    if next.width == width && next.height == height && next.sideBySide == sideBySide { return state() }
    let position = positionUs(); let resume = wantsPlay
    next.width = width; next.height = height; next.sideBySide = sideBySide
    return try await rebuild(next, positionUs: position, sourcePositionUs: nil, resume: resume)
  }

  func setPractice(_ practice: ComparisonPracticeDto) async throws -> ComparisonPlaybackDto {
    try requireReady()
    guard var next = config, let composition else { throw failure("请先打开两段视频。") }
    try validatePractice(practice, durationUs: composition.durationUs)
    let previous = next.practice
    next.practice = practice
    if previous.mirrorTeacher != practice.mirrorTeacher || previous.mirrorLearner != practice.mirrorLearner ||
        !samePicture(previous.teacherPicture, practice.teacherPicture) || !samePicture(previous.learnerPicture, practice.learnerPicture) {
      return try await rebuild(next, positionUs: positionUs(), sourcePositionUs: nil, resume: wantsPlay)
    }
    config = next
    installLoopBoundary()
    if let loop = loopRange(), !loop.contains(positionUs()) { try await seek(to: loop.lowerBound, resume: wantsPlay) }
    return state()
  }

  func stepFrame(forward: Bool) async throws -> ComparisonPlaybackDto {
    try requireReady()
    guard let composition, let index = referenceIndex else { throw failure("视频帧时间尚未就绪。") }
    let source = composition.referenceStartUs + positionUs()
    let next = index.adjacent(to: source, forward: forward, startUs: composition.referenceStartUs,
      endUs: composition.referenceStartUs + composition.durationUs) ?? source
    wantsPlay = false
    try await seek(to: next - composition.referenceStartUs, resume: false)
    return state()
  }

  func state() -> ComparisonPlaybackDto {
    let phase: String
    if closed { phase = "closed" }
    else if playbackError != nil { phase = "error" }
    else if !ready || seeking { phase = wantsPlay ? "buffering" : "opening" }
    else if wantsPlay && player?.timeControlStatus != .playing { phase = "buffering" }
    else { phase = wantsPlay ? "playing" : "paused" }
    return ComparisonPlaybackDto(textureId: textureId, phase: phase, positionUs: positionUs(),
      durationUs: composition?.durationUs ?? 0, teacherStartUs: composition?.referenceStartUs ?? 0,
      offsetUs: config?.offsetUs ?? 0, speed: speed, error: playbackError,
      teacherFrameUs: presentedReferenceUs, learnerFrameUs: presentedTargetUs,
      teacherAspect: composition?.referenceAspect, learnerAspect: composition?.targetAspect,
      timeScale: config?.timeScale ?? 1)
  }

  func close() {
    guard !closed else { return }
    pendingUs = positionUs(); closed = true; wantsPlay = false; ready = false
    generation &+= 1; seekRevision &+= 1
    referenceIndexTask?.cancel(); targetIndexTask?.cancel()
    referenceIndexTask = nil; targetIndexTask = nil
    referenceIndex = nil; targetIndex = nil
    releasePlayer()
    displayLink?.invalidate(); displayLink = nil; displayTarget = nil
    if let backgroundObserver { NotificationCenter.default.removeObserver(backgroundObserver) }
    if let foregroundObserver { NotificationCenter.default.removeObserver(foregroundObserver) }
    backgroundObserver = nil; foregroundObserver = nil
    pixels.store(nil)
    registry.unregisterTexture(textureId)
    for url in scopedURLs { url.stopAccessingSecurityScopedResource() }
    scopedURLs = []
    composition = nil; config = nil
  }

  private func rebuild(_ requested: Configuration, positionUs: Int64, sourcePositionUs: Int64?,
    resume: Bool, previousReferenceStartUs: Int64? = nil) async throws -> ComparisonPlaybackDto {
    generation &+= 1; seekRevision &+= 1
    let current = generation
    var next = requested
    wantsPlay = resume && foreground; ready = false; seeking = false; loopSeeking = false
    playbackError = nil; pendingUs = positionUs
    config = next
    releasePlayer()
    pixels.store(nil); presentedReferenceUs = nil; presentedTargetUs = nil
    do {
      let built = try await IOSComparisonComposition.make(referenceURL: next.referenceURL, targetURL: next.targetURL,
        offsetUs: next.offsetUs, timeScale: next.timeScale, width: next.width, height: next.height,
        gap: 8, sideBySide: next.sideBySide, practice: next.practice)
      try check(current)
      if let oldStart = previousReferenceStartUs {
        next.practice = translatedPractice(next.practice, oldStart: oldStart, newStart: built.referenceStartUs, duration: built.durationUs)
      }
      try validatePractice(next.practice, durationUs: built.durationUs)
      if referenceIndex == nil, let task = referenceIndexTask {
        let loaded = try await task.value
        try check(current)
        referenceIndex = loaded
      }
      try check(current)
      if targetIndex == nil, let task = targetIndexTask {
        let loaded = try await task.value
        try check(current)
        targetIndex = loaded
      }
      try check(current)
      config = next; composition = built
      let newItem = AVPlayerItem(asset: built.asset)
      newItem.videoComposition = built.videoComposition
      newItem.audioTimePitchAlgorithm = .spectral
      newItem.seekingWaitsForVideoCompositionRendering = true
      let newOutput = makeVideoOutput()
      newItem.add(newOutput)
      item = newItem; output = newOutput
      let newPlayer = AVPlayer(playerItem: newItem)
      newPlayer.automaticallyWaitsToMinimizeStalling = true
      player = newPlayer
      endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime,
        object: newItem, queue: .main) { [weak self] _ in
          Task { @MainActor in self?.reachedEnd(generation: current) }
        }
      try await waitUntilReady(newItem, generation: current)
      installLoopBoundary()
      let desired = sourcePositionUs.map { $0 - built.referenceStartUs } ?? positionUs
      ready = true
      try await seek(to: desired, resume: wantsPlay)
      try check(current)
      return state()
    } catch {
      if current == generation && !closed {
        ready = false; wantsPlay = false; player?.pause()
        if !(error is CancellationError) { playbackError = error.localizedDescription }
      }
      throw error
    }
  }

  private func waitUntilReady(_ item: AVPlayerItem, generation current: UInt64) async throws {
    let deadline = CACurrentMediaTime() + 15
    while item.status == .unknown {
      try check(current)
      guard CACurrentMediaTime() < deadline else { throw failure("视频加载超时，请重新打开。") }
      try await Task.sleep(nanoseconds: 16_000_000)
    }
    try check(current)
    guard item.status == .readyToPlay else { throw item.error ?? failure("视频暂时无法播放。") }
  }

  private func seek(to requested: Int64, resume: Bool) async throws {
    try requireReady()
    guard let player, let composition else { throw failure("请先打开两段视频。") }
    let current = generation
    seekRevision &+= 1
    let revision = seekRevision
    let target = min(max(0, requested), max(0, composition.durationUs - 1))
    pendingUs = target; seeking = true; wantsPlay = resume && foreground
    player.pause()
    pixels.store(nil); presentedReferenceUs = nil; presentedTargetUs = nil
    // Keep one output for the item's lifetime. Reattaching a suppressed output
    // reconfigures AVFoundation's render pipeline and can override an exact
    // seek with its preroll position. Seeking itself flushes the output sequence.
    let finished = await withCheckedContinuation { continuation in
      player.seek(to: CMTime(value: target, timescale: 1_000_000), toleranceBefore: .zero, toleranceAfter: .zero) {
        continuation.resume(returning: $0)
      }
    }
    try check(current)
    guard revision == seekRevision, finished else { throw CancellationError() }
    do {
      output?.requestNotificationOfMediaDataChange(withAdvanceInterval: 0)
      let deadline = CACurrentMediaTime() + 3
      while !refreshPixel(force: true) {
        try check(current)
        guard revision == seekRevision else { throw CancellationError() }
        if let item, item.status == .failed { throw item.error ?? failure("无法刷新视频画面。") }
        guard CACurrentMediaTime() < deadline else { throw failure("画面尚未就绪，请重试。") }
        try await Task.sleep(nanoseconds: 16_000_000)
      }
      try check(current)
      guard revision == seekRevision else { throw CancellationError() }
      seeking = false
      reconcile()
    } catch {
      if current == generation && revision == seekRevision {
        seeking = false; wantsPlay = false; player.pause()
        if !(error is CancellationError) { playbackError = error.localizedDescription }
      }
      throw error
    }
  }

  private func positionUs() -> Int64 {
    guard ready, !seeking, let player, let composition else { return pendingUs }
    let time = player.currentTime()
    guard time.isNumeric else { return pendingUs }
    let value = CMTimeConvertScale(time, timescale: 1_000_000, method: .roundHalfAwayFromZero).value
    return min(max(0, value), max(0, composition.durationUs - 1))
  }

  private func loopRange() -> Range<Int64>? {
    guard let practice = config?.practice, practice.loopEnabled,
          let start = practice.loopStartUs, let end = practice.loopEndUs,
          let duration = composition?.durationUs, start >= 0, end > start, end <= duration else { return nil }
    return start..<end
  }

  fileprivate func displayTick(_ link: CADisplayLink) {
    guard !closed, ready, !seeking else { return }
    if item?.status == .failed {
      pendingUs = positionUs(); wantsPlay = false; player?.pause()
      playbackError = item?.error?.localizedDescription ?? "视频暂时无法播放。"
      return
    }
    if wantsPlay && foreground && !loopSeeking {
      if let loop = loopRange(), positionUs() >= loop.upperBound {
        restartLoop(loop.lowerBound)
        return
      }
      if let composition, positionUs() >= composition.durationUs - 1 {
        reachedEnd(generation: generation)
      }
    }
    _ = refreshPixel(force: false, hostTime: link.targetTimestamp)
  }

  @discardableResult
  private func refreshPixel(force: Bool, hostTime: CFTimeInterval? = nil) -> Bool {
    guard let player, let output, let composition, let config else { return false }
    var requested = (!force && wantsPlay && hostTime != nil)
      ? output.itemTime(forHostTime: hostTime!) : player.currentTime()
    // The next display refresh can be ahead of the current audio clock. Keep
    // the loop's exclusive B frame out of that anticipatory video request.
    if wantsPlay, let loop = loopRange(), requested.isNumeric,
       CMTimeCompare(requested, CMTime(value: loop.upperBound, timescale: 1_000_000)) >= 0 {
      requested = CMTime(value: loop.upperBound - 1, timescale: 1_000_000)
    }
    guard requested.isNumeric, force || output.hasNewPixelBuffer(forItemTime: requested) else { return false }
    var displayed = CMTime.invalid
    guard let buffer = output.copyPixelBuffer(forItemTime: requested, itemTimeForDisplay: &displayed) else { return false }
    let time = displayed.isNumeric ? displayed : requested
    let sourceTime = CMTimeAdd(time, CMTime(value: composition.referenceStartUs, timescale: 1_000_000))
    presentedReferenceUs = referenceIndex?.frame(atOrBefore: sourceTime)
    // Keep rational source precision through the affine mapping. Rounding
    // 11/6s to microseconds first would map .8x + 1.2s just before the true
    // target 8/3s boundary and report the previous target frame.
    let targetTime = CMTimeAdd(CMTimeMultiplyByFloat64(sourceTime, multiplier: config.timeScale),
      CMTime(value: config.offsetUs, timescale: 1_000_000))
    presentedTargetUs = targetIndex?.frame(atOrBefore: targetTime)
    pixels.store(buffer)
    registry.textureFrameAvailable(textureId)
    return true
  }

  private func restartLoop(_ position: Int64) {
    guard !loopSeeking else { return }
    loopSeeking = true
    let current = generation
    Task { @MainActor [weak self] in
      guard let self else { return }
      defer { if current == self.generation { self.loopSeeking = false } }
      guard current == self.generation, self.wantsPlay, self.foreground else { return }
      do { try await self.seek(to: position, resume: true) }
      catch { if current == self.generation && !(error is CancellationError) { self.playbackError = error.localizedDescription } }
    }
  }

  private func reachedEnd(generation current: UInt64) {
    guard current == generation, !closed else { return }
    if wantsPlay, let loop = loopRange() { restartLoop(loop.lowerBound) }
    else {
      pendingUs = max(0, (composition?.durationUs ?? 1) - 1)
      wantsPlay = false; player?.pause()
    }
  }

  private func reconcile() {
    if wantsPlay && foreground && ready && !seeking && playbackError == nil { player?.rate = Float(speed) }
    else { player?.pause() }
  }

  private func enteredBackground() {
    guard !closed else { return }
    pendingUs = positionUs(); wantsPlay = false; foreground = false; player?.pause()
  }

  private func releasePlayer() {
    if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
    endObserver = nil
    if let loopBoundaryObserver { player?.removeTimeObserver(loopBoundaryObserver) }
    loopBoundaryObserver = nil
    loopRevision &+= 1
    player?.pause(); player?.cancelPendingPrerolls()
    item?.cancelPendingSeeks()
    if let output { item?.remove(output) }
    player?.replaceCurrentItem(with: nil)
    output = nil; item = nil; player = nil
  }

  private func makeVideoOutput() -> AVPlayerItemVideoOutput {
    let result = AVPlayerItemVideoOutput(pixelBufferAttributes: [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
      kCVPixelBufferIOSurfacePropertiesKey as String: [:],
    ])
    result.suppressesPlayerRendering = true
    return result
  }

  private func installLoopBoundary() {
    if let loopBoundaryObserver { player?.removeTimeObserver(loopBoundaryObserver) }
    loopBoundaryObserver = nil
    loopRevision &+= 1
    guard let player, let loop = loopRange() else { return }
    let current = generation
    let revision = loopRevision
    let time = NSValue(time: CMTime(value: loop.upperBound, timescale: 1_000_000))
    loopBoundaryObserver = player.addBoundaryTimeObserver(forTimes: [time], queue: .main) { [weak self] in
      Task { @MainActor in
        guard let self, current == self.generation, revision == self.loopRevision, self.wantsPlay, self.foreground,
              let activeLoop = self.loopRange() else { return }
        self.restartLoop(activeLoop.lowerBound)
      }
    }
  }

  private func requireOpen() throws { if closed { throw failure("播放会话已关闭。") } }
  private func requireReady() throws {
    try requireOpen()
    guard ready else { throw failure("请等待画面就绪。") }
  }
  private func check(_ expected: UInt64) throws {
    guard !closed, expected == generation, !Task.isCancelled else { throw CancellationError() }
  }
  private func failure(_ message: String) -> NSError {
    NSError(domain: "WoahComparison", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
  }
  private func validate(offsetUs: Int64, scale: Double, width: Int, height: Int, speed: Double) throws {
    guard (-172_800_000_000...172_800_000_000).contains(offsetUs), scale.isFinite, (0.5...2).contains(scale),
          (2...4096).contains(width), (2...4096).contains(height), [0.5, 0.75, 1].contains(speed) else {
      throw failure("对比播放参数无效。")
    }
  }
  private func validatePractice(_ practice: ComparisonPracticeDto, durationUs: Int64) throws {
    if let start = practice.loopStartUs, start < 0 || start >= durationUs { throw failure("循环起点超出共同区间。") }
    if let end = practice.loopEndUs, end <= (practice.loopStartUs ?? 0) || end > durationUs { throw failure("循环终点超出共同区间。") }
    if practice.loopEnabled && (practice.loopStartUs == nil || practice.loopEndUs == nil) { throw failure("请先设置循环起点和终点。") }
  }
  private func translatedPractice(_ value: ComparisonPracticeDto, oldStart: Int64, newStart: Int64, duration: Int64) -> ComparisonPracticeDto {
    var result = value
    let start = value.loopStartUs.map { oldStart + $0 - newStart }
    let end = value.loopEndUs.map { oldStart + $0 - newStart }
    let available: Bool
    if let start {
      available = start < duration && (end.map { $0 > 0 && $0 > start } ?? (start >= 0))
    } else { available = false }
    result.loopStartUs = available ? start.map { min(max(0, $0), duration - 1) } : nil
    result.loopEndUs = available ? end.map { min(max(0, $0), duration) } : nil
    result.loopEnabled = value.loopEnabled && available && end != nil
    return result
  }
  private func samePicture(_ a: ComparisonPictureDto?, _ b: ComparisonPictureDto?) -> Bool {
    (a?.zoom ?? 1) == (b?.zoom ?? 1) && (a?.panX ?? 0) == (b?.panX ?? 0) && (a?.panY ?? 0) == (b?.panY ?? 0)
  }
}
