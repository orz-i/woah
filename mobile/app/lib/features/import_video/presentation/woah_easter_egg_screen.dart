import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../app/theme.dart';
import '../../../core/metadata/woah_build_info.dart';

class WoahEasterEggScreen extends StatefulWidget {
  static const closeButtonKey = ValueKey('woah-easter-egg-close');

  final Future<WoahBuildInfo> Function()? buildInfoLoader;

  const WoahEasterEggScreen({super.key, this.buildInfoLoader});

  @override
  State<WoahEasterEggScreen> createState() => _WoahEasterEggScreenState();
}

class _WoahEasterEggScreenState extends State<WoahEasterEggScreen> {
  late final Future<WoahBuildInfo> _buildInfoFuture;

  @override
  void initState() {
    super.initState();
    _buildInfoFuture = (widget.buildInfoLoader ?? WoahBuildInfo.load).call();
  }

  @override
  Widget build(BuildContext context) {
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.dark,
        statusBarBrightness: Brightness.light,
        systemNavigationBarColor: AppTheme.warmBackground,
        systemNavigationBarIconBrightness: Brightness.dark,
        systemNavigationBarDividerColor: Colors.transparent,
        systemStatusBarContrastEnforced: false,
        systemNavigationBarContrastEnforced: false,
      ),
      child: Scaffold(
        backgroundColor: AppTheme.warmBackground,
        body: Stack(
          children: [
            const Positioned(
              top: -120,
              right: -90,
              child: _AmbientOrb(size: 300, opacity: 0.12),
            ),
            const Positioned(
              left: -130,
              bottom: 90,
              child: _AmbientOrb(size: 280, opacity: 0.08),
            ),
            SafeArea(
              child: SingleChildScrollView(
                physics: const BouncingScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(24, 32, 24, 132),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Text(
                      'Woah',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: AppTheme.warmTextPrimary,
                        fontSize: 40,
                        height: 1,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 8,
                      ),
                    ),
                    const SizedBox(height: 10),
                    const Text(
                      '记录舞动，也保护舞动的人',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: AppTheme.warmTextMuted,
                        fontSize: 12,
                        letterSpacing: 2.4,
                      ),
                    ),
                    const SizedBox(height: 34),
                    FutureBuilder<WoahBuildInfo>(
                      future: _buildInfoFuture,
                      builder: (context, snapshot) {
                        return _BuildIdentityCard(info: snapshot.data);
                      },
                    ),
                    const SizedBox(height: 28),
                    const _WordCloud(),
                    const SizedBox(height: 28),
                    const Text(
                      'LOCAL FIRST  ·  PRIVATE BY DESIGN',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: AppTheme.warmTextMuted,
                        fontSize: 10,
                        fontWeight: FontWeight.w600,
                        letterSpacing: 1.8,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            SafeArea(
              child: Align(
                alignment: Alignment.bottomCenter,
                child: Padding(
                  padding: const EdgeInsets.only(bottom: 18),
                  child: Semantics(
                    button: true,
                    label: '关闭彩蛋',
                    child: Material(
                      color: Colors.transparent,
                      shape: const CircleBorder(),
                      child: InkWell(
                        key: WoahEasterEggScreen.closeButtonKey,
                        customBorder: const CircleBorder(),
                        onTap: () {
                          HapticFeedback.lightImpact();
                          Navigator.of(context).pop();
                        },
                        child: Ink(
                          width: 62,
                          height: 62,
                          decoration: const BoxDecoration(
                            gradient: AppTheme.coralActionGradient,
                            shape: BoxShape.circle,
                            boxShadow: [
                              BoxShadow(
                                color: Color(0x38F44848),
                                blurRadius: 20,
                                offset: Offset(0, 8),
                              ),
                            ],
                          ),
                          child: const Icon(
                            Icons.close_rounded,
                            color: Colors.white,
                            size: 30,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _BuildIdentityCard extends StatelessWidget {
  final WoahBuildInfo? info;

  const _BuildIdentityCard({required this.info});

  @override
  Widget build(BuildContext context) {
    final value = info;
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 18),
      decoration: BoxDecoration(
        color: AppTheme.warmSurface.withValues(alpha: 0.90),
        borderRadius: BorderRadius.circular(26),
        border: Border.all(color: AppTheme.warmBorder),
        boxShadow: const [
          BoxShadow(
            color: Color(0x10000000),
            blurRadius: 28,
            offset: Offset(0, 10),
          ),
        ],
      ),
      child: Column(
        children: [
          const Text(
            'CREATED BY',
            style: TextStyle(
              color: AppTheme.warmTextMuted,
              fontSize: 10,
              fontWeight: FontWeight.w700,
              letterSpacing: 2.2,
            ),
          ),
          const SizedBox(height: 7),
          const Text(
            WoahBuildInfo.authorName,
            style: TextStyle(
              color: AppTheme.warmTextPrimary,
              fontSize: 22,
              fontWeight: FontWeight.w700,
              letterSpacing: 1.1,
            ),
          ),
          const SizedBox(height: 20),
          const Divider(height: 1, color: AppTheme.warmBorder),
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: _BuildDatum(
                  label: 'VERSION',
                  value: value == null ? '—' : 'v${value.versionName}',
                ),
              ),
              Expanded(
                child: _BuildDatum(
                  label: 'BUILD',
                  value: value == null ? '—' : '#${value.buildNumber}',
                ),
              ),
              Expanded(
                child: _BuildDatum(
                  label: 'TYPE',
                  value: value?.buildType.toUpperCase() ?? '—',
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
            decoration: BoxDecoration(
              color: AppTheme.warmSurfaceSoft,
              borderRadius: BorderRadius.circular(14),
            ),
            child: Row(
              children: [
                const Icon(
                  Icons.commit_rounded,
                  size: 17,
                  color: AppTheme.coral,
                ),
                const SizedBox(width: 9),
                const Text(
                  'COMMIT',
                  style: TextStyle(
                    color: AppTheme.warmTextMuted,
                    fontSize: 10,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 1.3,
                  ),
                ),
                const Spacer(),
                Text(
                  value?.shortCommit ?? 'loading',
                  style: const TextStyle(
                    color: AppTheme.warmTextPrimary,
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    fontFamily: 'monospace',
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _BuildDatum extends StatelessWidget {
  final String label;
  final String value;

  const _BuildDatum({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Text(
          label,
          style: const TextStyle(
            color: AppTheme.warmTextMuted,
            fontSize: 9,
            fontWeight: FontWeight.w700,
            letterSpacing: 1.4,
          ),
        ),
        const SizedBox(height: 6),
        Text(
          value,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            color: AppTheme.warmTextPrimary,
            fontSize: 12,
            fontWeight: FontWeight.w700,
          ),
        ),
      ],
    );
  }
}

class _WordCloud extends StatelessWidget {
  const _WordCloud();

  static const words = <(String, double, FontWeight, Color)>[
    ('DANCE', 27, FontWeight.w800, AppTheme.coralStrong),
    ('PRIVACY', 20, FontWeight.w700, AppTheme.warmTextPrimary),
    ('LOCAL FIRST', 15, FontWeight.w600, AppTheme.warmTextSecondary),
    ('LiteRT', 18, FontWeight.w700, AppTheme.coral),
    ('Tracking', 16, FontWeight.w600, AppTheme.warmTextPrimary),
    ('FACE', 13, FontWeight.w700, AppTheme.warmTextMuted),
    ('FULL BODY', 17, FontWeight.w700, AppTheme.warmTextPrimary),
    ('MASK', 14, FontWeight.w700, AppTheme.coralStrong),
    ('Flutter', 19, FontWeight.w700, AppTheme.warmTextPrimary),
    ('Android', 14, FontWeight.w600, AppTheme.warmTextSecondary),
    ('OpenGL ES', 13, FontWeight.w600, AppTheme.warmTextMuted),
    ('YOLO', 19, FontWeight.w800, AppTheme.coral),
    ('SEGMENTATION', 12, FontWeight.w600, AppTheme.warmTextSecondary),
    ('DETERMINISTIC', 16, FontWeight.w700, AppTheme.warmTextPrimary),
    ('H.264', 13, FontWeight.w700, AppTheme.warmTextMuted),
    ('MOTION', 23, FontWeight.w800, AppTheme.coralStrong),
    ('Woah', 18, FontWeight.w700, AppTheme.warmTextPrimary),
  ];

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 22),
      decoration: BoxDecoration(
        color: AppTheme.warmSurfaceSoft.withValues(alpha: 0.64),
        borderRadius: BorderRadius.circular(28),
        border: Border.all(color: AppTheme.warmBorder.withValues(alpha: 0.7)),
      ),
      child: Wrap(
        alignment: WrapAlignment.center,
        crossAxisAlignment: WrapCrossAlignment.center,
        spacing: 13,
        runSpacing: 11,
        children: [
          for (final word in words)
            Text(
              word.$1,
              style: TextStyle(
                color: word.$4,
                fontSize: word.$2,
                height: 1,
                fontWeight: word.$3,
                letterSpacing: word.$2 >= 20 ? 1.4 : 0.5,
              ),
            ),
        ],
      ),
    );
  }
}

class _AmbientOrb extends StatelessWidget {
  final double size;
  final double opacity;

  const _AmbientOrb({required this.size, required this.opacity});

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: RadialGradient(
            colors: [
              AppTheme.coral.withValues(alpha: opacity),
              AppTheme.coral.withValues(alpha: 0),
            ],
          ),
        ),
      ),
    );
  }
}
