import 'package:flutter/material.dart';

import 'pass_notes.dart';

/// Full-screen form for annotating a recorded pass. Returns the edited notes,
/// or null if cancelled.
Future<PassNotes?> editPassNotes(
  BuildContext context,
  PassNotes initial, {
  String title = 'Describe this pass',
}) {
  return Navigator.of(context).push<PassNotes>(
    MaterialPageRoute(
      fullscreenDialog: true,
      builder: (_) => _NotesEditor(initial: initial, title: title),
    ),
  );
}

class _NotesEditor extends StatefulWidget {
  const _NotesEditor({required this.initial, required this.title});

  final PassNotes initial;
  final String title;

  @override
  State<_NotesEditor> createState() => _NotesEditorState();
}

class _NotesEditorState extends State<_NotesEditor> {
  late final PassNotes n = PassNotes.fromJson(widget.initial.toJson());
  late final _speed = TextEditingController(
    text: n.groundTruthMph?.toStringAsFixed(1) ?? '',
  );
  late final _height = TextEditingController(
    text: n.phoneHeightM.toStringAsFixed(2),
  );
  late final _distance = TextEditingController(
    text: n.distanceToLaneM?.toStringAsFixed(1) ?? '',
  );
  late final _makeModel = TextEditingController(text: n.makeModel);
  late final _notes = TextEditingController(text: n.notes);

  @override
  void dispose() {
    for (final c in [_speed, _height, _distance, _makeModel, _notes]) {
      c.dispose();
    }
    super.dispose();
  }

  void _save() {
    n.groundTruthMph = double.tryParse(_speed.text.trim());
    n.phoneHeightM = double.tryParse(_height.text.trim()) ?? n.phoneHeightM;
    n.distanceToLaneM = double.tryParse(_distance.text.trim());
    n.makeModel = _makeModel.text.trim();
    n.notes = _notes.text.trim();
    Navigator.of(context).pop(n);
  }

  Widget _choice<T extends Enum>(
    String label,
    List<T> values,
    T value,
    String Function(T) text,
    ValueChanged<T> onChanged,
  ) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 4),
          Wrap(
            spacing: 6,
            runSpacing: 4,
            children: [
              for (final v in values)
                ChoiceChip(
                  label: Text(text(v)),
                  selected: v == value,
                  onSelected: (_) => setState(() => onChanged(v)),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _number(TextEditingController c, String label, {String? hint}) =>
      Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: TextField(
          controller: c,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(labelText: label, hintText: hint),
        ),
      );

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.title),
        actions: [TextButton(onPressed: _save, child: const Text('Save'))],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _choice(
            'Direction',
            PassDirection.values,
            n.direction,
            (v) => v.label,
            (v) => n.direction = v,
          ),
          _choice(
            'Vehicle',
            VehicleType.values,
            n.vehicleType,
            (v) => v.label,
            (v) => n.vehicleType = v,
          ),
          _choice(
            'Ground-truth source',
            GroundTruthSource.values,
            n.groundTruthSource,
            (v) => v.label,
            (v) => n.groundTruthSource = v,
          ),
          _number(
            _speed,
            'Ground-truth speed (mph)',
            hint: 'Leave blank if unknown',
          ),
          _choice(
            'Camera support',
            CameraSupport.values,
            n.support,
            (v) => v.label,
            (v) => n.support = v,
          ),
          _number(_height, 'Phone height above road (m)'),
          _number(
            _distance,
            'Distance from phone to lane center (m)',
            hint: 'Approximate',
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('License plate visible in the clip'),
            value: n.plateVisible,
            onChanged: (v) => setState(() => n.plateVisible = v),
          ),
          TextField(
            controller: _makeModel,
            decoration: const InputDecoration(
              labelText: 'Make / model (if known)',
              hintText: 'e.g. 2019 Honda Civic sedan',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _notes,
            maxLines: 3,
            decoration: const InputDecoration(
              labelText: 'Notes',
              hintText: 'Light, weather, anything odd',
            ),
          ),
        ],
      ),
    );
  }
}
