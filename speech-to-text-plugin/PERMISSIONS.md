# Microphone Permissions for Speech-to-Text Plugin

## Overview

The Speech-to-Text Plugin requires microphone access to capture audio for voice-to-code features. This document explains how permissions are requested and handled.

## Permission Levels

### AndroidManifest.xml (System Level)
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
```
These declare **required permissions** at the Android OS level.

### Plugin Metadata (Plugin System Level)
```xml
<meta-data
    android:name="plugin.permissions"
    android:value="filesystem.read,network.access,system.commands,audio.record" />
```
These declare **plugin-level permissions**. The IDE plugin system will check these before allowing the plugin to activate.

## Runtime Permission Flow

### 1. Check Permission Status
```kotlin
val hasPermission = plugin.hasMicrophonePermission()
```
Returns `true` if user has granted RECORD_AUDIO permission, `false` otherwise.

### 2. Request Permission (if needed)
```kotlin
if (!plugin.hasMicrophonePermission()) {
    plugin.requestMicrophonePermission()
}
```
Requests microphone permission from the user via Android's system dialog.

### 3. Handle Permission Result
The plugin gracefully handles:
- ✅ Permission granted → Voice recording works
- ❌ Permission denied → Display user-friendly error message
- ⚠️ Permission not requested yet → Request on first use

## Installation Flow

When a user **installs the Speech-to-Text Plugin**:

1. **Plugin Manager detects permissions** from manifest + metadata
2. **User sees permission prompt** (if running Android 6.0+)
   - "Speech-to-Text Plugin needs permission to access your microphone"
   - User can grant or deny
3. **Plugin activates only if:**
   - System permissions are granted (RECORD_AUDIO)
   - Plugin system permissions are approved (audio.record)
4. **Plugin gracefully degrades** if permissions denied
   - Can still use LLM for code generation
   - Voice input disabled

## Available Plugin Permissions

| Permission | Purpose | Android Level |
|---|---|---|
| `filesystem.read` | Access project files | Android 10+ (scoped storage) |
| `filesystem.write` | Modify project files | Android 10+ (scoped storage) |
| `network.access` | Cloud STT, LLM inference | All |
| `system.commands` | Execute gradle, build commands | All |
| `audio.record` | Microphone input (custom) | All |
| `ide.settings` | Access IDE preferences | All |

## Code Examples

### Check Permission Before Using Mic
```kotlin
if (plugin.hasMicrophonePermission()) {
    // Start recording
    startVoiceInput()
} else {
    // Show permission request UI
    showPermissionDialog(
        "Microphone access required",
        "This feature needs microphone permission to work",
        onAllow = { plugin.requestMicrophonePermission() }
    )
}
```

### Handle Permission Denial
```kotlin
try {
    if (!plugin.hasMicrophonePermission()) {
        plugin.requestMicrophonePermission()
        return "Waiting for user permission"
    }
    
    // Proceed with voice recording
    val transcript = recordAndTranscribe()
    val code = plugin.generateCodeFromVoice(transcript)
    plugin.insertCodeAtCursor(code)
} catch (e: SecurityException) {
    Log.e(TAG, "Permission denied", e)
    return "Microphone permission required"
}
```

## Testing Permissions

### Emulator
```bash
# Grant permission
adb shell pm grant com.itsaky.androidide android.permission.RECORD_AUDIO

# Revoke permission
adb shell pm revoke com.itsaky.androidide android.permission.RECORD_AUDIO

# Check permission status
adb shell pm list permissions -g
```

### Device
1. Open **Settings → Apps → CodeOnTheGo → Permissions**
2. Toggle **Microphone** on/off
3. Observe plugin behavior

## Permission Levels by Android Version

| Android Version | Behavior |
|---|---|
| < 6.0 (API 23) | No runtime permissions; manifest declarations only |
| 6.0+ (API 23+) | Runtime permission requests via `requestPermissions()` |
| 12+ (API 31+) | Approximate location permission replaces fine location |
| 14+ (API 34+) | Nearby WiFi devices permission for microphone discovery |

## Security Considerations

1. **Only request when needed** - Request permission at mic button click, not app launch
2. **Explain why** - Show clear message why microphone is needed
3. **Respect denial** - Gracefully degrade if user denies; don't pester repeatedly
4. **Secure audio** - Don't record or transmit audio without explicit user consent
5. **Clear on privacy** - Document in plugin description: "Microphone audio is processed locally or sent only to selected LLM backend"

## Troubleshooting

### Plugin won't activate
```
Check:
1. Is RECORD_AUDIO in AndroidManifest.xml? ✓
2. Is audio.record in plugin.permissions metadata? ✓
3. Has user granted permission in Settings? ✓
4. Is Android version 6.0+ (for runtime permission)? ✓
```

### Permission request doesn't appear
```
Possible causes:
- Permission already granted (check Settings)
- Plugin.activate() not called yet
- Activity not available (plugin may need UI context)
```

### "No value passed for parameter" errors
```
If you see compilation errors:
- Ensure androidx.core:core dependency is in build.gradle.kts
- Use ContextCompat.checkSelfPermission() (not Context.checkPermission())
```

## References

- [Android Runtime Permissions](https://developer.android.com/training/permissions/requesting)
- [Dangerous Permissions List](https://developer.android.com/guide/topics/permissions/overview#dangerous_permissions)
- [ContextCompat.checkSelfPermission()](https://developer.android.com/reference/androidx/core/content/ContextCompat#checkSelfPermission(android.content.Context,java.lang.String))
