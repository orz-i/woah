import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:dance_domain/dance_domain.dart';

class PersonCard extends StatelessWidget {
  final PersonTrack person;
  final bool isSelected;
  final VoidCallback onToggle;

  const PersonCard({
    super.key,
    required this.person,
    required this.isSelected,
    required this.onToggle,
  });

  @override
  Widget build(BuildContext context) {
    final confPercent = (person.confidence * 100).toInt();

    return GestureDetector(
      onTap: () {
        HapticFeedback.selectionClick();
        onToggle();
      },
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOutCubic,
        margin: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 8.0),
        decoration: BoxDecoration(
          color: isSelected
              ? const Color(0xFF1E1E23)
              : const Color(0xFF131316),
          borderRadius: BorderRadius.circular(26),
          border: Border.all(
            color: isSelected
                ? Colors.white
                : Colors.white.withAlpha(20),
            width: isSelected ? 2.0 : 1.0,
          ),
          boxShadow: [
            if (isSelected)
              BoxShadow(
                color: Colors.white.withAlpha(25),
                blurRadius: 24,
                spreadRadius: 1,
                offset: const Offset(0, 6),
              )
            else
              BoxShadow(
                color: Colors.black.withAlpha(100),
                blurRadius: 16,
                offset: const Offset(0, 6),
              ),
          ],
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(24),
          child: Stack(
            fit: StackFit.expand,
            children: [
              // 1. Full-height Image Layer (Maximizing person display)
              Container(
                alignment: Alignment.center,
                padding: const EdgeInsets.fromLTRB(16, 56, 16, 68),
                child: _buildThumbnail(),
              ),

              // 2. Top Header Overlay (Person ID & Confidence & Big Checkbox)
              Positioned(
                top: 14,
                left: 14,
                right: 14,
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                      decoration: BoxDecoration(
                        color: isSelected
                            ? Colors.white
                            : Colors.white.withAlpha(20),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Text(
                        '人物 ${person.id}',
                        style: TextStyle(
                          color: isSelected ? Colors.black : Colors.white,
                          fontWeight: FontWeight.bold,
                          fontSize: 14,
                        ),
                      ),
                    ),
                    Row(
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                          decoration: BoxDecoration(
                            color: Colors.black54,
                            borderRadius: BorderRadius.circular(8),
                            border: Border.all(color: Colors.white12),
                          ),
                          child: Text(
                            '置信度: $confPercent%',
                            style: TextStyle(
                              fontSize: 12,
                              color: Colors.white.withAlpha(220),
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        AnimatedContainer(
                          duration: const Duration(milliseconds: 200),
                          width: 32,
                          height: 32,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: isSelected
                                ? Colors.white
                                : Colors.black54,
                            border: Border.all(
                              color: isSelected
                                  ? Colors.white
                                  : Colors.white38,
                              width: 1.8,
                            ),
                          ),
                          child: Icon(
                            isSelected ? Icons.check : Icons.circle_outlined,
                            size: 18,
                            color: isSelected ? Colors.black : Colors.white54,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),

              // 3. Bottom Status Bar Overlay
              Positioned(
                bottom: 14,
                left: 14,
                right: 14,
                child: Container(
                  padding: const EdgeInsets.symmetric(vertical: 11, horizontal: 16),
                  decoration: BoxDecoration(
                    color: isSelected
                        ? Colors.white
                        : Colors.white.withAlpha(10),
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(
                      color: isSelected
                          ? Colors.white
                          : Colors.white10,
                    ),
                  ),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(
                        isSelected
                            ? Icons.check_circle_rounded
                            : Icons.add_circle_outline_rounded,
                        size: 18,
                        color: isSelected
                            ? Colors.black
                            : Colors.white60,
                      ),
                      const SizedBox(width: 8),
                      Text(
                        isSelected ? '已选中（将应用特效）' : '未选中（直通原画）',
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.bold,
                          color: isSelected
                              ? Colors.black
                              : Colors.white60,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildThumbnail() {
    if (person.thumbnailPath.isNotEmpty && File(person.thumbnailPath).existsSync()) {
      return Image.file(
        File(person.thumbnailPath),
        fit: BoxFit.contain,
        width: double.infinity,
        height: double.infinity,
        errorBuilder: (context, error, stackTrace) => _buildFallback(),
      );
    }
    return _buildFallback();
  }

  Widget _buildFallback() {
    return Container(
      color: Colors.black12,
      alignment: Alignment.center,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: const [
          Icon(Icons.person, size: 80, color: Colors.white30),
          SizedBox(height: 12),
          Text(
            '首帧人物剪影',
            style: TextStyle(color: Colors.white38, fontSize: 13),
          ),
        ],
      ),
    );
  }
}
