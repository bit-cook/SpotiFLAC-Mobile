import 'package:flutter/material.dart';
import 'package:spotiflac_android/l10n/app_localizations.dart';

export 'package:spotiflac_android/l10n/app_localizations.dart';

extension AppLocalizationsX on BuildContext {
  AppLocalizations get l10n => AppLocalizations.of(this);
}
