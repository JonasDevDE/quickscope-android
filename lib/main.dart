import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'app_services.dart';
import 'app_view_model.dart';
import 'logic/audio.dart';
import 'session_view_model.dart';
import 'ui/root.dart';
import 'ui/theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initializeDateFormatting('de_DE');
  await AppServices.initialize();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      statusBarBrightness: Brightness.dark,
      systemNavigationBarColor: AppColors.black,
      systemNavigationBarIconBrightness: Brightness.light,
    ),
  );
  runApp(const QuickscopeApp());
}

class QuickscopeApp extends StatefulWidget {
  const QuickscopeApp({super.key});

  @override
  State<QuickscopeApp> createState() => _QuickscopeAppState();
}

class _QuickscopeAppState extends State<QuickscopeApp>
    with WidgetsBindingObserver {
  late final AppViewModel _vm = AppViewModel(AppServices.instance);
  late final SessionViewModel _sessionVm =
      SessionViewModel(AppServices.instance);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final inForeground = state == AppLifecycleState.resumed;
    _vm.onAppLifecycleState(inForeground);
    if (shouldStopForLifecycle(
      inForeground,
      AppServices.instance.voice.playState,
    )) {
      AppServices.instance.voice.stop();
      _sessionVm.suppressAutoplay();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _vm.dispose();
    _sessionVm.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Quickscope',
        debugShowCheckedModeBanner: false,
        theme: quickscopeTheme,
        darkTheme: quickscopeTheme,
        themeMode: ThemeMode.dark,
        home: QuickscopeRoot(vm: _vm, sessionVm: _sessionVm),
      );
}
