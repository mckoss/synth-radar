import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:radar_camera/radar_camera.dart';

import 'notes_editor.dart';
import 'pass_notes.dart';
import 'recordings.dart';

/// Live preview plus record button. Each recording becomes one bundle directory.
class RecordPage extends StatefulWidget {
  const RecordPage({super.key});

  @override
  State<RecordPage> createState() => _RecordPageState();
}

class _RecordPageState extends State<RecordPage> with WidgetsBindingObserver {
  CameraSummary? _camera;
  VideoMode? _mode;
  PreviewInfo? _preview;
  String? _error;
  bool _recording = false;
  bool _busy = false;
  int _rotationOffset = 0;
  Map<String, dynamic> _status = const {};
  StreamSubscription<Map<String, dynamic>>? _events;
  PassNotes _lastNotes = PassNotes();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _events = RadarCamera.events.listen((e) {
      if (!mounted) return;
      if (e['event'] == 'error') {
        setState(() => _error = e['message'] as String?);
      } else {
        setState(() => _status = e);
      }
    });
    _start();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _events?.cancel();
    RadarCamera.close();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused) {
      // Closing the camera also finalizes any recording in progress.
      RadarCamera.close();
      setState(() {
        _preview = null;
        _recording = false;
      });
    } else if (state == AppLifecycleState.resumed && _preview == null) {
      _open();
    }
  }

  Future<void> _start() async {
    final statuses = await [Permission.camera, Permission.microphone].request();
    if (statuses[Permission.camera] != PermissionStatus.granted) {
      setState(() => _error = 'Camera permission is required.');
      return;
    }
    final cameras = await RadarCamera.listCameras();
    final back = cameras.where(
      (c) => c.facing == 'back' && c.logicalParent == null,
    );
    if (back.isEmpty) {
      setState(() => _error = 'No back camera found.');
      return;
    }
    final cam = back.first;
    final modes = cam.videoModes;
    _camera = cam;
    _mode = modes.contains(const VideoMode(3840, 2160, 30))
        ? const VideoMode(3840, 2160, 30)
        : (modes.isNotEmpty ? modes.first : const VideoMode(1920, 1080, 30));
    await _open();
  }

  Future<void> _open() async {
    final cam = _camera;
    final mode = _mode;
    if (cam == null || mode == null) return;
    try {
      final p = await RadarCamera.open(cam.id, mode);
      if (mounted) {
        setState(() {
          _preview = p;
          _error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _error = 'Open failed: $e');
    }
  }

  Future<void> _toggleRecord() async {
    if (_busy) return;
    _recording ? await _stop() : await _record();
  }

  Future<void> _record() async {
    setState(() => _busy = true);
    try {
      final dir = await RecordingStore.newBundleDir();
      await RadarCamera.startRecording(
        dir.path,
        audio: await Permission.microphone.isGranted,
      );
      setState(() => _recording = true);
    } catch (e) {
      setState(() => _error = 'Record failed: $e');
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _stop() async {
    setState(() => _busy = true);
    try {
      final result = await RadarCamera.stopRecording();
      setState(() => _recording = false);
      final bundle = RecordingBundle(Directory(result['dir'] as String));
      if (!mounted) return;
      final notes = await editPassNotes(
        context,
        _lastNotes.nextPass(),
        title:
            'Describe this pass '
            '(${(result['durationS'] as num? ?? 0).toStringAsFixed(1)} s, '
            '${result['videoFrames']} frames)',
      );
      if (notes != null) {
        await bundle.writeNotes(notes);
        _lastNotes = notes;
      }
    } catch (e) {
      setState(() => _error = 'Stop failed: $e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _changeMode(VideoMode m) async {
    if (_recording) return;
    setState(() {
      _mode = m;
      _preview = null;
    });
    await _open();
  }

  int get _quarterTurns {
    final p = _preview;
    if (p == null) return 0;
    // The activity is locked to landscape (display rotation 90°). With the
    // ImageReader-backed texture the raw sensor buffer is shown unrotated.
    final base = p.handlesCropAndRotation
        ? 3
        : ((p.sensorOrientation - 90) % 360) ~/ 90;
    return (base + _rotationOffset) % 4;
  }

  List<String> get _warnings {
    final w = <String>[];
    final stab = _status['stabilization'];
    if (stab != null && stab != 0) w.add('Video stabilization is ON');
    final exp = (_status['exposureMs'] as num?)?.toDouble();
    if (exp != null && exp > 4) {
      w.add('Exposure ${exp.toStringAsFixed(1)} ms: cars may blur');
    }
    final fps = (_status['fps'] as num?)?.toDouble();
    final target = _mode?.fps;
    if (fps != null && target != null && fps < target * 0.9) {
      w.add('Frame rate low: ${fps.toStringAsFixed(1)} fps');
    }
    return w;
  }

  @override
  Widget build(BuildContext context) {
    final p = _preview;
    return Row(
      children: [
        Expanded(
          child: Container(
            color: Colors.black,
            alignment: Alignment.center,
            child: p == null
                ? Text(
                    _error ?? 'Opening camera…',
                    style: const TextStyle(color: Colors.white),
                  )
                : RotatedBox(
                    quarterTurns: _quarterTurns,
                    child: AspectRatio(
                      aspectRatio: p.previewWidth / p.previewHeight,
                      child: Texture(textureId: p.textureId),
                    ),
                  ),
          ),
        ),
        SizedBox(width: 220, child: _controls()),
      ],
    );
  }

  Widget _controls() {
    final modes = _camera?.videoModes ?? const <VideoMode>[];
    final s = _status;
    String num1(Object? v) => v is num ? v.toStringAsFixed(1) : '–';
    return Padding(
      padding: const EdgeInsets.all(8),
      child: ListView(
        children: [
          Center(
            child: SizedBox(
              width: 88,
              height: 88,
              child: FilledButton(
                style: FilledButton.styleFrom(
                  shape: const CircleBorder(),
                  backgroundColor: _recording ? Colors.red : null,
                ),
                onPressed: _preview == null || _busy ? null : _toggleRecord,
                child: _busy
                    ? const CircularProgressIndicator()
                    : Icon(
                        _recording ? Icons.stop : Icons.fiber_manual_record,
                        size: 40,
                      ),
              ),
            ),
          ),
          const SizedBox(height: 8),
          if (_recording)
            Center(
              child: Text(
                'REC ${num1(s['elapsedS'])} s',
                style: const TextStyle(
                  color: Colors.red,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          const SizedBox(height: 8),
          DropdownButton<VideoMode>(
            isExpanded: true,
            value: modes.contains(_mode) ? _mode : null,
            hint: const Text('Video mode'),
            items: [
              for (final m in modes)
                DropdownMenuItem(value: m, child: Text(m.label)),
            ],
            onChanged: _recording || _busy
                ? null
                : (m) => m == null ? null : _changeMode(m),
          ),
          for (final w in _warnings)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                '⚠ $w',
                style: const TextStyle(color: Colors.orange, fontSize: 12),
              ),
            ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                _error!,
                style: const TextStyle(color: Colors.red, fontSize: 12),
              ),
            ),
          const Divider(),
          _kv('Camera', _camera?.id ?? '–'),
          _kv('Active lens', '${s['activePhysicalId'] ?? '–'}'),
          _kv('Capture fps', num1(s['fps'])),
          _kv('Exposure', '${num1(s['exposureMs'])} ms'),
          _kv('ISO', '${s['iso'] ?? '–'}'),
          _kv('Focal length', '${num1(s['focalLengthMm'])} mm'),
          if (_recording) ...[
            _kv('Encoded', '${s['encodedFrames'] ?? 0}'),
            _kv('Gyro', '${num1(s['gyroHz'])} Hz'),
          ],
          _kv('Timestamps', _camera?.timestampSource ?? '–'),
          TextButton.icon(
            onPressed: () => setState(() => _rotationOffset++),
            icon: const Icon(Icons.rotate_90_degrees_cw, size: 16),
            label: const Text('Rotate preview'),
          ),
        ],
      ),
    );
  }

  Widget _kv(String k, String v) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 1),
    child: Row(
      children: [
        Expanded(child: Text(k, style: const TextStyle(fontSize: 12))),
        Text(v, style: const TextStyle(fontSize: 12)),
      ],
    ),
  );
}
