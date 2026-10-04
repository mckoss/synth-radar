import 'package:flutter/material.dart';
import 'package:radar_camera/radar_camera.dart';
import 'package:share_plus/share_plus.dart';

import 'recordings.dart';

/// Shows what each camera reports about its lens, and exports the full dump.
class CameraInfoPage extends StatefulWidget {
  const CameraInfoPage({super.key});

  @override
  State<CameraInfoPage> createState() => _CameraInfoPageState();
}

class _CameraInfoPageState extends State<CameraInfoPage> {
  List<CameraSummary>? _cameras;
  String? _error;

  @override
  void initState() {
    super.initState();
    RadarCamera.listCameras().then(
      (c) => setState(() => _cameras = c),
      onError: (Object e) => setState(() => _error = '$e'),
    );
  }

  Future<void> _export() async {
    final root = await RecordingStore.root();
    final name =
        'camera_characteristics_'
        '${RecordingStore.bundleName(DateTime.now())}.json';
    final path = await RadarCamera.dumpCharacteristics(
      '${root.parent.path}/$name',
    );
    await SharePlus.instance.share(
      ShareParams(
        title: 'Camera characteristics',
        files: [XFile(path, mimeType: 'application/json')],
      ),
    );
  }

  String _fmt(List<double> v, [int digits = 3]) => v.isEmpty
      ? 'not reported'
      : v.map((x) => x.toStringAsFixed(digits)).join(', ');

  @override
  Widget build(BuildContext context) {
    final cams = _cameras;
    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                'Camera calibration data',
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            FilledButton.icon(
              onPressed: _export,
              icon: const Icon(Icons.ios_share),
              label: const Text('Export full dump'),
            ),
          ],
        ),
        if (_error != null) Text(_error!),
        if (cams == null && _error == null)
          const Padding(
            padding: EdgeInsets.all(24),
            child: Center(child: CircularProgressIndicator()),
          ),
        for (final c in cams ?? const <CameraSummary>[])
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: DefaultTextStyle.merge(
                style: const TextStyle(fontSize: 13),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Camera ${c.id} · ${c.facing}'
                      '${c.logicalParent != null ? ' · physical of ${c.logicalParent}' : ''}'
                      '${c.isLogicalMultiCamera ? ' · logical multi-camera' : ''}',
                      style: const TextStyle(fontWeight: FontWeight.bold),
                    ),
                    Text('Focal length (mm): ${_fmt(c.focalLengths, 2)}'),
                    Text(
                      'Intrinsics fx, fy, cx, cy, s: ${_fmt(c.intrinsics, 1)}',
                    ),
                    Text(
                      'Distortion k1, k2, k3, p1, p2: ${_fmt(c.distortion, 4)}',
                    ),
                    Text(
                      'Sensor orientation: ${c.sensorOrientation}° · '
                      'timestamps: ${c.timestampSource}',
                    ),
                    Text(
                      'Physical size (mm): ${c.raw['sensorPhysicalSize'] ?? '–'} · '
                      'active array: ${c.raw['activeArray'] ?? '–'}',
                    ),
                    Text(
                      'Stabilization modes: video ${c.raw['videoStabilizationModes']}, '
                      'optical ${c.raw['opticalStabilizationModes']}, '
                      'distortion correction ${c.raw['distortionCorrectionModes']}',
                    ),
                    Text(
                      'Video modes: '
                      '${c.videoModes.map((m) => m.label).join(', ')}',
                    ),
                  ],
                ),
              ),
            ),
          ),
      ],
    );
  }
}
