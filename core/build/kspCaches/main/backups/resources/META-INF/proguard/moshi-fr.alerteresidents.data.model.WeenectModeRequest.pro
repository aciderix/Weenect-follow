-keepnames class fr.alerteresidents.data.model.WeenectModeRequest
-if class fr.alerteresidents.data.model.WeenectModeRequest
-keep class fr.alerteresidents.data.model.WeenectModeRequestJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
