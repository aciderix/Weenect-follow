-keepnames class fr.alerteresidents.data.model.WeenectTrackersResponse
-if class fr.alerteresidents.data.model.WeenectTrackersResponse
-keep class fr.alerteresidents.data.model.WeenectTrackersResponseJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class fr.alerteresidents.data.model.WeenectTrackersResponse
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-keepclassmembers class fr.alerteresidents.data.model.WeenectTrackersResponse {
    public synthetic <init>(java.util.List,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
