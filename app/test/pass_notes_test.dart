import 'package:flutter_test/flutter_test.dart';
import 'package:synth_radar/pass_notes.dart';
import 'package:synth_radar/recordings.dart';

void main() {
  test('PassNotes round-trips through JSON', () {
    final n = PassNotes(
      direction: PassDirection.receding,
      vehicleType: VehicleType.pickup,
      groundTruthMph: 31.5,
      groundTruthSource: GroundTruthSource.radar,
      support: CameraSupport.braced,
      phoneHeightM: 1.5,
      distanceToLaneM: 6,
      plateVisible: true,
      makeModel: 'Ford F-150',
      notes: 'sunny',
    );
    final back = PassNotes.fromJson(n.toJson());
    expect(back.toJson(), n.toJson());
  });

  test('unknown enum names fall back safely', () {
    final n = PassNotes.fromJson({'direction': 'sideways', 'vehicleType': 7});
    expect(n.direction, PassDirection.other);
    expect(n.vehicleType, VehicleType.other);
    expect(n.groundTruthMph, isNull);
  });

  test('nextPass keeps session settings but clears per-pass fields', () {
    final n = PassNotes(
      groundTruthMph: 40,
      makeModel: 'Civic',
      phoneHeightM: 1.3,
      plateVisible: true,
    );
    final next = n.nextPass();
    expect(next.phoneHeightM, 1.3);
    expect(next.groundTruthMph, isNull);
    expect(next.makeModel, '');
    expect(next.plateVisible, isFalse);
  });

  test('bundle names sort chronologically', () {
    expect(
      RecordingStore.bundleName(DateTime(2026, 10, 4, 9, 5, 7)),
      '20261004-090507',
    );
  });
}
