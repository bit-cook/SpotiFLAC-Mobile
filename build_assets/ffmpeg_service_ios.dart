import 'dart:io';
import 'package:ffmpeg_kit_flutter_new_audio/ffmpeg_kit.dart';
import 'package:ffmpeg_kit_flutter_new_audio/return_code.dart';
import 'package:spotiflac_android/utils/logger.dart';

final _log = AppLogger('FFmpeg');

/// FFmpeg service for iOS using ffmpeg_kit_flutter plugin
class FFmpegServiceIOS {
  /// Execute FFmpeg command and return result
  static Future<FFmpegResultIOS> _execute(String command) async {
    try {
      final session = await FFmpegKit.execute(command);
      final returnCode = await session.getReturnCode();
      final output = await session.getOutput() ?? '';
      return FFmpegResultIOS(
        success: ReturnCode.isSuccess(returnCode),
        returnCode: returnCode?.getValue() ?? -1,
        output: output,
      );
    } catch (e) {
      _log.e('FFmpeg execute error: $e');
      return FFmpegResultIOS(success: false, returnCode: -1, output: e.toString());
    }
  }

  /// Convert M4A (DASH segments) to FLAC
  static Future<String?> convertM4aToFlac(String inputPath) async {
    final outputPath = inputPath.replaceAll('.m4a', '.flac');
    final command = '-i "$inputPath" -c:a flac -compression_level 8 "$outputPath" -y';
    final result = await _execute(command);

    if (result.success) {
      try {
        await File(inputPath).delete();
      } catch (_) {}
      return outputPath;
    }

    _log.e('M4A to FLAC conversion failed: ${result.output}');
    return null;
  }

  /// Convert FLAC to MP3
  /// If deleteOriginal is true, deletes the FLAC file after conversion
  static Future<String?> convertFlacToMp3(
    String inputPath, {
    String bitrate = '320k',
    bool deleteOriginal = true,
  }) async {
    // Convert in same folder, just change extension
    final outputPath = inputPath.replaceAll('.flac', '.mp3');

    final command = '-i "$inputPath" -codec:a libmp3lame -b:a $bitrate -map 0:a -map_metadata 0 -id3v2_version 3 "$outputPath" -y';
    final result = await _execute(command);

    if (result.success) {
      // Delete original FLAC if requested
      if (deleteOriginal) {
        try {
          await File(inputPath).delete();
        } catch (_) {}
      }
      return outputPath;
    }
    _log.e('FLAC to MP3 conversion failed: ${result.output}');
    return null;
  }

  /// Convert FLAC to M4A
  static Future<String?> convertFlacToM4a(String inputPath, {String codec = 'aac', String bitrate = '256k'}) async {
    final dir = File(inputPath).parent.path;
    final baseName = inputPath.split(Platform.pathSeparator).last.replaceAll('.flac', '');
    final outputDir = '$dir${Platform.pathSeparator}M4A';
    await Directory(outputDir).create(recursive: true);
    final outputPath = '$outputDir${Platform.pathSeparator}$baseName.m4a';

    String command;
    if (codec == 'alac') {
      command = '-i "$inputPath" -codec:a alac -map 0:a -map_metadata 0 "$outputPath" -y';
    } else {
      command = '-i "$inputPath" -codec:a aac -b:a $bitrate -map 0:a -map_metadata 0 "$outputPath" -y';
    }

    final result = await _execute(command);
    if (result.success) return outputPath;
    _log.e('FLAC to M4A conversion failed: ${result.output}');
    return null;
  }

  /// Embed cover art to FLAC file
  static Future<String?> embedCover(String flacPath, String coverPath) async {
    final tempOutput = '$flacPath.tmp';
    final command = '-i "$flacPath" -i "$coverPath" -map 0:a -map 1:0 -c copy -metadata:s:v title="Album cover" -metadata:s:v comment="Cover (front)" -disposition:v attached_pic "$tempOutput" -y';

    final result = await _execute(command);

    if (result.success) {
      try {
        await File(flacPath).delete();
        await File(tempOutput).rename(flacPath);
        return flacPath;
      } catch (e) {
        _log.e('Failed to replace file after cover embed: $e');
        return null;
      }
    }

    try {
      final tempFile = File(tempOutput);
      if (await tempFile.exists()) await tempFile.delete();
    } catch (_) {}

    _log.e('Cover embed failed: ${result.output}');
    return null;
  }

  /// Embed metadata and cover art to FLAC file
  /// Returns the file path on success, null on failure
  static Future<String?> embedMetadata({
    required String flacPath,
    String? coverPath,
    Map<String, String>? metadata,
  }) async {
    final tempOutput = '$flacPath.tmp';
    
    // Construct command
    final StringBuffer cmdBuffer = StringBuffer();
    cmdBuffer.write('-i "$flacPath" ');
    
    // Add cover input if available
    if (coverPath != null) {
      cmdBuffer.write('-i "$coverPath" ');
    }
    
    // Map audio stream
    cmdBuffer.write('-map 0:a ');
    
    // Map cover stream if available
    if (coverPath != null) {
      cmdBuffer.write('-map 1:0 ');
      cmdBuffer.write('-c:v copy ');
      cmdBuffer.write('-disposition:v attached_pic ');
      cmdBuffer.write('-metadata:s:v title="Album cover" ');
      cmdBuffer.write('-metadata:s:v comment="Cover (front)" ');
    }
    
    // Copy audio codec (don't re-encode)
    cmdBuffer.write('-c:a copy ');
    
    // Add text metadata
    if (metadata != null) {
      metadata.forEach((key, value) {
        // Sanitize value: escape double quotes
        final sanitizedValue = value.replaceAll('"', '\\"');
        cmdBuffer.write('-metadata $key="$sanitizedValue" ');
      });
    }
    
    cmdBuffer.write('"$tempOutput" -y');
    
    final command = cmdBuffer.toString();
    _log.d('Executing FFmpeg command: $command');

    final result = await _execute(command);

    if (result.success) {
      try {
        await File(flacPath).delete();
        await File(tempOutput).rename(flacPath);
        return flacPath;
      } catch (e) {
        _log.e('Failed to replace file after metadata embed: $e');
        return null;
      }
    }

    // Clean up temp file if exists
    try {
      final tempFile = File(tempOutput);
      if (await tempFile.exists()) {
        await tempFile.delete();
      }
    } catch (_) {}

    _log.e('Metadata/Cover embed failed: ${result.output}');
    return null;
  }

  /// Embed metadata and cover art to MP3 file using ID3v2 tags
  /// Returns the file path on success, null on failure
  static Future<String?> embedMetadataToMp3({
    required String mp3Path,
    String? coverPath,
    Map<String, String>? metadata,
  }) async {
    final tempOutput = '$mp3Path.tmp';
    
    final StringBuffer cmdBuffer = StringBuffer();
    cmdBuffer.write('-i "$mp3Path" ');
    
    if (coverPath != null) {
      cmdBuffer.write('-i "$coverPath" ');
    }
    
    cmdBuffer.write('-map 0:a ');
    
    if (coverPath != null) {
      cmdBuffer.write('-map 1:0 ');
      cmdBuffer.write('-c:v:0 copy ');
      cmdBuffer.write('-id3v2_version 3 ');
      cmdBuffer.write('-metadata:s:v title="Album cover" ');
      cmdBuffer.write('-metadata:s:v comment="Cover (front)" ');
    }
    
    cmdBuffer.write('-c:a copy ');
    
    if (metadata != null) {
      // Convert FLAC/Vorbis tags to ID3v2 tags for MP3
      final id3Metadata = _convertToId3Tags(metadata);
      id3Metadata.forEach((key, value) {
        final sanitizedValue = value.replaceAll('"', '\\"');
        cmdBuffer.write('-metadata $key="$sanitizedValue" ');
      });
    }
    
    cmdBuffer.write('-id3v2_version 3 "$tempOutput" -y');
    
    final command = cmdBuffer.toString();
    _log.d('Executing FFmpeg MP3 embed command: $command');

    final result = await _execute(command);

    if (result.success) {
      try {
        await File(mp3Path).delete();
        await File(tempOutput).rename(mp3Path);
        _log.d('MP3 metadata embedded successfully');
        return mp3Path;
      } catch (e) {
        _log.e('Failed to replace MP3 file after metadata embed: $e');
        return null;
      }
    }

    try {
      final tempFile = File(tempOutput);
      if (await tempFile.exists()) {
        await tempFile.delete();
      }
    } catch (_) {}

    _log.e('MP3 Metadata/Cover embed failed: ${result.output}');
    return null;
  }

  /// Convert FLAC/Vorbis comment tags to ID3v2 compatible tags
  static Map<String, String> _convertToId3Tags(Map<String, String> vorbisMetadata) {
    final id3Map = <String, String>{};
    
    for (final entry in vorbisMetadata.entries) {
      final key = entry.key.toUpperCase();
      final value = entry.value;
      
      // Map Vorbis comments to ID3v2 frame names
      switch (key) {
        case 'TITLE':
          id3Map['title'] = value;
          break;
        case 'ARTIST':
          id3Map['artist'] = value;
          break;
        case 'ALBUM':
          id3Map['album'] = value;
          break;
        case 'ALBUMARTIST':
          id3Map['album_artist'] = value;
          break;
        case 'TRACKNUMBER':
        case 'TRACK':
          id3Map['track'] = value;
          break;
        case 'DISCNUMBER':
        case 'DISC':
          id3Map['disc'] = value;
          break;
        case 'DATE':
        case 'YEAR':
          id3Map['date'] = value;
          break;
        case 'ISRC':
          id3Map['TSRC'] = value; // ID3v2 ISRC frame
          break;
        case 'LYRICS':
        case 'UNSYNCEDLYRICS':
          id3Map['lyrics'] = value;
          break;
        default:
          // Pass through other tags as-is
          id3Map[key.toLowerCase()] = value;
      }
    }
    
    return id3Map;
  }

  /// Check if FFmpeg is available
  static Future<bool> isAvailable() async {
    try {
      final session = await FFmpegKit.execute('-version');
      final returnCode = await session.getReturnCode();
      return ReturnCode.isSuccess(returnCode);
    } catch (e) {
      return false;
    }
  }

  /// Get FFmpeg version info
  static Future<String?> getVersion() async {
    try {
      final session = await FFmpegKit.execute('-version');
      return await session.getOutput();
    } catch (e) {
      return null;
    }
  }
}

class FFmpegResultIOS {
  final bool success;
  final int returnCode;
  final String output;

  FFmpegResultIOS({required this.success, required this.returnCode, required this.output});
}
