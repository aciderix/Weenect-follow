-keepnames class fr.alerteresidents.data.model.WeenectTrackerDto
-if class fr.alerteresidents.data.model.WeenectTrackerDto
-keep class fr.alerteresidents.data.model.WeenectTrackerDtoJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class fr.alerteresidents.data.model.WeenectTrackerDto
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-keepclassmembers class fr.alerteresidents.data.model.WeenectTrackerDto {
    public synthetic <init>(long,java.lang.String,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
