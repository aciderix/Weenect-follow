# Règles R8 pour la version release (minification activée).

# Garder les numéros de ligne dans les traces d'erreur, sans exposer les noms de fichiers source.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Retrofit : les interfaces d'API sont lues par réflexion (règles fournies par Retrofit, renforcées ici).
-keep,allowobfuscation,allowshrinking interface fr.alerteresidents.data.remote.WeenectApiService
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# Moshi (adaptateurs générés par moshi-kotlin-codegen) : les DTO Weenect.
-keep class fr.alerteresidents.data.model.Weenect*Dto { *; }
-keep class fr.alerteresidents.data.model.Weenect*Response { *; }
-keep class fr.alerteresidents.data.model.Weenect*Request { *; }

# OkHttp : plateformes TLS optionnelles absentes sur Android.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
