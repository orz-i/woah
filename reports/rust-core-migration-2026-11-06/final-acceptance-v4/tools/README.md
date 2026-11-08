# Final v4 local validation and safe report
Compiled native source: 28594280c86aadca5ec1b17101d3a461e8301745. Same frozen8 sources and62 matrix cases per platform. Detailed audits, original observations and review images stay in local ignored runtime directories on the originating host. Public delivery contains generic scripts, synthetic tests, aggregate counts and artifact hashes.

Run the existing tools/privacy/run_regression.py --suite all, then independent audits. audit-final-sequence.py waits for actual completed cohorts and creates audits/boards, without accepting semantics. Actually view every board, export alert, preview alert and topology candidate; record explicit SHA-bound decisions while preserving raw statuses.

Collect one completed reviewed cohort locally:
python collect-final-evidence.py REPOSITORY RAW_REPORT PRIVATE_CURATED_PLATFORM --platform android --suite legacy22
Repeat for original4k and focused13, on each platform. No report image/data directory should be staged for publication.

Additional known-risk review:
- review-known-effects.py RUN SOURCES KNOWN_FACE_SPEC DEST --expected-source COMMIT produces88 source/output pairs for11 complete4K non-solid solo modes, Source556..563.
- create-risk-targets.py SOURCE_VISION_JSONL TARGETS produces local source-only crop targets, independent of rendered output.
- review-continuous-risk-spans.py RUN SOURCES TARGETS DEST --expected-source COMMIT produces53 pairs for each closed immutable group movie, Source112..123 and176..216. Full original4k cohort requires12 group modes/636pairs.
- review-native-edge-previews.py RUN TARGETS DEST --expected-source COMMIT enlarges actual native source/protected PNG pairs at7400/8120ms using source bounds scaled to actual preview dimensions. Copy reviewed native-edge-preview-review locally. Both pairs require explicit manual decisions.
These tools create images only; every pair requires actual visual review. Copy reviewed supplemental folders to PRIVATE_CURATED_PLATFORM/known-effect-review and continuous-risk-review locally.

Validate one whole platform only after all3 cohorts and supplemental reviews:
python aggregate-final-evidence.py REPOSITORY PRIVATE_CURATED_BASE android
This emits counts-only PRIVATE_CURATED_BASE/acceptance-summary.json only after all62,61 actual movies,17491 decoded frames,186 native preview pairs, installed model bytes and SHA-bound local reviews validate. Run separately for iOS.

Transfer only the safe platform summaries, then combine:
python combine-safe-summaries.py ANDROID_SUMMARY IOS_SUMMARY SAFE_COMBINED_SUMMARY
Final combined output requires124 matrix cases,34982 decoded frames,372 preview pairs, identical source/model bytes and native code. Native bitmap/encoded-file bytes need not match between platforms. Simulator results do not establish iPhone hardware performance.

Actual interruption recovery in this report: Android V4 legacy22 completed and passed on the identical archived APK and eight frozen inputs. Preserve that accepted cohort. The incomplete V4 sequence had an ADB/external-files interruption; V5 was disconnected for charging and its app process was subsequently terminated by the system (o-stop40), retaining only12 closed cases. Neither interrupted sequence qualifies as complete27. Run the complete original4k suite then complete focused13 under a new V6 prefix, with no case filter or export-only options. All27 cases including >=25-frame cancel and662-frame immediate retry must run in one process; partial movies from old processes cannot fill its missing cases.
Use audit-final-sequence.py ... --suites original4k focused13 for these two full rerun cohorts. Collect legacy22 with --prefix shared_policy_final_v4 and the two new cohorts with --prefix shared_policy_final_v6. Collectors still require the exact frozen full cohort, same compiled source and platform binary; aggregate still requires all62. Until V6 finishes and validates, Android final acceptance remains pending.
When a supplemental review reuses prior visual judgments, all displayed source/output page SHA256 bytes, source/specification, frame/crop items and compiled source must match. Record the prior index/review hashes and retain local provenance. Different pixels require fresh actual visual review. Reusing a53-pair crop review never accepts a whole movie or an interrupted sequence.
