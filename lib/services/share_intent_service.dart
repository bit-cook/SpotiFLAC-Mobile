import 'dart:async';
import 'package:receive_sharing_intent/receive_sharing_intent.dart';
import 'package:spotiflac_android/utils/logger.dart';

final _log = AppLogger('ShareIntent');

class ShareIntentService {
  static final ShareIntentService _instance = ShareIntentService._internal();
  factory ShareIntentService() => _instance;
  ShareIntentService._internal();

  static final RegExp _spotifyUriPattern =
      RegExp(r'spotify:(track|album|playlist|artist):[a-zA-Z0-9]+');
  static final RegExp _spotifyUrlPattern = RegExp(
    r'https?://open\.spotify\.com/(track|album|playlist|artist)/[a-zA-Z0-9]+(\?[^\s]*)?',
  );

  final _sharedUrlController = StreamController<String>.broadcast();
  StreamSubscription<List<SharedMediaFile>>? _mediaSubscription;
  bool _initialized = false;
  String? _pendingUrl; // Store URL received before listener is ready

  Stream<String> get sharedUrlStream => _sharedUrlController.stream;

  String? consumePendingUrl() {
    final url = _pendingUrl;
    _pendingUrl = null;
    return url;
  }

  Future<void> initialize() async {
    if (_initialized) return;
    _initialized = true;

    _mediaSubscription = ReceiveSharingIntent.instance.getMediaStream().listen(
      _handleSharedMedia,
      onError: (err) => _log.e('Error: $err'),
    );

    final initialMedia = await ReceiveSharingIntent.instance.getInitialMedia();
    if (initialMedia.isNotEmpty) {
      _handleSharedMedia(initialMedia, isInitial: true);
      ReceiveSharingIntent.instance.reset();
    }
  }

  void _handleSharedMedia(List<SharedMediaFile> files, {bool isInitial = false}) {
    for (final file in files) {
      final textToCheck = file.path;
      
      final url = _extractSpotifyUrl(textToCheck);
      if (url != null) {
        _log.i('Received Spotify URL: $url (initial: $isInitial)');
        if (isInitial) {
          _pendingUrl = url;
        }
        _sharedUrlController.add(url);
        return; // Only process first valid URL
      }
    }
  }

  String? _extractSpotifyUrl(String text) {
    if (text.isEmpty) return null;

    final uriMatch = _spotifyUriPattern.firstMatch(text);
    if (uriMatch != null) {
      return uriMatch.group(0);
    }

    final urlMatch = _spotifyUrlPattern.firstMatch(text);
    if (urlMatch != null) {
      final fullUrl = urlMatch.group(0)!;
      final queryIndex = fullUrl.indexOf('?');
      return queryIndex > 0 ? fullUrl.substring(0, queryIndex) : fullUrl;
    }

    return null;
  }

  void dispose() {
    _mediaSubscription?.cancel();
    _sharedUrlController.close();
  }
}
