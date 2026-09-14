package com.municipalidad.localmix

data class Track(val id:Long,val title:String,val artist:String,val album:String,val durationMs:Long,val uri:String)
data class MixPoint(val fromTrackId:Long,val fromAtMs:Long,val toAtMs:Long,val fadeMs:Long)
