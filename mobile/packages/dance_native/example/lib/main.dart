import 'package:flutter/material.dart';
import 'package:dance_native/dance_native.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final _client = DanceNativeClient();
  NativeCapabilitiesDto? _capabilities;
  String _status = 'Initializing...';

  @override
  void initState() {
    super.initState();
    _loadCapabilities();
  }

  Future<void> _loadCapabilities() async {
    try {
      final caps = await _client.getCapabilities();
      if (mounted) {
        setState(() {
          _capabilities = caps;
          _status = 'Native connected';
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _status = 'Error: $e';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('DanceNative Plugin Example'),
        ),
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text('Status: $_status'),
              if (_capabilities != null) ...[
                const SizedBox(height: 16),
                Text('Platform: ${_capabilities!.platform} ${_capabilities!.osVersion}'),
                Text('GPU Supported: ${_capabilities!.gpuSupported}'),
                Text('H264 Encoder: ${_capabilities!.h264Encoder}'),
                Text('CPU Cores: ${_capabilities!.cpuCores}'),
                Text('Recommended Profile: ${_capabilities!.recommendedProfile}'),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
