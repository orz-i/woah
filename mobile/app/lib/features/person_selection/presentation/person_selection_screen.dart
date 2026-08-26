import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:dance_domain/dance_domain.dart';
import 'person_selection_controller.dart';
import '../domain/person_selection_state.dart';
import 'widgets/person_card.dart';

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
      _pageController.animateToPage(
        page,
        duration: const Duration(milliseconds: 280),
        curve: Curves.easeInOut,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(personSelectionControllerProvider);
    final controller = ref.read(personSelectionControllerProvider.notifier);

    return Scaffold(
      appBar: AppBar(
        title: const Text('选择目标人物'),
        elevation: 0,
      ),
      body: SafeArea(
        child: _buildBody(context, state, controller),
      ),
      bottomNavigationBar: state.status == PersonSelectionStatus.ready
          ? _buildBottomBar(context, state, controller)
          : null,
    );
  }

  Widget _buildBody(
    BuildContext context,
    PersonSelectionState state,
    PersonSelectionController controller,
  ) {
    if (state.isAnalyzing) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const SizedBox(
              width: 56,
              height: 56,
              child: CircularProgressIndicator(strokeWidth: 3, color: Colors.white),
            ),
            const SizedBox(height: 24),
            Text(
              '正在智能识别画面人物...',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                  ),
            ),
            const SizedBox(height: 8),
            const Text(
              '已按画面位置从左到右智能排列',
              style: TextStyle(color: Colors.white60, fontSize: 13),
            ),
          ],
        ),
      );
    }

    if (state.status == PersonSelectionStatus.error) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.error_outline_rounded, size: 48, color: Colors.redAccent),
            const SizedBox(height: 16),
            Text(
              state.errorMessage ?? '分析失败',
              style: const TextStyle(color: Colors.redAccent),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: () => controller.analyzeProject(widget.project),
              child: const Text('重试分析'),
            ),
          ],
        ),
      );
    }

    final persons = state.persons;
    if (persons.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.person_off_outlined, size: 48, color: Colors.white54),
            const SizedBox(height: 16),
            const Text('首帧未检测到人物，请尝试其他视频'),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('返回重新选择视频'),
            ),
          ],
        ),
      );
    }

    final selectedCount = state.selectedPersonIds.length;
    final totalCount = persons.length;

    // Safety check on page bounds
    if (_currentPage >= totalCount) {
      _currentPage = totalCount - 1;
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // 1. Compact Top Bar (Selection count & Quick buttons)
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                decoration: BoxDecoration(
                  color: const Color(0xFF1E1E24),
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(color: Colors.white.withAlpha(25)),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.people_alt_rounded, size: 16, color: Colors.white70),
                    const SizedBox(width: 6),
                    Text(
                      '已选择 $selectedCount / $totalCount 位人物',
                      style: const TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.bold,
                        color: Colors.white,
                      ),
                    ),
                  ],
                ),
              ),
              Row(
                children: [
                  TextButton.icon(
                    style: TextButton.styleFrom(
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                    ),
                    onPressed: () => controller.selectAll(),
                    icon: const Icon(Icons.select_all_rounded, size: 16),
                    label: const Text('全选'),
                  ),
                  const SizedBox(width: 4),
                  TextButton.icon(
                    style: TextButton.styleFrom(
                      foregroundColor: Colors.white60,
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                    ),
                    onPressed: () => controller.deselectAll(),
                    icon: const Icon(Icons.deselect_rounded, size: 16),
                    label: const Text('清空'),
                  ),
                ],
              ),
            ],
          ),
        ),

        // 2. Main Showcase: Full Single-Viewport Carousel (PageView)
        Expanded(
          child: PageView.builder(
            controller: _pageController,
            itemCount: persons.length,
            onPageChanged: (index) {
              setState(() {
                _currentPage = index;
              });
            },
            itemBuilder: (context, index) {
              final p = persons[index];
              final isSelected = state.selectedPersonIds.contains(p.id);
              return PersonCard(
                person: p,
                isSelected: isSelected,
                onToggle: () => controller.togglePerson(p.id),
              );
            },
          ),
        ),

        // 3. Integrated Navigation & Pagination Bar
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 6.0),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              // Previous Arrow Button
              IconButton(
                style: IconButton.styleFrom(
                  backgroundColor: _currentPage > 0 ? const Color(0xFF1E1E24) : Colors.transparent,
                  disabledBackgroundColor: Colors.transparent,
                ),
                icon: Icon(
                  Icons.arrow_back_ios_new_rounded,
                  size: 18,
                  color: _currentPage > 0 ? Colors.white : Colors.white24,
                ),
                onPressed: _currentPage > 0
                    ? () => _scrollToPage(_currentPage - 1, totalCount)
                    : null,
              ),

              // Page Indicator Dots & Page Text
              Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    '第 ${_currentPage + 1} / $totalCount 位人物',
                    style: const TextStyle(
                      fontSize: 13,
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: List.generate(totalCount, (index) {
                      final isCurrent = index == _currentPage;
                      return AnimatedContainer(
                        duration: const Duration(milliseconds: 200),
                        margin: const EdgeInsets.symmetric(horizontal: 3),
                        width: isCurrent ? 20 : 6,
                        height: 6,
                        decoration: BoxDecoration(
                          color: isCurrent
                              ? Colors.white
                              : Colors.white24,
                          borderRadius: BorderRadius.circular(3),
                        ),
                      );
                    }),
                  ),
                ],
              ),

              // Next Arrow Button
              IconButton(
                style: IconButton.styleFrom(
                  backgroundColor: _currentPage < totalCount - 1 ? const Color(0xFF1E1E24) : Colors.transparent,
                  disabledBackgroundColor: Colors.transparent,
                ),
                icon: Icon(
                  Icons.arrow_forward_ios_rounded,
                  size: 18,
                  color: _currentPage < totalCount - 1 ? Colors.white : Colors.white24,
                ),
                onPressed: _currentPage < totalCount - 1
                    ? () => _scrollToPage(_currentPage + 1, totalCount)
                    : null,
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildBottomBar(
    BuildContext context,
    PersonSelectionState state,
    PersonSelectionController controller,
  ) {
    final hasSelection = state.selectedPersonIds.isNotEmpty;

    return Container(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 16),
      decoration: const BoxDecoration(
        color: Color(0xFF131316),
        border: Border(top: BorderSide(color: Color(0xFF2E2E34))),
      ),
      child: ElevatedButton.icon(
        onPressed: hasSelection
            ? () {
                final configured = controller.buildConfiguredProject();
                if (configured != null) {
                  context.push('/effect_editor', extra: configured);
                }
              }
            : null,
        icon: const Icon(Icons.tune_rounded, size: 20),
        label: Text(
          hasSelection
              ? '下一步：调节特效 (已选 ${state.selectedPersonIds.length} 人)'
              : '请至少选择一位人物',
          style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
        ),
        style: ElevatedButton.styleFrom(
          backgroundColor: Colors.white,
          foregroundColor: Colors.black,
          disabledBackgroundColor: Colors.white12,
          disabledForegroundColor: Colors.white38,
          padding: const EdgeInsets.symmetric(vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
    );
  }
}
