import 'dart:io';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:dance_domain/dance_domain.dart';
import 'person_selection_controller.dart';
import '../domain/person_selection_state.dart';

class PersonSelectionScreen extends ConsumerStatefulWidget {
  final DanceProject project;

  const PersonSelectionScreen({
    super.key,
    required this.project,
  });

  @override
  ConsumerState<PersonSelectionScreen> createState() =>
      _PersonSelectionScreenState();
}

class _PersonSelectionScreenState extends ConsumerState<PersonSelectionScreen> {
  late final PageController _pageController;
  int _currentPage = 0;

  @override
  void initState() {
    super.initState();
    _pageController = PageController(viewportFraction: 1.0);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref
          .read(personSelectionControllerProvider.notifier)
          .analyzeProject(widget.project);
    });
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  void _scrollToPage(int page, int totalCount) {
    if (page >= 0 && page < totalCount) {
      HapticFeedback.lightImpact();
      _pageController.animateToPage(
        page,
        duration: const Duration(milliseconds: 260),
        curve: Curves.easeOutCubic,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(personSelectionControllerProvider);
    final controller = ref.read(personSelectionControllerProvider.notifier);

    return Scaffold(
      backgroundColor: const Color(0xFF0A0A0C),
      resizeToAvoidBottomInset: false,
      body: Stack(
        children: [
          // Layer 0: 全屏多媒体主舞台 (左右滑动切人，点击任意区域切换选中)
          Positioned.fill(
            child: _buildStageContent(context, state, controller),
          ),

          // Layer 1: 顶部一体化极简导航栏 (绝对不与内容冲突)
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: SafeArea(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                child: _buildTopBar(context, state, controller),
              ),
            ),
          ),

          // Layer 2: 底部轻量操作控制栏
          if (state.status == PersonSelectionStatus.ready && state.persons.isNotEmpty)
            Positioned(
              bottom: 0,
              left: 0,
              right: 0,
              child: SafeArea(
                top: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
                  child: _buildBottomBar(context, state, controller),
                ),
              ),
            ),
        ],
      ),
    );
  }

  /// 全屏主舞台内容
  Widget _buildStageContent(
    BuildContext context,
    PersonSelectionState state,
    PersonSelectionController controller,
  ) {
    if (state.isAnalyzing) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(
              width: 44,
              height: 44,
              child: CircularProgressIndicator(strokeWidth: 2.8, color: Colors.white),
            ),
            const SizedBox(height: 20),
            Text(
              '智能识别人物中...',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w600,
                    color: Colors.white,
                  ),
            ),
          ],
        ),
      );
    }

    if (state.status == PersonSelectionStatus.error) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.error_outline_rounded, size: 48, color: Colors.redAccent),
              const SizedBox(height: 14),
              Text(
                state.errorMessage ?? '分析失败',
                style: const TextStyle(color: Colors.redAccent, fontSize: 13),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 18),
              OutlinedButton.icon(
                onPressed: () {
                  HapticFeedback.mediumImpact();
                  controller.analyzeProject(widget.project);
                },
                icon: const Icon(Icons.refresh_rounded, size: 16),
                label: const Text('重试识别'),
              ),
            ],
          ),
        ),
      );
    }

    final persons = state.persons;
    if (persons.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.person_off_outlined, size: 48, color: Colors.white38),
            const SizedBox(height: 14),
            const Text('首帧未检测到人物', style: TextStyle(color: Colors.white70)),
            const SizedBox(height: 16),
            OutlinedButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('返回重选'),
            ),
          ],
        ),
      );
    }

    return PageView.builder(
      controller: _pageController,
      itemCount: persons.length,
      physics: const BouncingScrollPhysics(),
      onPageChanged: (index) {
        HapticFeedback.selectionClick();
        setState(() {
          _currentPage = index;
        });
      },
      itemBuilder: (context, index) {
        final person = persons[index];
        final isSelected = state.selectedPersonIds.contains(person.id);
        final hasThumb = person.thumbnailPath.isNotEmpty && File(person.thumbnailPath).existsSync();
        final confPercent = (person.confidence * 100).toInt();

        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: () {
            HapticFeedback.selectionClick();
            controller.togglePerson(person.id);
          },
          child: Stack(
            fit: StackFit.expand,
            alignment: Alignment.center,
            children: [
              // 1. 人物大图完整居中展示
              Center(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 70, 24, 140),
                  child: hasThumb
                      ? Image.file(
                          File(person.thumbnailPath),
                          fit: BoxFit.contain,
                          gaplessPlayback: true,
                        )
                      : const Icon(Icons.person_rounded, size: 96, color: Colors.white24),
                ),
              ),

              // 2. 底部集成式状态指示胶囊 (图标 + 人物信息，点击即翻转，顶部零杂乱)
              Positioned(
                bottom: 108,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(22),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 200),
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                      decoration: BoxDecoration(
                        color: isSelected
                            ? Colors.white
                            : const Color(0xFF1B1B20).withAlpha(220),
                        borderRadius: BorderRadius.circular(22),
                        border: Border.all(
                          color: isSelected ? Colors.white : Colors.white24,
                          width: 1.2,
                        ),
                        boxShadow: [
                          if (isSelected)
                            BoxShadow(
                              color: Colors.white.withAlpha(60),
                              blurRadius: 16,
                              spreadRadius: 1,
                            ),
                        ],
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          // 仅纯图标状态
                          Icon(
                            isSelected ? Icons.check_circle_rounded : Icons.radio_button_unchecked_rounded,
                            size: 18,
                            color: isSelected ? Colors.black : Colors.white60,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            '人物 ${person.id}   •   $confPercent%   •   第 ${index + 1}/${persons.length} 位',
                            style: TextStyle(
                              color: isSelected ? Colors.black : Colors.white,
                              fontSize: 13,
                              fontWeight: FontWeight.w600,
                              letterSpacing: 0.2,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  /// 顶部一体化极简导航栏
  Widget _buildTopBar(
    BuildContext context,
    PersonSelectionState state,
    PersonSelectionController controller,
  ) {
    final selectedCount = state.selectedPersonIds.length;
    final totalCount = state.persons.length;
    final allSelected = selectedCount == totalCount && totalCount > 0;

    return ClipRRect(
      borderRadius: BorderRadius.circular(24),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
        child: Container(
          height: 48,
          padding: const EdgeInsets.symmetric(horizontal: 8),
          decoration: BoxDecoration(
            color: const Color(0xFF141418).withAlpha(180),
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: Colors.white.withAlpha(25)),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              // 返回
              IconButton(
                icon: const Icon(Icons.arrow_back_rounded, color: Colors.white, size: 20),
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                onPressed: () {
                  HapticFeedback.lightImpact();
                  context.pop();
                },
              ),

              // 标题与计数
              Text(
                totalCount > 0 ? '已选择 $selectedCount / $totalCount 位' : '选择保护人物',
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: Colors.white,
                  letterSpacing: 0.3,
                ),
              ),

              // 全选 / 清空 极简文字操作
              if (state.status == PersonSelectionStatus.ready && totalCount > 0)
                TextButton(
                  style: TextButton.styleFrom(
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                    visualDensity: VisualDensity.compact,
                    minimumSize: Size.zero,
                  ),
                  onPressed: () {
                    HapticFeedback.selectionClick();
                    if (allSelected) {
                      controller.deselectAll();
                    } else {
                      controller.selectAll();
                    }
                  },
                  child: Text(
                    allSelected ? '清空' : '全选',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: allSelected ? Colors.white70 : Colors.white,
                    ),
                  ),
                )
              else
                const SizedBox(width: 40),
            ],
          ),
        ),
      ),
    );
  }

  /// 底部精致控制栏 (平滑点指示器 + 优雅主操作按钮)
  Widget _buildBottomBar(
    BuildContext context,
    PersonSelectionState state,
    PersonSelectionController controller,
  ) {
    final totalCount = state.persons.length;
    final selectedCount = state.selectedPersonIds.length;
    final hasSelection = selectedCount > 0;

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        // 极简平滑页面点指示器
        if (totalCount > 1)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: List.generate(totalCount, (index) {
                final isCurrent = index == _currentPage;
                final isSelected = state.selectedPersonIds.contains(state.persons[index].id);
                return GestureDetector(
                  onTap: () => _scrollToPage(index, totalCount),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 200),
                    margin: const EdgeInsets.symmetric(horizontal: 3),
                    width: isCurrent ? 18 : 6,
                    height: 5,
                    decoration: BoxDecoration(
                      color: isCurrent
                          ? Colors.white
                          : (isSelected ? Colors.white.withAlpha(120) : Colors.white24),
                      borderRadius: BorderRadius.circular(3),
                    ),
                  ),
                );
              }),
            ),
          ),

        // 精致圆角主操作按钮
        ClipRRect(
          borderRadius: BorderRadius.circular(16),
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 12, sigmaY: 12),
            child: SizedBox(
              width: double.infinity,
              height: 50,
              child: ElevatedButton(
                onPressed: hasSelection
                    ? () async {
                        HapticFeedback.mediumImpact();
                        final configured = controller.buildConfiguredProject();
                        if (configured != null) {
                          final resultProj = await context.push<DanceProject>('/effect_editor', extra: configured);
                          if (resultProj != null) {
                            controller.updateProject(resultProj);
                          }
                        }
                      }
                    : null,
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.white,
                  foregroundColor: Colors.black,
                  disabledBackgroundColor: Colors.white.withAlpha(20),
                  disabledForegroundColor: Colors.white38,
                  elevation: 0,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    const Icon(Icons.tune_rounded, size: 18),
                    const SizedBox(width: 8),
                    Text(
                      hasSelection
                          ? '调节特效 ($selectedCount人)'
                          : '请选择人物',
                      style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(width: 4),
                    const Icon(Icons.arrow_forward_rounded, size: 16),
                  ],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}
