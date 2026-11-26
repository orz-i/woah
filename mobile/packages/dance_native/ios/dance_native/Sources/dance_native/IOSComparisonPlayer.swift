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
  private var cutoutRevision: UInt64 = 0
  private var cutoutTask: Task<IOSComparisonCutoutCache, Error>?
  private var archiveSaveTask:Task<Bool,Error>?
  private var archiveRestoreTask:Task<IOSComparisonCutoutRestored?,Error>?
  private var cutoutSelection:IOSComparisonCutoutSelection?
  private struct Repair:Equatable,Sendable {
    let reference:Bool
    let window:Range<Int64>
    let position:Int64
  }
  private var repair:Repair?
  private func completedCutoutResult(_ playback:ComparisonPlaybackDto)throws->ComparisonCutoutResultDto{
    guard let cache=cutoutCache else{throw IOSComparisonCutoutExportUnavailable()}
    return ComparisonCutoutResultDto(playback:playback,referenceVisible:Int64(cache.reference.visibleCount),
      referenceFrames:Int64(cache.reference.times.count),targetVisible:Int64(cache.target.visibleCount),
      targetFrames:Int64(cache.target.times.count))
  }
  private var archiving:Bool{archiveSaveTask != nil || archiveRestoreTask != nil}
  private func cancelArchive(){
    archiveSaveTask?.cancel();archiveRestoreTask?.cancel()
  }
  private func waitArchive()async{
    let save=archiveSaveTask,restore=archiveRestoreTask
    cancelArchive()
    _=try? await save?.value;_=try? await restore?.value
    archiveSaveTask=nil;archiveRestoreTask=nil
  }
  private var partialWork:(IOSComparisonCutoutAnalysis.Work,IOSComparisonCutoutAnalysis.Work)?
  func cutoutStatus()->ComparisonCutoutResultDto?{
    guard let (r,t)=partialWork,let window=cutoutWindow else{return nil}
    return ComparisonCutoutResultDto(playback:state(),referenceVisible:Int64(r.visible),referenceFrames:Int64(r.total),
      targetVisible:Int64(t.visible),targetFrames:Int64(t.total),analyzing:true,previewStartUs:window.lowerBound,
      previewEndUs:window.upperBound,referenceAnalyzed:Int64(r.analyzed),targetAnalyzed:Int64(t.analyzed))
  }
  private var cutoutCache: IOSComparisonCutoutCache?
  private var cutoutWindow: Range<Int64>?
  private var cutoutExportSuspended=false
  private var inspectionTask: Task<IOSComparisonCutoutAnalysis.Inspection, Error>?
  private var referenceInspection: IOSComparisonCutoutAnalysis.Inspection?
  private var targetInspection: IOSComparisonCutoutAnalysis.Inspection?
  var hasCutout: Bool { cutoutCache != nil || cutoutTask != nil || inspectionTask != nil || archiving }
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
    cutoutRevision &+= 1
    await waitArchive()
    let oldCutout = cutoutTask; oldCutout?.cancel()
    _ = try? await oldCutout?.value
    let oldInspection=inspectionTask; oldInspection?.cancel()
    _ = try? await oldInspection?.value
    inspectionTask=nil;referenceInspection=nil;targetInspection=nil
    cutoutTask = nil;partialWork=nil;cutoutCache = nil;cutoutWindow=nil;cutoutSelection=nil;repair=nil
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
    guard (cutoutTask == nil && inspectionTask == nil && !archiving) || partialWork != nil || command == .pause else { throw failure("人物分析进行中") }
    guard repair==nil || command == .pause else{throw failure("请先完成或取消局部纠错")}
    if command != .pause { referenceInspection=nil;targetInspection=nil }
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
    guard !hasCutout else { throw failure("请先退出抠像画布") }
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
    if partialWork != nil || archiving || repair != nil{return state()}
    try validate(offsetUs: next.offsetUs, scale: next.timeScale, width: width, height: height, speed: speed)
    if next.width == width && next.height == height && next.sideBySide == sideBySide { return state() }
    let position = positionUs(); let resume = wantsPlay
    next.width = width; next.height = height; next.sideBySide = sideBySide
    return try await rebuild(next, positionUs: position, sourcePositionUs: nil, resume: resume)
  }

  func setPractice(_ practice: ComparisonPracticeDto) async throws -> ComparisonPlaybackDto {
    try requireReady()
    guard cutoutTask == nil && inspectionTask == nil && !archiving else { throw failure("人物分析进行中") }
    guard var next = config, let composition else { throw failure("请先打开两段视频。") }
    try validatePractice(practice, durationUs: composition.durationUs)
    let previous = next.practice
    guard !hasCutout || previous == practice else { throw failure("请先退出抠像画布再调整选段或画面") }
    if previous != practice {referenceInspection=nil;targetInspection=nil}
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
    guard repair==nil,(cutoutTask == nil && inspectionTask == nil && !archiving) || partialWork != nil else { throw failure("人物分析进行中") }
    guard let composition, let index = referenceIndex else { throw failure("视频帧时间尚未就绪。") }
    let source = composition.referenceStartUs + positionUs()
    let next = index.adjacent(to: source, forward: forward,
      startUs: composition.referenceStartUs + (cutoutWindow?.lowerBound ?? 0),
      endUs: composition.referenceStartUs + (cutoutWindow?.upperBound ?? composition.durationUs)) ?? source
    wantsPlay = false
    try await seek(to: next - composition.referenceStartUs, resume: false)
    return state()
  }

  func inspectCutout(reference: Bool,seed value: ComparisonCutoutSeedDto) async throws -> ComparisonCutoutPreviewDto {
    try requireReady()
    guard (!hasCutout || (repair?.reference==reference && cutoutTask==nil && inspectionTask==nil && !archiving)),
          foreground,!wantsPlay,let config,
          (reference ? presentedReferenceUs : presentedTargetUs)==value.sourcePtsUs else {
      throw failure("请暂停并等待画面就绪")
    }
    let seed=IOSComparisonCutoutSeed(ptsUs:value.sourcePtsUs,x:Float(value.x),y:Float(value.y))
    let url=reference ? config.referenceURL : config.targetURL
    cutoutRevision &+= 1
    let revision=cutoutRevision
    let task=Task.detached(priority:.userInitiated) {
      try IOSComparisonCutoutAnalysis().inspect(url:url,seed:seed,isCancelled:{Task.isCancelled})
    }
    inspectionTask=task
    defer { if cutoutRevision==revision { inspectionTask=nil } }
    let inspected=try await task.value
    guard !closed,foreground,cutoutRevision==revision,
          (reference ? presentedReferenceUs : presentedTargetUs)==seed.ptsUs else { throw CancellationError() }
    if reference { referenceInspection=inspected } else { targetInspection=inspected }
    return ComparisonCutoutPreviewDto(sourcePtsUs:seed.ptsUs,png:FlutterStandardTypedData(bytes:inspected.png),
      width:Int64(inspected.width),height:Int64(inspected.height))
  }

  func prepareCutout(reference: ComparisonCutoutSeedDto,target: ComparisonCutoutSeedDto,layout: ComparisonCanvasLayoutDto?) async throws -> ComparisonCutoutResultDto {
    let requestedLayout = try layout.map(Self.canvasLayout)
    try requireReady()
    guard !hasCutout, foreground, !wantsPlay, let config, let composition, let referenceIndex, let targetIndex,
          presentedReferenceUs == reference.sourcePtsUs, presentedTargetUs == target.sourcePtsUs else {
      throw failure("请暂停并等待画面就绪")
    }
    let r = IOSComparisonCutoutSeed(ptsUs: reference.sourcePtsUs,x: Float(reference.x),y: Float(reference.y))
    let t = IOSComparisonCutoutSeed(ptsUs: target.sourcePtsUs,x: Float(target.x),y: Float(target.y))
    let rURL = config.referenceURL, tURL = config.targetURL
    let window = loopRange() ?? (0..<composition.durationUs)
    guard window.contains(positionUs()) else { throw failure("请在 A/B 选段内选人") }
    let anchorPosition=positionUs()
    let initial=max(window.lowerBound,anchorPosition-500_000)..<min(window.upperBound,anchorPosition+500_000)
    let origin=composition.referenceStartUs
    let start=origin+window.lowerBound,end=origin+window.upperBound-1
    let offset = config.offsetUs, scale = config.timeScale
    let rMirror = config.practice.mirrorTeacher, tMirror = config.practice.mirrorLearner
    let inspectedR=referenceInspection,inspectedT=targetInspection
    cutoutRevision &+= 1
    let revision = cutoutRevision
    let task = Task.detached(priority: .userInitiated) { [weak self] in
      let analyzer=IOSComparisonCutoutAnalysis()
      let previewStart=origin+initial.lowerBound,previewEnd=origin+initial.upperBound-1
      let a=try analyzer.begin(url:rURL,index:referenceIndex,start:start,end:end,
        firstPreview:previewStart,lastPreview:previewEnd,seed:r,reference:true,mirror:rMirror,
        inspection:inspectedR,isCancelled:{Task.isCancelled})
      let b=try analyzer.begin(url:tURL,index:targetIndex,
        start:IOSComparisonTimeCore.map(start,scale:scale,offset:offset),
        end:IOSComparisonTimeCore.map(end,scale:scale,offset:offset),
        firstPreview:IOSComparisonTimeCore.map(previewStart,scale:scale,offset:offset),
        lastPreview:IOSComparisonTimeCore.map(previewEnd,scale:scale,offset:offset),
        seed:t,reference:false,mirror:tMirror,inspection:inspectedT,isCancelled:{Task.isCancelled})
      if initial != window {
        guard let self else {throw CancellationError()}
        try await self.installPartial(a,b,initial:initial,origin:origin,offset:offset,scale:scale,
          layout:requestedLayout,position:anchorPosition,revision:revision)
      }
      let ra=try a.finish(),ta=try b.finish()
      return IOSComparisonCutoutCache(reference:ra,target:ta,referenceStartUs:origin,offsetUs:offset,timeScale:scale,window:window)
    }
    cutoutTask=task
    defer{if cutoutRevision==revision{cutoutTask=nil}}
    do{
      let cache=try await task.value
      guard !closed,foreground,cutoutRevision==revision,let current=self.config else{throw CancellationError()}
      let position=positionUs(),resume=wantsPlay
      cutoutCache=cache.withLayout(requestedLayout);cutoutWindow=window;partialWork=nil
      cutoutSelection=IOSComparisonCutoutSelection(reference:r,target:t)
      referenceInspection=nil;targetInspection=nil
      let state=try await rebuild(current,positionUs:position,sourcePositionUs:nil,resume:resume)
      guard foreground,cutoutRevision==revision else{throw CancellationError()}
      return ComparisonCutoutResultDto(playback:state,referenceVisible:Int64(cache.reference.visibleCount),
        referenceFrames:Int64(cache.reference.times.count),targetVisible:Int64(cache.target.visibleCount),
        targetFrames:Int64(cache.target.times.count))
    } catch {
      if !closed && cutoutRevision==revision{
        let position=positionUs()
        cutoutCache=nil;cutoutWindow=nil;partialWork=nil;cutoutSelection=nil
        if let current=self.config{_=try? await rebuild(current,positionUs:position,sourcePositionUs:nil,resume:false)}
      }
      throw error
    }
  }

  private func installPartial(_ r:IOSComparisonCutoutAnalysis.Work,_ t:IOSComparisonCutoutAnalysis.Work,
    initial:Range<Int64>,origin:Int64,offset:Int64,scale:Double,layout:IOSComparisonCanvasLayout?,
    position:Int64,revision:UInt64) async throws{
    guard !closed,foreground,cutoutRevision==revision,let current=config else{throw CancellationError()}
    cutoutCache=IOSComparisonCutoutCache(reference:r.preview,target:t.preview,referenceStartUs:origin,
      offsetUs:offset,timeScale:scale,window:initial,editedLayout:layout)
    cutoutWindow=initial;partialWork=(r,t)
    _=try await rebuild(current,positionUs:position,sourcePositionUs:nil,resume:false)
    guard !closed,foreground,cutoutRevision==revision else{throw CancellationError()}
  }

  func beginCutoutRepair(reference:Bool,startUs:Int64,endUs:Int64)async throws->ComparisonPlaybackDto{
    try requireReady()
    guard !closed,foreground,!wantsPlay,cutoutTask==nil,inspectionTask==nil,!archiving,!cutoutExportSuspended,
      repair==nil,cutoutCache != nil,cutoutSelection != nil,let window=cutoutWindow,let config,
      startUs>=window.lowerBound,endUs<=window.upperBound,endUs>startUs,endUs-startUs<=10_000_000,
      (startUs..<endUs).contains(positionUs()) else{throw IOSComparisonCutoutExportUnavailable()}
    let request=Repair(reference:reference,window:startUs..<endUs,position:positionUs())
    cutoutRevision &+= 1;let revision=cutoutRevision
    referenceInspection=nil;targetInspection=nil
    repair=request
    do{
      let value=try await rebuild(config,positionUs:request.position,sourcePositionUs:nil,resume:false)
      guard !closed,foreground,revision==cutoutRevision,repair==request else{throw CancellationError()}
      return value
    }catch{
      if !closed,revision==cutoutRevision{
        repair=nil;_=try? await rebuild(config,positionUs:request.position,sourcePositionUs:nil,resume:false)
      }
      throw error
    }
  }

  func repairCutout(seed value:ComparisonCutoutSeedDto,projectId:String)async throws->ComparisonCutoutResultDto{
    try requireReady()
    guard !closed,foreground,!wantsPlay,cutoutTask==nil,inspectionTask==nil,!archiving,!cutoutExportSuspended,
      let request=repair,let original=cutoutCache,let selection=cutoutSelection,let config,let composition,
      let index=request.reference ? referenceIndex:targetIndex,
      request.window.contains(positionUs()),
      (request.reference ? presentedReferenceUs:presentedTargetUs)==value.sourcePtsUs else{
      throw IOSComparisonCutoutExportUnavailable()
    }
    let seed=IOSComparisonCutoutSeed(ptsUs:value.sourcePtsUs,x:Float(value.x),y:Float(value.y))
    guard let inspection=request.reference ? referenceInspection:targetInspection,inspection.seed==seed else{
      throw IOSComparisonCutoutExportUnavailable()
    }
    let origin=composition.referenceStartUs,offset=config.offsetUs,scale=config.timeScale
    let r=config.referenceURL,t=config.targetURL,url=request.reference ? r:t
    let mirror=request.reference ? config.practice.mirrorTeacher:config.practice.mirrorLearner
    func sourceTime(_ position:Int64)throws->Int64{
      let us=origin+position
      if request.reference{return us}
      return IOSComparisonTimeCore.map(us,scale:scale,offset:offset)
    }
    let start=try sourceTime(request.window.lowerBound),end=try sourceTime(request.window.upperBound-1)
    let previewStart=try sourceTime(max(request.window.lowerBound,positionUs()-500_000))
    let previewEnd=try sourceTime(min(request.window.upperBound-1,positionUs()+499_999))
    let oldTrack=request.reference ? original.reference:original.target
    let expected=try IOSComparisonCutoutAnalysis.window(oldTrack.times,start:start,end:end,anchor:seed.ptsUs)
    guard expected.count<=600 else{throw IOSComparisonCutoutExportUnavailable()}
    let anchor=request.reference ? selection.reference.ptsUs:selection.target.ptsUs
    cutoutRevision &+= 1;let revision=cutoutRevision
    let task=Task.detached(priority:.userInitiated){()->IOSComparisonCutoutCache in
      let work=try IOSComparisonCutoutAnalysis().begin(url:url,index:index,start:start,end:end,
        firstPreview:previewStart,lastPreview:previewEnd,seed:seed,reference:request.reference,mirror:mirror,
        inspection:inspection,isCancelled:{Task.isCancelled})
      let patch=try work.finish()
      guard patch.exact(seed.ptsUs)?.layer != nil else{throw IOSComparisonCutoutExportUnavailable()}
      let merged=try oldTrack.replacing(patch,expected:expected,originalAnchor:anchor,current:{!Task.isCancelled})
      try original.verifySources(referenceURL:r,targetURL:t,current:{!Task.isCancelled})
      guard !Task.isCancelled else{throw CancellationError()}
      try IOSComparisonCutoutArchive.forget(projectId)
      return IOSComparisonCutoutCache(reference:request.reference ? merged:original.reference,
        target:request.reference ? original.target:merged,referenceStartUs:original.referenceStartUs,
        offsetUs:original.offsetUs,timeScale:original.timeScale,window:original.window,editedLayout:original.editedLayout)
    }
    cutoutTask=task
    defer{if cutoutRevision==revision{cutoutTask=nil}}
    let replacement=try await task.value
    guard !closed,foreground,cutoutRevision==revision,repair==request else{throw CancellationError()}
    cutoutCache=replacement;repair=nil
    do{
      let playback=try await rebuild(config,positionUs:request.position,sourcePositionUs:nil,resume:false)
      guard !closed,foreground,cutoutRevision==revision else{throw CancellationError()}
      referenceInspection=nil;targetInspection=nil
      return try completedCutoutResult(playback)
    }catch{
      if !closed,cutoutRevision==revision {
        cutoutCache=original;repair=request
        _=try? await rebuild(config,positionUs:request.position,sourcePositionUs:nil,resume:false)
      }
      throw error
    }
  }

  func cancelCutoutRepair()async throws->ComparisonCutoutResultDto{
    guard let request=repair else{return try completedCutoutResult(state())}
    guard let config else{throw IOSComparisonCutoutExportUnavailable()}
    cutoutRevision &+= 1;let revision=cutoutRevision
    let work=cutoutTask,inspect=inspectionTask
    work?.cancel();inspect?.cancel()
    _=try? await work?.value;_=try? await inspect?.value
    guard !closed,revision==cutoutRevision,repair==request else{throw CancellationError()}
    cutoutTask=nil;inspectionTask=nil;referenceInspection=nil;targetInspection=nil;repair=nil
    let value=try await rebuild(config,positionUs:request.position,sourcePositionUs:nil,resume:false)
    return try completedCutoutResult(value)
  }

  nonisolated private static func archiveKey(reference:URL,target:URL,origin:Int64,offset:Int64,
    scale:Double,window:Range<Int64>,mirrorR:Bool,mirrorT:Bool,current:()->Bool)throws->IOSComparisonCutoutArchiveKey{
    guard let model=IOSModelResources.yoloModelURL(),let executable=Bundle.main.executableURL else{
      throw IOSComparisonCutoutExportUnavailable()
    }
    return IOSComparisonCutoutArchiveKey(origin:origin,offset:offset,scale:scale,window:window,
      mirrorR:mirrorR,mirrorT:mirrorT,
      referenceDigest:try IOSComparisonCutoutFingerprint.read(reference,current:current),
      targetDigest:try IOSComparisonCutoutFingerprint.read(target,current:current),
      modelDigest:try IOSComparisonCutoutFingerprint.read(model,current:current),
      buildDigest:try IOSComparisonCutoutFingerprint.read(executable,current:current))
  }

  func saveCutout(projectId:String)async throws->Bool{
    guard !closed,foreground,!archiving,cutoutTask==nil,!cutoutExportSuspended,repair==nil,
      let cache=cutoutCache,let window=cutoutWindow,let selection=cutoutSelection,let config else{
      throw IOSComparisonCutoutExportUnavailable()
    }
    if !cache.complete{return false}
    let r=config.referenceURL,t=config.targetURL,origin=cache.referenceStartUs
    let offset=config.offsetUs,scale=config.timeScale,mr=config.practice.mirrorTeacher,mt=config.practice.mirrorLearner
    cutoutRevision &+= 1;let revision=cutoutRevision
    let task=Task.detached(priority:.utility){
      let key=try Self.archiveKey(reference:r,target:t,origin:origin,offset:offset,scale:scale,window:window,
        mirrorR:mr,mirrorT:mt,current:{!Task.isCancelled})
      return try IOSComparisonCutoutArchive.save(projectId,key:key,selection:selection,cache:cache,current:{!Task.isCancelled})
    }
    archiveSaveTask=task
    defer{if cutoutRevision==revision{archiveSaveTask=nil}}
    return try await task.value
  }

  func restoreCutout(projectId:String,layout:ComparisonCanvasLayoutDto?)async throws->ComparisonCutoutResultDto?{
    try requireReady()
    guard !closed,foreground,!wantsPlay,!hasCutout,let config,let composition,
      let referenceIndex,let targetIndex else{throw IOSComparisonCutoutExportUnavailable()}
    let requested=try layout.map(Self.canvasLayout)
    let window=loopRange() ?? (0..<composition.durationUs),origin=composition.referenceStartUs
    let r=config.referenceURL,t=config.targetURL,offset=config.offsetUs,scale=config.timeScale
    let mr=config.practice.mirrorTeacher,mt=config.practice.mirrorLearner
    cutoutRevision &+= 1;let revision=cutoutRevision
    let task=Task.detached(priority:.userInitiated){()->IOSComparisonCutoutRestored? in
      guard try IOSComparisonCutoutArchive.exists(projectId) else{return nil}
      let current={!Task.isCancelled}
      let key=try Self.archiveKey(reference:r,target:t,origin:origin,offset:offset,scale:scale,window:window,
        mirrorR:mr,mirrorT:mt,current:current)
      let start=origin+window.lowerBound,end=origin+window.upperBound-1
      let restored=try IOSComparisonCutoutArchive.restore(projectId,key:key,referenceTimes:{seed in
        try IOSComparisonCutoutAnalysis.window(referenceIndex.presentationTimesUs,start:start,end:end,anchor:seed.ptsUs)
      },targetTimes:{seed in
        try IOSComparisonCutoutAnalysis.window(targetIndex.presentationTimesUs,
          start:IOSComparisonTimeCore.map(start,scale:scale,offset:offset),
          end:IOSComparisonTimeCore.map(end,scale:scale,offset:offset),anchor:seed.ptsUs)
      },current:current)
      guard let restored else{return nil}
      guard try IOSComparisonCutoutFingerprint.read(r,current:current)==key.referenceDigest,
            try IOSComparisonCutoutFingerprint.read(t,current:current)==key.targetDigest else{
        try IOSComparisonCutoutArchive.forget(projectId);return nil
      }
      return restored
    }
    archiveRestoreTask=task
    defer{if cutoutRevision==revision{archiveRestoreTask=nil}}
    guard let restored=try await task.value else{return nil}
    guard !closed,foreground,cutoutRevision==revision else{throw CancellationError()}
    let position=min(max(positionUs(),window.lowerBound),window.upperBound-1)
    cutoutCache=restored.cache.withLayout(requested);cutoutWindow=window;cutoutSelection=restored.selection
    referenceInspection=nil;targetInspection=nil
    do {
      let playback=try await rebuild(config,positionUs:position,sourcePositionUs:nil,resume:false)
      guard !closed,foreground,cutoutRevision==revision else{throw CancellationError()}
      return ComparisonCutoutResultDto(playback:playback,referenceVisible:Int64(restored.cache.reference.visibleCount),
        referenceFrames:Int64(restored.cache.reference.times.count),targetVisible:Int64(restored.cache.target.visibleCount),
        targetFrames:Int64(restored.cache.target.times.count))
    } catch {
      if !closed && cutoutRevision==revision{
        cutoutCache=nil;cutoutWindow=nil;cutoutSelection=nil
        _=try? await rebuild(config,positionUs:position,sourcePositionUs:nil,resume:false)
      }
      throw error
    }
  }

  private static func canvasLayout(_ value: ComparisonCanvasLayoutDto) throws -> IOSComparisonCanvasLayout {
    func layer(_ v:ComparisonCanvasLayerDto)->IOSComparisonCanvasLayer {
      IOSComparisonCanvasLayer(x:v.x,y:v.y,height:v.height,opacity:v.opacity,mirror:v.mirror)
    }
    let layout=IOSComparisonCanvasLayout(reference:layer(value.reference),target:layer(value.target),
      targetOnTop:value.targetOnTop,background:value.background)
    guard layout.valid else { throw IOSComparisonModelError.invalidRange }
    return layout
  }

  func setCutoutLayout(_ value: ComparisonCanvasLayoutDto) async throws -> ComparisonPlaybackDto {
    try requireReady()
    guard foreground,!wantsPlay,cutoutTask==nil,inspectionTask==nil,!archiving,repair==nil,
      let old=cutoutCache,let config else { throw failure("请暂停画布后再调整") }
    let layout=try Self.canvasLayout(value)
    let position=positionUs()
    cutoutRevision &+= 1
    let revision=cutoutRevision
    cutoutCache=old.withLayout(layout)
    // AVFoundation caches composed frames. Rebuild the composition with a
    // frozen geometry snapshot and the SAME analyzed source cache.
    do {
      let state=try await rebuild(config,positionUs:position,sourcePositionUs:nil,resume:false)
      guard cutoutRevision==revision else {throw CancellationError()}
      return state
    } catch {
      if !closed && cutoutRevision==revision {
        cutoutCache=old
        _ = try? await rebuild(config,positionUs:position,sourcePositionUs:nil,resume:false)
      }
      throw error
    }
  }

  func freezeCutoutExport(_ spec:IOSComparisonExportSpec) throws -> IOSComparisonCutoutCache {
    try requireReady()
    guard foreground,!wantsPlay,cutoutTask==nil,inspectionTask==nil,!archiving,!cutoutExportSuspended,repair==nil,
      let cache=cutoutCache,let config,let window=cutoutWindow,spec.isCutout,cache.complete,
      window==(spec.startUs..<spec.endUs),spec.offsetUs==config.offsetUs,spec.timeScale==config.timeScale,
      try IOSComparisonFiles.resolve(spec.teacherURI)==config.referenceURL,
      try IOSComparisonFiles.resolve(spec.learnerURI)==config.targetURL else {throw IOSComparisonCutoutExportUnavailable()}
    return cache
  }
  func suspendCutoutForExport() throws {
    guard cutoutCache != nil,!closed else {throw IOSComparisonCutoutExportUnavailable()}
    if cutoutExportSuspended {return}
    pendingUs=positionUs();wantsPlay=false;ready=false;cutoutExportSuspended=true
    generation &+= 1;seekRevision &+= 1
    releasePlayer();pixels.store(nil);presentedReferenceUs=nil;presentedTargetUs=nil
  }
  func resumeCutoutPreview() async throws -> ComparisonPlaybackDto {
    guard !closed,cutoutCache != nil,let config else {throw IOSComparisonCutoutExportUnavailable()}
    guard cutoutExportSuspended else {return state()}
    cutoutExportSuspended=false
    return try await rebuild(config,positionUs:pendingUs,sourcePositionUs:nil,resume:false)
  }

  func clearCutout() async throws -> ComparisonPlaybackDto {
    cutoutRevision &+= 1
    let revision = cutoutRevision, oldTask = cutoutTask, oldInspection=inspectionTask
    oldTask?.cancel();oldInspection?.cancel()
    await waitArchive()
    _ = try? await oldTask?.value
    _ = try? await oldInspection?.value
    guard cutoutRevision == revision else { return state() }
    inspectionTask=nil;referenceInspection=nil;targetInspection=nil
    cutoutTask = nil;partialWork=nil
    guard cutoutCache != nil else { return state() }
    cutoutCache = nil;cutoutWindow=nil;cutoutSelection=nil;repair=nil;cutoutExportSuspended=false
    guard !closed, let config else { return state() }
    return try await rebuild(config,positionUs: positionUs(),sourcePositionUs: nil,resume: false)
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
    cutoutRevision &+= 1; cutoutTask?.cancel(); cutoutTask = nil;partialWork=nil; cutoutCache = nil;cutoutWindow=nil;repair=nil;cutoutSelection=nil; inspectionTask?.cancel();inspectionTask=nil;referenceInspection=nil;targetInspection=nil
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
        gap: 8, sideBySide: next.sideBySide, practice: next.practice,cutout: repair==nil ? cutoutCache:nil)
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
        if !(error is CancellationError) { playbackError = IOSDiagnosticPrivacy.message(code: nil, stage: .playback) }
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
    let target = min(max(cutoutWindow?.lowerBound ?? 0, requested), max(0, (cutoutWindow?.upperBound ?? composition.durationUs) - 1))
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
        if !(error is CancellationError) { playbackError = IOSDiagnosticPrivacy.message(code: nil, stage: .playback) }
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
    if partialWork != nil,let cutoutWindow{return cutoutWindow}
    guard let practice = config?.practice, practice.loopEnabled,
          let start = practice.loopStartUs, let end = practice.loopEndUs,
          let duration = composition?.durationUs, start >= 0, end > start, end <= duration else { return nil }
    return start..<end
  }

  fileprivate func displayTick(_ link: CADisplayLink) {
    guard !closed, ready, !seeking else { return }
    if item?.status == .failed {
      pendingUs = positionUs(); wantsPlay = false; player?.pause()
      playbackError = "视频暂时无法播放。"
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
    let targetTime = IOSComparisonTimeCore.map(sourceTime, scale: config.timeScale, offset: config.offsetUs)
    presentedTargetUs = targetTime.flatMap { targetIndex?.frame(atOrBefore: $0) }
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
      catch { if current == self.generation && !(error is CancellationError) { self.playbackError = IOSDiagnosticPrivacy.message(code: nil, stage: .playback) } }
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
    cancelArchive();cutoutTask?.cancel();inspectionTask?.cancel();referenceInspection=nil;targetInspection=nil
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
