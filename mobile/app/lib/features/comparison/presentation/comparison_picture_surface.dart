import 'package:flutter/widgets.dart';
import 'package:dance_domain/dance_domain.dart';

/// Matches the native compositor's pane-relative zoom and screen-space pan.
class ComparisonPictureSurface extends StatelessWidget {
  const ComparisonPictureSurface({
    super.key,
    required this.picture,
    required this.child,
    required this.aspectRatio,
  });
  final ComparisonPicture picture;
  final Widget child;
  final double aspectRatio;
  @override
  Widget build(BuildContext context) => ClipRect(
    child: LayoutBuilder(
      builder: (context, bounds) {
        final fit = applyBoxFit(
          BoxFit.contain,
          Size(aspectRatio, 1),
          bounds.biggest,
        ).destination;
        final travel = picture.zoom == 1
            ? Offset.zero
            : Offset(
                (fit.width * picture.zoom - bounds.maxWidth).abs() / 2,
                (fit.height * picture.zoom - bounds.maxHeight).abs() / 2,
              );
        return Transform.translate(
          offset: Offset(picture.panX * travel.dx, picture.panY * travel.dy),
          child: Transform.scale(scale: picture.zoom, child: child),
        );
      },
    ),
  );
}
