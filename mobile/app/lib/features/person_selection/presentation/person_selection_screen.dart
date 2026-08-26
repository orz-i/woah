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
    _pageController = PageController(viewportFraction: 0.86);
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
        duration: const Duration(milliseconds: 300),
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
        title: const Text('人物选择 (Select Persons)'),
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 12.0),
          child: _buildBody(context, state, controller),
        ),
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
              child: CircularProgressIndicator(strokeWidth: 3),
            ),
            const SizedBox(height: 24),
            Text(
              '正在进行首帧 YOLO 分割与人物分析...',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
            ),
            const SizedBox(height: 8),
            const Text(
              '按从左到右排序并裁剪高清人物缩略图',
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
            const Icon(Icons.error_outline, size: 48, color: Colors.redAccent),
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
        // 1. Header & Quick Actions
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16.0),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '共检测到 $totalCount 位人物',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.bold,
                        ),
                  ),
                  const SizedBox(height: 2),
                  const Text(
                    '左右滑动或点击箭头切换人物',
                    style: TextStyle(fontSize: 12, color: Colors.white54),
                  ),
                ],
              ),
              Row(
                children: [
                  TextButton(
                    onPressed: () => controller.selectAll(),
                    child: const Text('全选'),
                  ),
                  TextButton(
                    onPressed: () => controller.deselectAll(),
                    child: const Text('清空'),
                  ),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: 8),

        // 2. Info Hint Banner
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16.0),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: Colors.deepPurpleAccent.withAlpha(25),
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: Colors.deepPurpleAccent.withAlpha(60)),
            ),
            child: Row(
              children: [
                const Icon(Icons.info_outline, size: 16, color: Colors.purpleAccent),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '已选择 $selectedCount / $totalCount 位人物进行匿名化/美颜',
                    style: const TextStyle(fontSize: 12, color: Colors.white70),
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),

        // 3. Single-viewport Swipeable Carousel (PageView)
        Expanded(
          child: Stack(
            alignment: Alignment.center,
            children: [
              PageView.builder(
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

              // Left Nav Arrow
              if (_currentPage > 0)
                Positioned(
                  left: 4,
                  child: Material(
                    color: Colors.black45,
                    shape: const CircleBorder(),
                    child: IconButton(
                      icon: const Icon(Icons.chevron_left, color: Colors.white, size: 28),
                      onPressed: () => _scrollToPage(_currentPage - 1, totalCount),
                    ),
                  ),
                ),

              // Right Nav Arrow
              if (_currentPage < totalCount - 1)
                Positioned(
                  right: 4,
                  child: Material(
                    color: Colors.black45,
                    shape: const CircleBorder(),
                    child: IconButton(
                      icon: const Icon(Icons.chevron_right, color: Colors.white, size: 28),
                      onPressed: () => _scrollToPage(_currentPage + 1, totalCount),
                    ),
                  ),
                ),
            ],
          ),
        ),

        const SizedBox(height: 8),

        // 4. Page Indicator Dots & Page Text
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              '${_currentPage + 1} / $totalCount',
              style: const TextStyle(fontSize: 13, color: Colors.white70, fontWeight: FontWeight.bold),
            ),
            const SizedBox(width: 12),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: List.generate(totalCount, (index) {
                final isCurrent = index == _currentPage;
                return AnimatedContainer(
                  duration: const Duration(milliseconds: 200),
                  margin: const EdgeInsets.symmetric(horizontal: 3),
                  width: isCurrent ? 16 : 6,
                  height: 6,
                  decoration: BoxDecoration(
                    color: isCurrent
                        ? Colors.deepPurpleAccent
                        : Colors.white24,
                    borderRadius: BorderRadius.circular(3),
                  ),
                );
              }),
            ),
          ],
        ),
        const SizedBox(height: 8),
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
      padding: const EdgeInsets.all(16),
      decoration: const BoxDecoration(
        color: Color(0xFF1D1B20),
        border: Border(top: BorderSide(color: Colors.white10)),
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
        icon: const Icon(Icons.tune_rounded),
        label: Text(
          hasSelection
              ? '下一步：特效参数调节 (已选 ${state.selectedPersonIds.length} 人)'
              : '请至少选择一位人物',
          style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
        ),
        style: ElevatedButton.styleFrom(
          backgroundColor: Colors.deepPurpleAccent,
          foregroundColor: Colors.white,
          disabledBackgroundColor: Colors.white12,
          padding: const EdgeInsets.symmetric(vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
    );
  }
}
