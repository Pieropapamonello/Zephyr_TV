# Kept whole in release builds, as R8 can't see how they're reached.

# Media3's FFmpeg decoder (the sound the device has no decoder for): DefaultRenderersFactory makes it by reflection,
# and its native code calls back into it.
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# WireGuard's tunnel (WARP for Zephyr TV): its Go code calls back into the backend and its VPN service over JNI.
-keep class com.wireguard.android.backend.** { *; }
-keep class com.wireguard.crypto.** { *; }
-keep class com.wireguard.config.** { *; }
