/// Recording bundles on disk: one directory per pass.
library;

import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';

import 'pass_notes.dart';

class RecordingBundle {
  RecordingBundle(this.dir);

  final Directory dir;

  String get name => dir.path.split(Platform.pathSeparator).last;
  File get sessionFile => File('${dir.path}/session.json');
  File get notesFile => File('${dir.path}/notes.json');

  Map<String, dynamic>? readSession() => _readJson(sessionFile);

  PassNotes? readNotes() {
    final j = _readJson(notesFile);
    return j == null ? null : PassNotes.fromJson(j);
  }

  Future<void> writeNotes(PassNotes notes) => notesFile.writeAsString(
    const JsonEncoder.withIndent('  ').convert(notes.toJson()),
  );

  List<File> get files =>
      dir.listSync().whereType<File>().toList()
        ..sort((a, b) => a.path.compareTo(b.path));

  int get sizeBytes => files.fold(0, (sum, f) => sum + f.lengthSync());

  static Map<String, dynamic>? _readJson(File f) {
    try {
      return jsonDecode(f.readAsStringSync()) as Map<String, dynamic>;
    } catch (_) {
      return null;
    }
  }
}

class RecordingStore {
  static Future<Directory> root() async {
    final base =
        await getExternalStorageDirectory() ??
        await getApplicationDocumentsDirectory();
    final dir = Directory('${base.path}/recordings');
    await dir.create(recursive: true);
    return dir;
  }

  static Future<Directory> newBundleDir() async {
    final r = await root();
    return Directory('${r.path}/${bundleName(DateTime.now())}');
  }

  static String bundleName(DateTime t) {
    String two(int v) => v.toString().padLeft(2, '0');
    return '${t.year}${two(t.month)}${two(t.day)}-'
        '${two(t.hour)}${two(t.minute)}${two(t.second)}';
  }

  static Future<List<RecordingBundle>> list() async {
    final r = await root();
    final dirs = r.listSync().whereType<Directory>().toList()
      ..sort((a, b) => b.path.compareTo(a.path));
    return [for (final d in dirs) RecordingBundle(d)];
  }
}

String formatBytes(int bytes) {
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
  if (bytes < 1024 * 1024 * 1024) {
    return '${(bytes / 1024 / 1024).toStringAsFixed(1)} MB';
  }
  return '${(bytes / 1024 / 1024 / 1024).toStringAsFixed(2)} GB';
}
