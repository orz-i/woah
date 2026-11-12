#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint dance_native.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'dance_native'
  s.version          = '0.0.1'
  s.summary          = 'Native iOS video processing bridge for Woah.'
  s.description      = <<-DESC
Native iOS media, rendering, and on-device inference bridge for Woah.
                       DESC
  s.homepage         = 'http://example.com'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Your Company' => 'email@example.com' }
  s.source           = { :path => '.' }
  s.source_files = 'dance_native/Sources/dance_native/**/*.swift'
  # TensorFlowLiteSwift ships a statically linked binary. Mark the Flutter
  # plugin pod itself static as well so CocoaPods does not try to build a
  # dynamic dance_native framework with a transitive static binary dependency.
  s.static_framework = true
  s.libraries = 'frame_kernel', 'iconv'
  s.user_target_xcconfig = {
    'LIBRARY_SEARCH_PATHS' => '$(inherited) "$(PODS_CONFIGURATION_BUILD_DIR)/dance_native/FrameKernel"',
    # Dart's synchronous timeline calls use these same C ABI symbols. Retain
    # them in the final executable even when no Swift call uses an entry point.
    'OTHER_LDFLAGS' => '$(inherited) -Wl,-export_dynamic -Wl,-u,_frame_kernel_time_value -Wl,-u,_frame_kernel_time_inverse -Wl,-u,_frame_kernel_time_anchors -Wl,-u,_frame_kernel_time_overlap'
  }
  s.dependency 'Flutter'
  # The first-party general Swift runtime currently available through CocoaPods
  # is TensorFlowLiteSwift. This compatibility bridge is intentionally isolated
  # behind IOSYoloRunner so it can move to LiteRT's newer runtime API without
  # changing the Flutter/Pigeon surface once a suitable Swift artifact exists.
  s.dependency 'TensorFlowLiteSwift/CoreML', '2.17.0'
  s.dependency 'TensorFlowLiteSwift/Metal', '2.17.0'
  s.platform = :ios, '17.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386',
    'SWIFT_INCLUDE_PATHS' => '$(inherited) "$(PODS_ROOT)/../../../../crates/frame-kernel/include"',
    'LIBRARY_SEARCH_PATHS' => '$(inherited) "$(BUILT_PRODUCTS_DIR)/FrameKernel"',
    'OTHER_LDFLAGS' => '$(inherited) -lframe_kernel -liconv'
  }
  s.script_phase = {
    :name => 'Build FrameKernel',
    :execution_position => :before_compile,
    :script => 'set -eu; python3 "${PODS_ROOT}/../../../../tools/rust/build_xcode.py"',
    # Resolve Flutter's plugin symlink before globbing. Xcode normalizes input
    # paths lexically, so its paths must start from the real Pods directory.
    :input_files => Dir.glob('{src/**/*.rs,include/*,README.md}', :base => File.expand_path('../../../../crates/frame-kernel', File.realpath(__dir__))).sort.map { |path|
      '$(PODS_ROOT)/../../../../crates/frame-kernel/' + path
    } +
      ['$(PODS_ROOT)/../../../../crates/frame-kernel/Cargo.toml', '$(PODS_ROOT)/../../../../crates/frame-kernel/Cargo.lock',
       '$(PODS_ROOT)/../../../../rust-toolchain.toml',
       '$(PODS_ROOT)/../../../../tools/rust/build_xcode.py',
       '$(PODS_ROOT)/../../../../tools/rust/run.py'],
    :output_files => ['$(BUILT_PRODUCTS_DIR)/FrameKernel/libframe_kernel.a']
  }
  s.swift_version = '5.0'

  # If your plugin requires a privacy manifest, for example if it uses any
  # required reason APIs, update the PrivacyInfo.xcprivacy file to describe your
  # plugin's privacy impact, and then uncomment this line. For more information,
  # see https://developer.apple.com/documentation/bundleresources/privacy_manifest_files
  s.resource_bundles = {
    'dance_native_privacy' => ['dance_native/Sources/dance_native/PrivacyInfo.xcprivacy'],
    'dance_native_models' => ['dance_native/Sources/dance_native/Resources/InferenceAssets/**/*'],
    'dance_native_phase5' => ['dance_native/Sources/dance_native/Resources/GoldenTraces/**/*']
  }
end
