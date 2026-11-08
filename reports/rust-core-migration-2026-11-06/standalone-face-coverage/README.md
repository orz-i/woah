# Standalone face coverage: 4K tail diagnostic

In the iOS simulator, an independently detected face could be rendered without
its usual sampling margin when no body track owned it. The full-length group
4K face-only solid export reproduced the exposed lower chin at source frame
578. A separate export from the same binary and material reproduced the last
six decoded frames exactly. A local observation trace matched the unpadded
standalone face region; the observations and original pixels stay local.

Commit `14f95fe1cedcf638e9c59855a378bcd83623aa76` routes this existing iOS
rendering case through the shared FrameKernel margin. It does not change
detection, ownership, track lifetime, or selected-person exemptions. Android
continues using the same sampling operation for its existing owned-face path.

The new simulator export decoded all 581 frames at 3840 × 2160. Inspection
of all six consecutive tail frames confirmed the previous chin contour was
covered. All five original native preview pairs passed the timing and
coverage checks. The one independent face-box candidate included ear and neck;
the one topology candidate was an arm seam between two masks. Both were
reviewed against actual output. No raw privacy evidence is included here.

The simulator's current detector cache was reused under the unchanged
observation contract. Tracking, face geometry, rendering and encoding ran
again. [The diagnostic receipt](ios-targeted-summary.json) binds this one
verified output. It is a targeted diagnostic and does not count toward the
complete 62-case acceptance on either platform.
