-keepnames class fr.alerteresidents.data.model.ExtraZoneJson
-if class fr.alerteresidents.data.model.ExtraZoneJson
-keep class fr.alerteresidents.data.model.ExtraZoneJsonJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class fr.alerteresidents.data.model.ExtraZoneJson
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-keepclassmembers class fr.alerteresidents.data.model.ExtraZoneJson {
    public synthetic <init>(java.lang.String,double,double,double,boolean,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
