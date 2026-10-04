/// Annotations the tester attaches to each recorded pass (saved as notes.json).
library;

enum PassDirection {
  approaching('Approaching (toward camera)'),
  receding('Receding (away from camera)'),
  leftToRight('Crossing left → right'),
  rightToLeft('Crossing right → left'),
  other('Other / mixed');

  const PassDirection(this.label);
  final String label;
}

enum VehicleType {
  car('Car'),
  suv('SUV / crossover'),
  pickup('Pickup'),
  van('Van'),
  truck('Truck / bus'),
  motorcycle('Motorcycle'),
  multiple('Multiple vehicles'),
  other('Other');

  const VehicleType(this.label);
  final String label;
}

enum GroundTruthSource {
  none('None'),
  radar('Radar gun'),
  gpsLogger('GPS logger in vehicle'),
  speedTrap('Timed between road markers'),
  driverReported('Driver held speed (speedometer / cruise)');

  const GroundTruthSource(this.label);
  final String label;
}

enum CameraSupport {
  handheld('Handheld'),
  braced('Braced'),
  tripod('Tripod');

  const CameraSupport(this.label);
  final String label;
}

class PassNotes {
  PassNotes({
    this.direction = PassDirection.approaching,
    this.vehicleType = VehicleType.car,
    this.groundTruthMph,
    this.groundTruthSource = GroundTruthSource.none,
    this.support = CameraSupport.handheld,
    this.phoneHeightM = 1.4,
    this.distanceToLaneM,
    this.plateVisible = false,
    this.makeModel = '',
    this.notes = '',
  });

  PassDirection direction;
  VehicleType vehicleType;
  double? groundTruthMph;
  GroundTruthSource groundTruthSource;
  CameraSupport support;
  double phoneHeightM;
  double? distanceToLaneM;
  bool plateVisible;
  String makeModel;
  String notes;

  Map<String, dynamic> toJson() => {
    'direction': direction.name,
    'vehicleType': vehicleType.name,
    'groundTruthMph': groundTruthMph,
    'groundTruthSource': groundTruthSource.name,
    'support': support.name,
    'phoneHeightM': phoneHeightM,
    'distanceToLaneM': distanceToLaneM,
    'plateVisible': plateVisible,
    'makeModel': makeModel,
    'notes': notes,
  };

  factory PassNotes.fromJson(Map<String, dynamic> j) => PassNotes(
    direction: _byName(
      PassDirection.values,
      j['direction'],
      PassDirection.other,
    ),
    vehicleType: _byName(
      VehicleType.values,
      j['vehicleType'],
      VehicleType.other,
    ),
    groundTruthMph: (j['groundTruthMph'] as num?)?.toDouble(),
    groundTruthSource: _byName(
      GroundTruthSource.values,
      j['groundTruthSource'],
      GroundTruthSource.none,
    ),
    support: _byName(
      CameraSupport.values,
      j['support'],
      CameraSupport.handheld,
    ),
    phoneHeightM: (j['phoneHeightM'] as num?)?.toDouble() ?? 1.4,
    distanceToLaneM: (j['distanceToLaneM'] as num?)?.toDouble(),
    plateVisible: j['plateVisible'] as bool? ?? false,
    makeModel: j['makeModel'] as String? ?? '',
    notes: j['notes'] as String? ?? '',
  );

  /// Copy used as the starting point for the next pass (sticky session settings).
  PassNotes nextPass() => PassNotes(
    direction: direction,
    vehicleType: vehicleType,
    groundTruthSource: groundTruthSource,
    support: support,
    phoneHeightM: phoneHeightM,
    distanceToLaneM: distanceToLaneM,
  );

  String get summary {
    final speed = groundTruthMph == null
        ? 'no ground truth'
        : '${groundTruthMph!.toStringAsFixed(1)} mph (${groundTruthSource.label})';
    return '${direction.label} · ${vehicleType.label} · $speed';
  }

  static T _byName<T extends Enum>(List<T> values, Object? name, T fallback) =>
      values.firstWhere((v) => v.name == name, orElse: () => fallback);
}
