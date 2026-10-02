-keepnames class fr.alerteresidents.data.model.WeenectPositionDto
-if class fr.alerteresidents.data.model.WeenectPositionDto
-keep class fr.alerteresidents.data.model.WeenectPositionDtoJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class fr.alerteresidents.data.model.WeenectPositionDto
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-keepclassmembers class fr.alerteresidents.data.model.WeenectPositionDto {
    public synthetic <init>(java.lang.String,java.lang.Double,java.lang.Double,java.lang.Integer,java.lang.Double,java.lang.Integer,java.lang.Boolean,java.lang.Integer,java.lang.Integer,java.lang.Integer,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.Boolean,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.Integer,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
