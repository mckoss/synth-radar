/// Measurement camera for Synth Radar.
///
/// Wraps a native Camera2 pipeline that records video with per-frame lens
/// metadata and a high-rate IMU log, all on the device's boot-time clock.
library;

import 'package:flutter/services.dart';

/// A video mode supported by a camera.
class VideoMode {
  const VideoMode(this.width, this.height, this.fps);

  final int width;
  final int height;
  final int fps;

  String get label => '${height}p$fps';

  @override
  bool operator ==(Object other) =>
      other is VideoMode &&
      other.width == width &&
      other.height == height &&
      other.fps == fps;

  @override
  int get hashCode => Object.hash(width, height, fps);
}

/// Compact description of one camera (logical or physical).
class CameraSummary {
  CameraSummary(this.raw);

  final Map<String, dynamic> raw;

  String get id => raw['id'] as String;
  String? get logicalParent => raw['logicalParent'] as String?;
  String get facing => raw['facing'] as String;
  bool get isLogicalMultiCamera =>
      raw['isLogicalMultiCamera'] as bool? ?? false;
  int get sensorOrientation => raw['sensorOrientation'] as int? ?? 0;
  List<double> get focalLengths => _doubles(raw['focalLengths']);
  List<double> get intrinsics => _doubles(raw['intrinsics']);
  List<double> get distortion => _doubles(raw['distortion']);
  String get timestampSource => raw['timestampSource'] as String? ?? 'UNKNOWN';
  List<VideoMode> get videoModes => [
    for (final m in (raw['videoModes'] as List? ?? const []))
      VideoMode(m['width'] as int, m['height'] as int, m['fps'] as int),
  ];

  static List<double> _doubles(Object? v) =>
      v is List ? [for (final x in v) (x as num).toDouble()] : const [];
}

/// Result of opening the camera preview.
class PreviewInfo {
  PreviewInfo(Map<String, dynamic> m)
    : textureId = m['textureId'] as int,
      previewWidth = m['previewWidth'] as int,
      previewHeight = m['previewHeight'] as int,
      sensorOrientation = m['sensorOrientation'] as int? ?? 0,
      handlesCropAndRotation = m['handlesCropAndRotation'] as bool? ?? false;

  final int textureId;
  final int previewWidth;
  final int previewHeight;
  final int sensorOrientation;
  final bool handlesCropAndRotation;
}

class RadarCamera {
  static const _channel = MethodChannel('radar_camera');
  static const _events = EventChannel('radar_camera/events');

  /// Status and error events from the native pipeline (about twice a second).
  static Stream<Map<String, dynamic>> get events => _events
      .receiveBroadcastStream()
      .map((e) => Map<String, dynamic>.from(e as Map));

  static Future<List<CameraSummary>> listCameras() async {
    final list = await _channel.invokeListMethod<Map>('listCameras') ?? [];
    return [for (final m in list) CameraSummary(_deepCast(m))];
  }

  /// Writes every camera characteristic of every camera to [path] as JSON.
  static Future<String> dumpCharacteristics(String path) async =>
      (await _channel.invokeMethod<String>('dumpCharacteristics', {
        'path': path,
      }))!;

  static Future<PreviewInfo> open(String cameraId, VideoMode mode) async {
    final m = await _channel.invokeMapMethod<String, dynamic>('open', {
      'cameraId': cameraId,
      'width': mode.width,
      'height': mode.height,
      'fps': mode.fps,
    });
    return PreviewInfo(m!);
  }

  static Future<Map<String, dynamic>> startRecording(
    String dir, {
    bool audio = true,
  }) async => (await _channel.invokeMapMethod<String, dynamic>(
    'startRecording',
    {'dir': dir, 'audio': audio},
  ))!;

  static Future<Map<String, dynamic>> stopRecording() async =>
      (await _channel.invokeMapMethod<String, dynamic>('stopRecording'))!;

  static Future<void> close() => _channel.invokeMethod('close');

  static Map<String, dynamic> _deepCast(Map m) =>
      m.map((k, v) => MapEntry(k as String, v is Map ? _deepCast(v) : v));
}
