import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
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
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref
          .read(personSelectionControllerProvider.notifier)
          .analyzeProject(widget.project);
    });
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
          padding: const EdgeInsets.all(16.0),
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

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Header & Quick Actions
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '检测到 $totalCount 位人物',
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                ),
                const SizedBox(height: 2),
                const Text(
                  '已按画面位置从左至右排序编号',
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
        const Divider(height: 20),

        // Hint Banner
        Container(
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
              Text(
                '已选择 $selectedCount / $totalCount 人物将应用遮挡/美颜/拉腿特效',
                style: const TextStyle(fontSize: 12, color: Colors.white70),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),

        // Person Cards Grid
        Expanded(
          child: GridView.builder(
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 2,
              mainAxisSpacing: 12,
              crossAxisSpacing: 12,
              childAspectRatio: 0.78,
            ),
            itemCount: persons.length,
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
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    content: Text(
                      '已保存项目 ${configured?.id} 的人物选择 (${state.selectedPersonIds.toList()})，准备进入 Phase 4 特效编辑',
                    ),
                  ),
                );
              }
            : null,
        icon: const Icon(Icons.tune_rounded),
        label: Text(
          hasSelection
              ? '下一步：特效参数编辑 (已选 ${state.selectedPersonIds.length} 人)'
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
