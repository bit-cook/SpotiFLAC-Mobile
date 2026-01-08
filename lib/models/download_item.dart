import 'package:json_annotation/json_annotation.dart';
import 'package:spotiflac_android/models/track.dart';

part 'download_item.g.dart';

/// Download status enum
enum DownloadStatus {
  queued,
  downloading,
  finalizing, // Embedding metadata, cover, lyrics
  completed,
  failed,
  skipped,
}

/// Error type enum for better error handling
enum DownloadErrorType {
  unknown,
  notFound,    // Track not found on any service
  rateLimit,   // Rate limited by service
  network,     // Network/connection error
}

@JsonSerializable()
class DownloadItem {
  final String id;
  final Track track;
  final String service;
  final DownloadStatus status;
  final double progress;
  final double speedMBps; // Download speed in MB/s
  final String? filePath;
  final String? error;
  final DownloadErrorType? errorType;
  final DateTime createdAt;
  final String? qualityOverride; // Override quality for this specific download

  const DownloadItem({
    required this.id,
    required this.track,
    required this.service,
    this.status = DownloadStatus.queued,
    this.progress = 0.0,
    this.speedMBps = 0.0,
    this.filePath,
    this.error,
    this.errorType,
    required this.createdAt,
    this.qualityOverride,
  });

  DownloadItem copyWith({
    String? id,
    Track? track,
    String? service,
    DownloadStatus? status,
    double? progress,
    double? speedMBps,
    String? filePath,
    String? error,
    DownloadErrorType? errorType,
    DateTime? createdAt,
    String? qualityOverride,
  }) {
    return DownloadItem(
      id: id ?? this.id,
      track: track ?? this.track,
      service: service ?? this.service,
      status: status ?? this.status,
      progress: progress ?? this.progress,
      speedMBps: speedMBps ?? this.speedMBps,
      filePath: filePath ?? this.filePath,
      error: error ?? this.error,
      errorType: errorType ?? this.errorType,
      createdAt: createdAt ?? this.createdAt,
      qualityOverride: qualityOverride ?? this.qualityOverride,
    );
  }

  /// Get user-friendly error message based on error type
  String get errorMessage {
    if (error == null) return '';
    
    switch (errorType) {
      case DownloadErrorType.notFound:
        return 'Song not found on any service';
      case DownloadErrorType.rateLimit:
        return 'Rate limit reached, try again later';
      case DownloadErrorType.network:
        return 'Connection failed, check your internet';
      default:
        return error ?? 'An error occurred';
    }
  }

  factory DownloadItem.fromJson(Map<String, dynamic> json) =>
      _$DownloadItemFromJson(json);
  Map<String, dynamic> toJson() => _$DownloadItemToJson(this);
}
