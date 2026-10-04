import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';

import 'notes_editor.dart';
import 'pass_notes.dart';
import 'recordings.dart';

/// Lists recorded passes; edit notes, share or delete them.
class RecordingsPage extends StatefulWidget {
  const RecordingsPage({super.key});

  @override
  State<RecordingsPage> createState() => _RecordingsPageState();
}

class _RecordingsPageState extends State<RecordingsPage> {
  List<RecordingBundle> _bundles = const [];
  String _root = '';

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final root = await RecordingStore.root();
    final list = await RecordingStore.list();
    if (mounted) {
      setState(() {
        _bundles = list;
        _root = root.path;
      });
    }
  }

  Future<void> _edit(RecordingBundle b) async {
    final notes = await editPassNotes(
      context,
      b.readNotes() ?? PassNotes(),
      title: b.name,
    );
    if (notes != null) {
      await b.writeNotes(notes);
      _refresh();
    }
  }

  Future<void> _share(RecordingBundle b) async {
    await SharePlus.instance.share(
      ShareParams(
        title: 'Synth Radar recording ${b.name}',
        subject: 'Synth Radar recording ${b.name}',
        files: [for (final f in b.files) XFile(f.path)],
        fileNameOverrides: [
          for (final f in b.files) '${b.name}_${_base(f.path)}',
        ],
      ),
    );
  }

  Future<void> _delete(RecordingBundle b) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text('Delete ${b.name}?'),
        content: const Text('This removes the video and all logs.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(c, true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (ok == true) {
      await b.dir.delete(recursive: true);
      _refresh();
    }
  }

  static String _base(String path) => path.split('/').last;

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      onRefresh: _refresh,
      child: ListView(
        children: [
          ListTile(
            dense: true,
            title: Text('${_bundles.length} recordings'),
            subtitle: Text(_root),
            trailing: IconButton(
              icon: const Icon(Icons.refresh),
              onPressed: _refresh,
            ),
          ),
          for (final b in _bundles) _tile(b),
        ],
      ),
    );
  }

  Widget _tile(RecordingBundle b) {
    final session = b.readSession();
    final summary = session?['summary'] as Map<String, dynamic>?;
    final video = session?['video'] as Map<String, dynamic>?;
    final notes = b.readNotes();
    final details = [
      if (video != null) '${video['height']}p${video['fps']}',
      if (summary != null)
        '${(summary['durationS'] as num? ?? 0).toStringAsFixed(1)} s',
      if (summary != null) '${summary['videoFrames']} frames',
      formatBytes(b.sizeBytes),
      if (session == null) 'INCOMPLETE',
    ].join(' · ');
    return ListTile(
      title: Text(b.name),
      subtitle: Text('$details\n${notes?.summary ?? 'No notes yet'}'),
      isThreeLine: true,
      onTap: () => _edit(b),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            tooltip: 'Share',
            icon: const Icon(Icons.share),
            onPressed: () => _share(b),
          ),
          IconButton(
            tooltip: 'Delete',
            icon: const Icon(Icons.delete_outline),
            onPressed: () => _delete(b),
          ),
        ],
      ),
    );
  }
}
