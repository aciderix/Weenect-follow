-keepnames class fr.alerteresidents.data.model.LatLonJson
-if class fr.alerteresidents.data.model.LatLonJson
-keep class fr.alerteresidents.data.model.LatLonJsonJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
