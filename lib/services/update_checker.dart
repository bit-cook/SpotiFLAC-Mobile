import 'dart:convert';
import 'dart:io';
import 'package:device_info_plus/device_info_plus.dart';
import 'package:http/http.dart' as http;
import 'package:spotiflac_android/constants/app_info.dart';
import 'package:spotiflac_android/utils/logger.dart';

final _log = AppLogger('UpdateChecker');

enum _ApkVariant { arm64, arm32, universal }

class _ApkAsset {
  final String name;
  final String url;
  final _ApkVariant variant;

  const _ApkAsset({
    required this.name,
    required this.url,
    required this.variant,
  });
}

class UpdateInfo {
  final String version;
  final String changelog;
  final String downloadUrl;
  final String? apkDownloadUrl;
  final DateTime publishedAt;
  final bool isPrerelease;

  const UpdateInfo({
    required this.version,
    required this.changelog,
    required this.downloadUrl,
    this.apkDownloadUrl,
    required this.publishedAt,
    this.isPrerelease = false,
  });
}

class UpdateChecker {
  static const String _latestApiUrl =
      'https://api.github.com/repos/${AppInfo.githubRepo}/releases/latest';
  static const String _allReleasesApiUrl =
      'https://api.github.com/repos/${AppInfo.githubRepo}/releases';

  /// Check for updates based on channel preference
  /// [channel] can be 'stable' or 'preview'
  static Future<UpdateInfo?> checkForUpdate({String channel = 'stable'}) async {
    if (!Platform.isAndroid) {
      return null;
    }

    try {
      Map<String, dynamic>? releaseData;

      if (channel == 'preview') {
        final response = await http
            .get(
              Uri.parse('$_allReleasesApiUrl?per_page=10'),
              headers: {'Accept': 'application/vnd.github.v3+json'},
            )
            .timeout(const Duration(seconds: 10));

        if (response.statusCode != 200) {
          _log.w('GitHub API returned ${response.statusCode}');
          return null;
        }

        final releases = jsonDecode(response.body) as List<dynamic>;
        if (releases.isEmpty) {
          _log.i('No releases found');
          return null;
        }

        releaseData = releases.first as Map<String, dynamic>;
      } else {
        final response = await http
            .get(
              Uri.parse(_latestApiUrl),
              headers: {'Accept': 'application/vnd.github.v3+json'},
            )
            .timeout(const Duration(seconds: 10));

        if (response.statusCode != 200) {
          _log.w('GitHub API returned ${response.statusCode}');
          return null;
        }

        releaseData = jsonDecode(response.body) as Map<String, dynamic>;
      }

      final tagName = releaseData['tag_name'] as String? ?? '';
      final latestVersion = tagName.replaceFirst('v', '');
      final isPrerelease = releaseData['prerelease'] as bool? ?? false;

      if (!_isNewerVersion(latestVersion, AppInfo.version)) {
        _log.i(
          'No update available (current: ${AppInfo.version}, latest: $latestVersion, channel: $channel)',
        );
        return null;
      }

      final body = releaseData['body'] as String? ?? 'No changelog available';
      final htmlUrl =
          releaseData['html_url'] as String? ?? '${AppInfo.githubUrl}/releases';
      final publishedAt =
          DateTime.tryParse(releaseData['published_at'] as String? ?? '') ??
          DateTime.now();

      final assets = _collectApkAssets(
        releaseData['assets'] as List<dynamic>? ?? const [],
      );
      final selectedAsset = await _selectApkForCurrentDevice(assets);
      final apkUrl = selectedAsset?.url;

      _log.i(
        'Update available: $latestVersion (prerelease: $isPrerelease), '
        'APK asset: ${selectedAsset?.name ?? 'none'}, APK URL: $apkUrl',
      );

      return UpdateInfo(
        version: latestVersion,
        changelog: body,
        downloadUrl: htmlUrl,
        apkDownloadUrl: apkUrl,
        publishedAt: publishedAt,
        isPrerelease: isPrerelease,
      );
    } catch (e) {
      _log.e('Error checking for updates: $e');
      return null;
    }
  }

  static bool _isNewerVersion(String latest, String current) {
    try {
      final latestBase = latest.split('-').first;
      final currentBase = current.split('-').first;

      final latestParts = latestBase.split('.').map(int.parse).toList();
      final currentParts = currentBase.split('.').map(int.parse).toList();

      while (latestParts.length < 3) {
        latestParts.add(0);
      }
      while (currentParts.length < 3) {
        currentParts.add(0);
      }

      for (int i = 0; i < 3; i++) {
        if (latestParts[i] > currentParts[i]) return true;
        if (latestParts[i] < currentParts[i]) return false;
      }

      final latestHasSuffix = latest.contains('-');
      final currentHasSuffix = current.contains('-');

      if (!latestHasSuffix && currentHasSuffix) return true;

      return false;
    } catch (e) {
      _log.e('Error comparing versions: $e');
      return false;
    }
  }

  static String get currentVersion => AppInfo.version;

  static List<_ApkAsset> _collectApkAssets(List<dynamic> assets) {
    final apkAssets = <_ApkAsset>[];

    for (final asset in assets.whereType<Map<Object?, Object?>>()) {
      final assetMap = Map<String, dynamic>.from(asset);
      final name = (assetMap['name'] as String? ?? '').trim();
      final normalizedName = name.toLowerCase();
      if (!normalizedName.endsWith('.apk')) {
        continue;
      }

      final downloadUrl = assetMap['browser_download_url'] as String?;
      final uri = downloadUrl != null ? Uri.tryParse(downloadUrl) : null;
      if (uri == null || uri.scheme != 'https') {
        _log.w('Skipping non-HTTPS APK URL: $downloadUrl');
        continue;
      }

      final variant = _apkVariantFromName(normalizedName);
      if (variant == null) {
        _log.w('Skipping APK with unknown variant: $name');
        continue;
      }

      apkAssets.add(
        _ApkAsset(name: name, url: uri.toString(), variant: variant),
      );
    }

    return apkAssets;
  }

  static _ApkVariant? _apkVariantFromName(String name) {
    if (name.contains('universal')) {
      return _ApkVariant.universal;
    }
    if (name.contains('arm64') || name.contains('arm64-v8a')) {
      return _ApkVariant.arm64;
    }
    if (name.contains('arm32') ||
        name.contains('armeabi') ||
        name.contains('armv7') ||
        name.contains('v7a')) {
      return _ApkVariant.arm32;
    }
    return null;
  }

  static Future<_ApkAsset?> _selectApkForCurrentDevice(
    List<_ApkAsset> assets,
  ) async {
    if (assets.isEmpty) {
      return null;
    }

    _ApkAsset? arm64Asset;
    _ApkAsset? arm32Asset;
    _ApkAsset? universalAsset;
    for (final asset in assets) {
      switch (asset.variant) {
        case _ApkVariant.arm64:
          arm64Asset ??= asset;
          break;
        case _ApkVariant.arm32:
          arm32Asset ??= asset;
          break;
        case _ApkVariant.universal:
          universalAsset ??= asset;
          break;
      }
    }

    final supportedAbis = await _getSupportedAndroidAbis();
    final hasArm64 = supportedAbis.any(_isArm64Abi);
    final hasArm32 = supportedAbis.any(_isArm32Abi);

    if (hasArm64) {
      return arm64Asset ?? universalAsset ?? arm32Asset;
    }
    if (hasArm32) {
      return arm32Asset ?? universalAsset;
    }

    if (universalAsset != null) {
      _log.w(
        'Could not match APK asset to supported ABIs ${supportedAbis.join(', ')}; '
        'falling back to universal APK.',
      );
      return universalAsset;
    }

    _log.w(
      'Could not match APK asset to supported ABIs ${supportedAbis.join(', ')}; '
      'no universal APK available.',
    );
    return null;
  }

  static Future<List<String>> _getSupportedAndroidAbis() async {
    if (!Platform.isAndroid) {
      return const [];
    }

    try {
      final androidInfo = await DeviceInfoPlugin().androidInfo;
      final supportedAbis = androidInfo.supportedAbis
          .map((abi) => abi.toLowerCase())
          .where((abi) => abi.isNotEmpty)
          .toSet()
          .toList();
      _log.i('Detected supported Android ABIs: ${supportedAbis.join(', ')}');
      return supportedAbis;
    } catch (e) {
      _log.w('Failed to detect supported Android ABIs: $e');
      return const [];
    }
  }

  static bool _isArm64Abi(String abi) =>
      abi.contains('arm64') || abi.contains('aarch64');

  static bool _isArm32Abi(String abi) =>
      abi.contains('armeabi') || abi.contains('armv7') || abi.contains('arm');
}
