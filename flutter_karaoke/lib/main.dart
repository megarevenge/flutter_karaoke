import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Microphone Monitor',
      debugShowCheckedModeBanner: false,
      // Disable Material 3 to adhere strictly to the requirement of using standard older Material widgets and no styling.
      theme: ThemeData(useMaterial3: false),
      home: const MicrophoneMonitorHome(),
    );
  }
}

class MicrophoneMonitorHome extends StatefulWidget {
  const MicrophoneMonitorHome({super.key});

  @override
  State<MicrophoneMonitorHome> createState() => _MicrophoneMonitorHomeState();
}

class _MicrophoneMonitorHomeState extends State<MicrophoneMonitorHome>
    with WidgetsBindingObserver {
  // MethodChannel is used here because Android native code is required to achieve ultra low-latency real-time loopback.
  // Traditional Dart/Flutter streaming packages have high JNI and GC serialization overhead which causes audio lag.
  static const MethodChannel _channel = MethodChannel(
    'com.example.flutter_karaoke/audio_loopback',
  );

  String _status = 'Idle';
  bool _isPlaying = false;
  bool _wasPlayingBeforePause = false;
  bool _isNsSupported = false;
  bool _isNsEnabled = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkInitialStatus();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    // Properly release native audio resources when the widget/app is disposed.
    _stopLoopback();
    super.dispose();
  }

  // Handle lifecycle changes to comply with Android background execution policies.
  // Apps in the background should not record microphone audio to prevent OS security exceptions and conserve resources.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      if (_isPlaying) {
        _wasPlayingBeforePause = true;
        _stopLoopback();
      }
    } else if (state == AppLifecycleState.resumed) {
      if (_wasPlayingBeforePause) {
        _wasPlayingBeforePause = false;
        _startLoopback();
      }
    }
  }

  Future<void> _checkInitialStatus() async {
    try {
      final bool running = await _channel.invokeMethod('isRunning');
      final bool supported = await _channel.invokeMethod('isNsSupported');
      final bool nsEnabled = await _channel.invokeMethod('isNsEnabled');
      setState(() {
        _isNsSupported = supported;
        _isNsEnabled = nsEnabled;
      });

      if (running) {
        setState(() {
          _isPlaying = true;
          _status = 'Listening';
        });
      } else {
        final status = await Permission.microphone.status;
        if (status.isDenied || status.isPermanentlyDenied) {
          setState(() {
            _status = 'Permission denied';
          });
        } else {
          setState(() {
            _status = 'Idle';
          });
        }
      }
    } on PlatformException catch (e) {
      setState(() {
        _status = 'Error: ${e.message}';
      });
    }
  }

  Future<void> _startLoopback() async {
    // Request microphone permission at runtime using the permission_handler package.
    final status = await Permission.microphone.request();
    if (!status.isGranted) {
      setState(() {
        _status = 'Permission denied';
      });
      return;
    }

    try {
      final bool success = await _channel.invokeMethod('start');
      if (success) {
        setState(() {
          _isPlaying = true;
          _status = 'Listening';
        });
      } else {
        setState(() {
          _status = 'Error';
        });
      }
    } on PlatformException catch (e) {
      setState(() {
        _status = 'Error: ${e.message}';
      });
    }
  }

  Future<void> _stopLoopback() async {
    try {
      await _channel.invokeMethod('stop');
      setState(() {
        _isPlaying = false;
        _status = 'Idle';
      });
    } on PlatformException catch (e) {
      setState(() {
        _status = 'Error: ${e.message}';
      });
    }
  }

  Future<void> _toggleNs() async {
    try {
      final bool nextState = !_isNsEnabled;
      final bool success = await _channel.invokeMethod('toggleNs', {
        'enabled': nextState,
      });
      if (success) {
        setState(() {
          _isNsEnabled = nextState;
        });
      }
    } on PlatformException catch (e) {
      setState(() {
        _status = 'Error: ${e.message}';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Microphone Monitor')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            Text(_status, style: const TextStyle(fontSize: 24.0)),
            const SizedBox(height: 30.0),
            ElevatedButton(
              onPressed: _isPlaying ? _stopLoopback : _startLoopback,
              child: Text(_isPlaying ? 'Stop' : 'Start'),
            ),
            const SizedBox(height: 30.0),
            Text(
              'Noise Suppression: ${_isNsSupported ? (_isNsEnabled ? "Enabled" : "Disabled") : "Not Supported"}',
              style: const TextStyle(fontSize: 16.0),
            ),
            if (_isNsSupported) ...[
              const SizedBox(height: 10.0),
              ElevatedButton(
                onPressed: _toggleNs,
                child: Text(_isNsEnabled ? 'Turn Off NS' : 'Turn On NS'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
