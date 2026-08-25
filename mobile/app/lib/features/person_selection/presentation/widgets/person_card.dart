import 'dart:io';
import 'package:flutter/material.dart';
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

    return InkWell(
      onTap: onToggle,
      borderRadius: BorderRadius.circular(14),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        width: 140,
        decoration: BoxDecoration(
          color: isSelected
              ? Colors.deepPurpleAccent.withAlpha(40)
              : const Color(0xFF232128),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: isSelected ? Colors.deepPurpleAccent : Colors.white12,
            width: isSelected ? 2.0 : 1.0,
          ),
        ),
        padding: const EdgeInsets.all(8.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Thumbnail container
            Expanded(
              child: Stack(
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(10),
                    child: _buildThumbnail(),
                  ),
                  Positioned(
                    top: 6,
                    right: 6,
                    child: Container(
                      padding: const EdgeInsets.all(4),
                      decoration: BoxDecoration(
                        color: isSelected ? Colors.deepPurpleAccent : Colors.black54,
                        shape: BoxShape.circle,
                      ),
                      child: Icon(
                        isSelected ? Icons.check : Icons.circle_outlined,
                        size: 14,
                        color: Colors.white,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '人物 ${person.id}',
                  style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
                ),
                Text(
                  '$confPercent%',
                  style: const TextStyle(fontSize: 11, color: Colors.white60),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildThumbnail() {
    if (person.thumbnailPath.isNotEmpty && File(person.thumbnailPath).existsSync()) {
      return Image.file(
        File(person.thumbnailPath),
        fit: BoxFit.cover,
        width: double.infinity,
        height: double.infinity,
        errorBuilder: (context, error, stackTrace) => _buildFallback(),
      );
    }
    return _buildFallback();
  }

  Widget _buildFallback() {
    return Container(
      color: Colors.black38,
      alignment: Alignment.center,
      child: const Icon(Icons.person, size: 36, color: Colors.white54),
    );
  }
}
