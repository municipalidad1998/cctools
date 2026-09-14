package com.municipalidad.localmix
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
class LibraryRepository(private val c:Context){
 fun scan():List<Track>{
  val out=mutableListOf<Track>();val p=arrayOf(MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM,MediaStore.Audio.Media.DURATION)
  c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,p,"${MediaStore.Audio.Media.IS_MUSIC} != 0",null,"${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use{x->
   val id=x.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);val t=x.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);val a=x.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);val al=x.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);val d=x.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
   while(x.moveToNext()){val i=x.getLong(id);out+=Track(i,x.getString(t) ?: "Sin título",x.getString(a) ?: "Artista desconocido",x.getString(al) ?: "Álbum desconocido",x.getLong(d),ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,i).toString())}
  };return out
 }
}
