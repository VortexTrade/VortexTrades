# Anthropic SDK relies on Jackson reflection; keep its model classes intact.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.anthropic.**
-dontwarn com.fasterxml.jackson.**
-keepattributes Signature,*Annotation*,EnclosingMethod
