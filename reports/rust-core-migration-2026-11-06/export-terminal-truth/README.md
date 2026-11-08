# Export cancellation terminal truth

A cancellation request can arrive after FrameKernel has sealed a successful
publication. The Flutter controller previously discarded the native terminal
and always displayed cancelled after the RPC returned. It now queries the
actual drained job, verifies its ID and terminal state, and renders that result,
including the committed output. Missing, mismatched or nonterminal status stays
retryable and cannot be reported as successful cancellation.

The existing pending-start, disposal, listener, retry and error behavior remains
covered. Native test fakes now supply the query contract. New barrier tests
cover completion/failure during cancellation, sealed early completion,
nonterminal/wrong-ID responses and query failure followed by retry.

Validation: all 277 Flutter application tests and flutter analyze passed on
Windows. The initial broader run exposed missing query implementations in
existing UI test fakes; those were corrected before the passing full run.
Log hashes and final summaries are recorded in verification.json. No layout,
branding or visual design changes. This is a stage gate, not final device
acceptance; the Android/iOS 62-case regression remains outstanding.
