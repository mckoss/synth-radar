import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'camera_info_page.dart';
import 'record_page.dart';
import 'recordings_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // Recording is landscape-only so frames stay in the sensor's native
  // orientation and the lens calibration applies without rotation.
  SystemChrome.setPreferredOrientations([DeviceOrientation.landscapeLeft]);
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  runApp(const SynthRadarApp());
}

class SynthRadarApp extends StatelessWidget {
  const SynthRadarApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Synth Radar',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.teal,
          brightness: Brightness.dark,
        ),
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Row(
          children: [
            NavigationRail(
              selectedIndex: _index,
              onDestinationSelected: (i) => setState(() => _index = i),
              labelType: NavigationRailLabelType.all,
              destinations: const [
                NavigationRailDestination(
                  icon: Icon(Icons.videocam),
                  label: Text('Record'),
                ),
                NavigationRailDestination(
                  icon: Icon(Icons.video_library),
                  label: Text('Passes'),
                ),
                NavigationRailDestination(
                  icon: Icon(Icons.camera),
                  label: Text('Lens'),
                ),
              ],
            ),
            const VerticalDivider(width: 1),
            // Only the selected page is built, so the camera closes when you
            // leave the Record tab.
            Expanded(
              child: switch (_index) {
                0 => const RecordPage(),
                1 => const RecordingsPage(),
                _ => const CameraInfoPage(),
              },
            ),
          ],
        ),
      ),
    );
  }
}
