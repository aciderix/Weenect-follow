-keepnames class fr.alerteresidents.data.model.WeenectLoginResponse
-if class fr.alerteresidents.data.model.WeenectLoginResponse
-keep class fr.alerteresidents.data.model.WeenectLoginResponseJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class fr.alerteresidents.data.model.WeenectLoginResponse
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-keepclassmembers class fr.alerteresidents.data.model.WeenectLoginResponse {
    public synthetic <init>(java.lang.String,java.lang.Double,java.lang.String,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
