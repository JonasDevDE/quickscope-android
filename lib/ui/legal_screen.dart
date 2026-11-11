import 'package:flutter/material.dart' hide CloseButton;
import 'package:flutter/services.dart';
import '../logic/markdown.dart';
import 'brand_mark.dart';
import 'theme.dart';

/// Rechtliche Seite als ruhige Vollbildseite: Titel, scrollbarer Text; der Text liegt in der App (ohne Netz, ohne WebView).
class LegalScreen extends StatefulWidget {
  final LegalPage page;
  final VoidCallback onClose;

  const LegalScreen({
    super.key,
    required this.page,
    required this.onClose,
  });

  @override
  State<LegalScreen> createState() => _LegalScreenState();
}

class _LegalScreenState extends State<LegalScreen> {
  List<MdBlock> _blocks = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant LegalScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.page != widget.page) _load();
  }

  Future<void> _load() async {
    try {
      final text =
          await rootBundle.loadString('assets/legal/${widget.page.file}.md');
      if (mounted) setState(() => _blocks = parseMarkdown(text));
    } catch (_) {
      if (mounted) setState(() => _blocks = const []);
    }
  }

  TextSpan _styled(List<MdSpan> spans, TextStyle? base) => TextSpan(
        style: base,
        children: [
          for (final s in spans)
            TextSpan(
              text: s.text,
              style: s.bold
                  ? const TextStyle(fontWeight: FontWeight.bold)
                  : null,
            ),
        ],
      );

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context).textTheme;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) widget.onClose();
      },
      child: ColoredBox(
        color: AppColors.black,
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  child: Row(
                    children: [
                      CloseButton(onPressed: widget.onClose, label: 'Zurück'),
                    ],
                  ),
                ),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.only(bottom: 32),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        for (final b in _blocks) ...[
                          const SizedBox(height: 10),
                          switch (b) {
                            MdH1(:final text) => Padding(
                                padding: const EdgeInsets.only(bottom: 4),
                                child: Text(text, style: theme.headlineMedium),
                              ),
                            MdH2(:final text) => Padding(
                                padding: const EdgeInsets.only(top: 14),
                                child: Text(text, style: theme.titleMedium),
                              ),
                            MdPara(:final spans) =>
                              Text.rich(_styled(spans, theme.bodyLarge)),
                            MdBullet(:final spans) => Row(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Padding(
                                    padding: const EdgeInsets.only(right: 10),
                                    child: Text('•', style: theme.bodyLarge),
                                  ),
                                  Expanded(
                                    child: Text.rich(
                                      _styled(spans, theme.bodyLarge),
                                    ),
                                  ),
                                ],
                              ),
                            MdNote(:final text) => Text(
                                text,
                                style: theme.bodySmall
                                    ?.copyWith(color: AppColors.muted),
                              ),
                          },
                        ],
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
