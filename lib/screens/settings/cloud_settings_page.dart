import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:spotiflac_android/l10n/l10n.dart';
import 'package:spotiflac_android/providers/settings_provider.dart';
import 'package:spotiflac_android/widgets/settings_group.dart';

class CloudSettingsPage extends ConsumerStatefulWidget {
  const CloudSettingsPage({super.key});

  @override
  ConsumerState<CloudSettingsPage> createState() => _CloudSettingsPageState();
}

class _CloudSettingsPageState extends ConsumerState<CloudSettingsPage> {
  late TextEditingController _serverUrlController;
  late TextEditingController _usernameController;
  late TextEditingController _passwordController;
  late TextEditingController _remotePathController;
  bool _isTestingConnection = false;
  String? _connectionTestResult;

  @override
  void initState() {
    super.initState();
    final settings = ref.read(settingsProvider);
    _serverUrlController = TextEditingController(text: settings.cloudServerUrl);
    _usernameController = TextEditingController(text: settings.cloudUsername);
    _passwordController = TextEditingController(text: settings.cloudPassword);
    _remotePathController = TextEditingController(text: settings.cloudRemotePath);
  }

  @override
  void dispose() {
    _serverUrlController.dispose();
    _usernameController.dispose();
    _passwordController.dispose();
    _remotePathController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final settings = ref.watch(settingsProvider);
    final colorScheme = Theme.of(context).colorScheme;
    final topPadding = MediaQuery.of(context).padding.top;

    return PopScope(
      canPop: true,
      child: Scaffold(
        body: CustomScrollView(
          slivers: [
            SliverAppBar(
              expandedHeight: 120 + topPadding,
              collapsedHeight: kToolbarHeight,
              floating: false,
              pinned: true,
              backgroundColor: colorScheme.surface,
              surfaceTintColor: Colors.transparent,
              leading: IconButton(
                icon: const Icon(Icons.arrow_back),
                onPressed: () => Navigator.pop(context),
              ),
              flexibleSpace: LayoutBuilder(
                builder: (context, constraints) {
                  final maxHeight = 120 + topPadding;
                  final minHeight = kToolbarHeight + topPadding;
                  final expandRatio =
                      ((constraints.maxHeight - minHeight) /
                              (maxHeight - minHeight))
                          .clamp(0.0, 1.0);
                  final leftPadding = 56 - (32 * expandRatio);
                  return FlexibleSpaceBar(
                    expandedTitleScale: 1.0,
                    titlePadding: EdgeInsets.only(
                      left: leftPadding,
                      bottom: 16,
                    ),
                    title: Text(
                      context.l10n.cloudSettingsTitle,
                      style: TextStyle(
                        fontSize: 20 + (8 * expandRatio),
                        fontWeight: FontWeight.bold,
                        color: colorScheme.onSurface,
                      ),
                    ),
                  );
                },
              ),
            ),

            // Enable Cloud Upload
            SliverToBoxAdapter(
              child: SettingsSectionHeader(title: context.l10n.cloudSettingsSectionGeneral),
            ),
            SliverToBoxAdapter(
              child: SettingsGroup(
                children: [
                  SettingsSwitchItem(
                    icon: Icons.cloud_upload_outlined,
                    title: context.l10n.cloudSettingsEnable,
                    subtitle: context.l10n.cloudSettingsEnableSubtitle,
                    value: settings.cloudUploadEnabled,
                    onChanged: (value) {
                      ref.read(settingsProvider.notifier).setCloudUploadEnabled(value);
                    },
                    showDivider: false,
                  ),
                ],
              ),
            ),

            // Provider Selection
            if (settings.cloudUploadEnabled) ...[
              SliverToBoxAdapter(
                child: SettingsSectionHeader(title: context.l10n.cloudSettingsSectionProvider),
              ),
              SliverToBoxAdapter(
                child: SettingsGroup(
                  children: [
                    SettingsItem(
                      icon: Icons.dns_outlined,
                      title: context.l10n.cloudSettingsProvider,
                      subtitle: _getProviderName(settings.cloudProvider),
                      onTap: () => _showProviderPicker(context, settings.cloudProvider),
                      showDivider: false,
                    ),
                  ],
                ),
              ),

              // Server Configuration (for WebDAV/SFTP)
              if (settings.cloudProvider != 'none' && settings.cloudProvider != 'gdrive') ...[
                SliverToBoxAdapter(
                  child: SettingsSectionHeader(title: context.l10n.cloudSettingsSectionServer),
                ),
                SliverToBoxAdapter(
                  child: SettingsGroup(
                    children: [
                      Padding(
                        padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                        child: TextField(
                          controller: _serverUrlController,
                          decoration: InputDecoration(
                            labelText: context.l10n.cloudSettingsServerUrl,
                            hintText: settings.cloudProvider == 'webdav'
                                ? 'https://your-server.com/webdav'
                                : 'sftp://your-server.com:22',
                            border: const OutlineInputBorder(),
                            prefixIcon: const Icon(Icons.link),
                          ),
                          onChanged: (value) {
                            ref.read(settingsProvider.notifier).setCloudServerUrl(value);
                          },
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                        child: TextField(
                          controller: _usernameController,
                          decoration: InputDecoration(
                            labelText: context.l10n.cloudSettingsUsername,
                            border: const OutlineInputBorder(),
                            prefixIcon: const Icon(Icons.person_outline),
                          ),
                          onChanged: (value) {
                            ref.read(settingsProvider.notifier).setCloudUsername(value);
                          },
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                        child: TextField(
                          controller: _passwordController,
                          obscureText: true,
                          decoration: InputDecoration(
                            labelText: context.l10n.cloudSettingsPassword,
                            border: const OutlineInputBorder(),
                            prefixIcon: const Icon(Icons.lock_outline),
                          ),
                          onChanged: (value) {
                            ref.read(settingsProvider.notifier).setCloudPassword(value);
                          },
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                        child: TextField(
                          controller: _remotePathController,
                          decoration: InputDecoration(
                            labelText: context.l10n.cloudSettingsRemotePath,
                            hintText: '/Music/SpotiFLAC',
                            border: const OutlineInputBorder(),
                            prefixIcon: const Icon(Icons.folder_outlined),
                          ),
                          onChanged: (value) {
                            ref.read(settingsProvider.notifier).setCloudRemotePath(value);
                          },
                        ),
                      ),
                    ],
                  ),
                ),

                // Test Connection Button
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                    child: FilledButton.icon(
                      onPressed: _isTestingConnection ? null : _testConnection,
                      icon: _isTestingConnection
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.sync),
                      label: Text(context.l10n.cloudSettingsTestConnection),
                    ),
                  ),
                ),

                // Connection Test Result
                if (_connectionTestResult != null)
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                      child: Card(
                        color: _connectionTestResult!.startsWith('Success')
                            ? colorScheme.primaryContainer
                            : colorScheme.errorContainer,
                        child: Padding(
                          padding: const EdgeInsets.all(12),
                          child: Row(
                            children: [
                              Icon(
                                _connectionTestResult!.startsWith('Success')
                                    ? Icons.check_circle
                                    : Icons.error,
                                color: _connectionTestResult!.startsWith('Success')
                                    ? colorScheme.onPrimaryContainer
                                    : colorScheme.onErrorContainer,
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: Text(
                                  _connectionTestResult!,
                                  style: TextStyle(
                                    color: _connectionTestResult!.startsWith('Success')
                                        ? colorScheme.onPrimaryContainer
                                        : colorScheme.onErrorContainer,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
              ],

              // Info about the feature
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                  child: Card(
                    color: colorScheme.tertiaryContainer.withValues(alpha: 0.3),
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Icon(
                            Icons.info_outline,
                            size: 20,
                            color: colorScheme.tertiary,
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Text(
                              context.l10n.cloudSettingsInfo,
                              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                color: colorScheme.onTertiaryContainer,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ],

            const SliverToBoxAdapter(child: SizedBox(height: 32)),
          ],
        ),
      ),
    );
  }

  String _getProviderName(String provider) {
    switch (provider) {
      case 'webdav':
        return 'WebDAV (Synology, Nextcloud, QNAP)';
      case 'sftp':
        return 'SFTP';
      case 'gdrive':
        return 'Google Drive (Coming Soon)';
      default:
        return 'Not Configured';
    }
  }

  void _showProviderPicker(BuildContext context, String current) {
    final colorScheme = Theme.of(context).colorScheme;
    showModalBottomSheet(
      context: context,
      backgroundColor: colorScheme.surfaceContainerHigh,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 24, 24, 8),
              child: Text(
                context.l10n.cloudSettingsProvider,
                style: Theme.of(context).textTheme.titleLarge?.copyWith(
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
              child: Text(
                context.l10n.cloudSettingsProviderDescription,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                ),
              ),
            ),
            ListTile(
              leading: const Icon(Icons.web),
              title: const Text('WebDAV'),
              subtitle: const Text('Synology, Nextcloud, QNAP, ownCloud'),
              trailing: current == 'webdav' ? Icon(Icons.check, color: colorScheme.primary) : null,
              onTap: () {
                ref.read(settingsProvider.notifier).setCloudProvider('webdav');
                Navigator.pop(context);
              },
            ),
            ListTile(
              leading: const Icon(Icons.terminal),
              title: const Text('SFTP'),
              subtitle: const Text('SSH File Transfer Protocol'),
              trailing: current == 'sftp' ? Icon(Icons.check, color: colorScheme.primary) : null,
              onTap: () {
                ref.read(settingsProvider.notifier).setCloudProvider('sftp');
                Navigator.pop(context);
              },
            ),
            ListTile(
              leading: Icon(Icons.cloud, color: colorScheme.onSurfaceVariant),
              title: Text(
                'Google Drive',
                style: TextStyle(color: colorScheme.onSurfaceVariant),
              ),
              subtitle: Text(
                'Coming Soon',
                style: TextStyle(color: colorScheme.onSurfaceVariant),
              ),
              enabled: false,
            ),
            const SizedBox(height: 16),
          ],
        ),
      ),
    );
  }

  Future<void> _testConnection() async {
    setState(() {
      _isTestingConnection = true;
      _connectionTestResult = null;
    });

    // TODO: Implement actual connection test
    await Future.delayed(const Duration(seconds: 2));

    final settings = ref.read(settingsProvider);
    if (settings.cloudServerUrl.isEmpty) {
      setState(() {
        _isTestingConnection = false;
        _connectionTestResult = 'Error: Server URL is required';
      });
      return;
    }

    // Placeholder - actual implementation will use webdav_client
    setState(() {
      _isTestingConnection = false;
      _connectionTestResult = 'Success: Connection test will be implemented in next version';
    });
  }
}
