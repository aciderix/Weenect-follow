-keepnames class fr.alerteresidents.data.model.WeenectLoginRequest
-if class fr.alerteresidents.data.model.WeenectLoginRequest
-keep class fr.alerteresidents.data.model.WeenectLoginRequestJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
