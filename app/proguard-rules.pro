# The model runtimes use native entry points and look up these classes by name.
-keep class com.google.mediapipe.** { *; }
-keep class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }

# These annotations are only used to generate the library code.
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.protobuf.Internal$ProtoMethodMayReturnNull
-dontwarn com.google.protobuf.Internal$ProtoNonnullApi
-dontwarn com.google.protobuf.ProtoField
-dontwarn com.google.protobuf.ProtoPresenceBits
-dontwarn com.google.protobuf.ProtoPresenceCheckedField

# Secure Pocket uses the text model API. Image input and desktop AWT
# integrations are optional and absent from the Android runtime.
-dontwarn com.google.mediapipe.framework.image.BitmapExtractor
-dontwarn com.google.mediapipe.framework.image.ByteBufferExtractor
-dontwarn com.google.mediapipe.framework.image.MPImage
-dontwarn com.google.mediapipe.framework.image.MPImageProperties
-dontwarn com.google.mediapipe.framework.image.MediaImageExtractor
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
