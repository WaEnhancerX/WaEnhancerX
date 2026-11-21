#!/usr/bin/env python3
"""Exact name lookups: real production Kotlin/Java, explicit Android doubles, real SQLite queries.
The mocked provider is not Android Binder/ContactsProvider; the SQL runner is Python SQLite.
"""
from pathlib import Path
import re, sqlite3, subprocess, tempfile, json
ROOT=Path(__file__).resolve().parents[1]; SRC=ROOT/'app/src/main/java/com/waenhancer'
STUBS={
'Cursor.kt':'''package android.database
class Cursor(val rows:List<List<Any?>>):java.io.Closeable {var index=-1;val columnCount:Int get()=rows.firstOrNull()?.size?:0
 fun moveToFirst():Boolean {index=0;return rows.isNotEmpty()}
 fun moveToNext():Boolean {index++;return index<rows.size}
 fun getString(i:Int):String?=rows[index][i] as? String
 fun getInt(i:Int):Int=(rows[index][i] as? Number)?.toInt()?:0
 fun getLong(i:Int):Long=(rows[index][i] as? Number)?.toLong()?:0
 fun getColumnIndex(name:String)=0
 override fun close(){} }
''',
'Database.kt':'''package android.database.sqlite
import android.database.Cursor
import android.content.*
class SQLiteDatabase(val path:String):java.io.Closeable {
 companion object { const val OPEN_READONLY=1;const val CONFLICT_REPLACE=5
 var reads=mutableListOf<Triple<String,String,List<String>>>()
 var handler:(String,String,List<String>)->List<List<Any?>> = { _, _, _ -> emptyList() }
 fun openDatabase(path:String,factory:Any?,flags:Int)=SQLiteDatabase(path) }
 fun rawQuery(sql:String,args:Array<String>?):Cursor{val a=args?.toList()?:emptyList();reads.add(Triple(path,sql,a));return Cursor(handler(path,sql,a))}
 fun execSQL(sql:String){};fun insertWithOnConflict(t:String,n:String?,v:ContentValues,f:Int)=1L
 fun update(t:String,v:ContentValues,w:String,args:Array<String>)=1
 fun query(t:String,cols:Array<String>,s:String?,a:Array<String>?,g:String?,h:String?,o:String?):Cursor=Cursor(emptyList())
 override fun close(){} }
abstract class SQLiteOpenHelper(c:Context,n:String,f:Any?,v:Int){val readableDatabase=SQLiteDatabase("preserved");val writableDatabase=readableDatabase
 abstract fun onCreate(db:SQLiteDatabase);abstract fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)
 open fun onDowngrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){throw IllegalStateException("Unsupported database downgrade")}}
''',
'Log.kt':'''package android.util
object Log {
 @JvmStatic fun e(tag:String,message:String):Int=0
 @JvmStatic fun e(tag:String,message:String,error:Throwable):Int=0
}
''',
'Context.kt':'''package android.content
import android.database.Cursor
import android.net.Uri
import java.io.File
open class Context {val applicationContext:Context get()=this;val resources=Resources();val packageName="fixture.host"
 val contentResolver=Resolver();var dbRoot=File(".")
 fun getDatabasePath(n:String):File=File(dbRoot,n)}
class Resources{fun getIdentifier(n:String,t:String,p:String)=0}
class Resolver {var rows:List<List<Any?>> = emptyList();var calls=0
 fun query(u:Uri,p:Array<String>,s:String?,a:Array<String>?,o:String?):Cursor{calls++;return Cursor(rows)}}
class ContentValues{fun put(k:String,v:String?){};fun put(k:String,v:Int){};fun put(k:String,v:Long){}}
class Intent{fun getStringExtra(k:String):String?=null;val extras:Bundle?=null}
class Bundle {fun keySet():Set<String> = emptySet();fun get(k:String):Any?=null}
''',
'Activity.kt':'''package android.app
import android.content.*
import android.view.*
open class Activity:Context(){val intent:Intent?=null;val window:Window?=null;val actionBar:ActionBar?=null;val title:CharSequence?=null
 fun <T:View> findViewById(i:Int):T?=null}
class ActionBar{val title:CharSequence?=null};class Window{val decorView:View?=null}
''',
'Views.kt':'''package android.view
open class View
open class ViewGroup:View(){val childCount=0;fun getChildAt(i:Int):View=View()}
''',
'Text.kt':'''package android.widget
class TextView:android.view.View(){val text:CharSequence?=null}
''',
'Uri.kt':'''package android.net
class Uri{companion object{fun withAppendedPath(u:Uri,p:String)=Uri();fun encode(s:String)=s}}
''',
'Contacts.kt':'''package android.provider
object ContactsContract{object PhoneLookup{val CONTENT_FILTER_URI=android.net.Uri();const val DISPLAY_NAME="display_name";const val NORMALIZED_NUMBER="normalized_number";const val NUMBER="number"}}
'''
}
TEST=r'''package com.waenhancer.utils
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.waenhancer.xposed.core.db.PreservedMessageStore
import java.nio.file.Files
fun main(){
 var checks=0
 fun ck(n:String,v:Boolean){check(v){"ASSERTION|$n"};checks++}
 val ctx=Context();ctx.dbRoot=Files.createTempDirectory("names-host").toFile()
 ctx.getDatabasePath("wa.db").writeText("");ctx.getDatabasePath("msgstore.db").writeText("")
 val phone="56912345678@s.whatsapp.net";val lid="123456789012345@lid"
 for(j in listOf(phone,lid,"123-456@g.us","120363000000000000@g.us","123456789012345@s.whatsapp.net"))ck("whole identity $j",ContactIdentityPolicy.canonicalJid(j)==j)
 for(j in listOf("","123","$phone.evil"," $phone","$phone\n","123@lid.evil","123:2@lid","123456@other.net","١٢٣٤٥@s.whatsapp.net","<jid:$phone>"))ck("reject $j",ContactIdentityPolicy.canonicalJid(j)==null)
 ck("long phone is not LID",!ContactNameResolver.isLidJid("123456789012345@s.whatsapp.net"))
 ck("hyphenated phone not group",!ContactNameResolver.isGroupJid("+56-912345678"))
 ck("format full phone",ContactIdentityPolicy.phoneDigits("+56 (9) 1234-5678")=="56912345678")
 ck("reject phone operators",ContactIdentityPolicy.phoneDigits("56912345%") == null)
 SQLiteDatabase.handler={_,sql,args->
  check(!sql.contains("LIKE"));check(args.isNotEmpty())
  if(sql.contains("wa_contacts") && args.single()==phone)listOf(listOf("Exact contact",null,null)) else emptyList()
 }
 ck("exact database beats supplied fallback",ContactNameResolver.resolveName(ctx,phone,"Wrong fallback")=="Exact contact")
 ck("no implicit LID phone hint",ContactNameResolver.resolveName(ctx,lid,null,"56912345678")=="Unknown")
 ck("reject unqualified namespace",ContactNameResolver.resolveName(ctx,"123456789012345")=="Unknown")
 SQLiteDatabase.handler={_,sql,_->if(sql.contains("wa_contacts"))listOf(listOf("Name one",null,null),listOf("Name two",null,null))else emptyList()}
 ck("conflicting rows fail",ContactNameResolver.resolveFromWaDb(ctx,phone)==null)
 SQLiteDatabase.handler={_,sql,_->if(sql.contains("wa_contacts"))List(33){listOf("Same name",null,null)}else emptyList()}
 ck("bounded rows fail",ContactNameResolver.resolveFromWaDb(ctx,phone)==null)
 SQLiteDatabase.handler={_,sql,_->if(sql.contains("wa_contacts"))listOf(listOf(null,"Same name",null),listOf("Same name",null,null))else emptyList()}
 ck("same names permitted",ContactNameResolver.resolveFromWaDb(ctx,phone)=="Same name")
 SQLiteDatabase.handler={_,sql,args->if(sql.contains("jid_map")&&args.single()==lid)listOf(listOf(phone))else emptyList()}
 ck("verified LID edge",ContactNameResolver.resolveLidToPhone(ctx,lid)=="56912345678")
 val activity=android.app.Activity();activity.dbRoot=ctx.dbRoot
 SQLiteDatabase.handler={_,sql,args->when {
  sql.contains("jid_map") && args.single()==lid -> listOf(listOf(phone))
  sql.contains("wa_contacts") && args.single()==phone -> listOf(listOf("Activity exact",null,null))
  else -> emptyList()
 }}
 val activityReads=SQLiteDatabase.reads.size
 ck("activity mapped phone keeps namespace",ContactNameResolver.resolveFromActivity(activity,lid)=="Activity exact")
 ck("activity exact path uses three queries",SQLiteDatabase.reads.size-activityReads==3)
 SQLiteDatabase.handler={_,sql,_->if(sql.contains("jid_map"))listOf(listOf(phone),listOf("56912345679@s.whatsapp.net"))else emptyList()}
 ck("conflicting LID edge",ContactNameResolver.resolveLidToPhone(ctx,lid)==null)
 SQLiteDatabase.handler={_,sql,_->if(sql.contains("jid_map"))listOf(listOf("56912345678@lid"))else emptyList()}
 ck("mapping must end in phone namespace",ContactNameResolver.resolveLidToPhone(ctx,lid)==null)
 SQLiteDatabase.handler={_,_,_->emptyList()}
 ctx.contentResolver.rows=listOf(listOf("Wrong suffix","+19912345678","9912345678"))
 ck("PhoneLookup fuzzy rejected",ContactNameResolver.getSystemContactName(ctx,"56912345678")==null)
 ctx.contentResolver.rows=listOf(listOf("Right number","+56912345678","912345678"))
 ck("normalized full number",ContactNameResolver.getSystemContactName(ctx,"56912345678")=="Right number")
 ctx.contentResolver.rows=listOf(listOf("Right number",null,"+56 9 1234-5678"))
 ck("full formatted number fallback",ContactNameResolver.getSystemContactName(ctx,"56912345678")=="Right number")
 ctx.contentResolver.rows=listOf(listOf("Name A","+56912345678",null),listOf("Name B","+56912345678",null))
 ck("provider ambiguity",ContactNameResolver.getSystemContactName(ctx,"56912345678")==null)
 SQLiteDatabase.handler={p,sql,args->
  if(p=="preserved"){check(sql.contains("WHERE \$COL_JID").not());check(!sql.contains("LIKE"));check(args.single()==phone);listOf(listOf("Cached exact"))}else emptyList()
 }
 ck("exact preserved cache",PreservedMessageStore.getInstance(ctx).getContactNameByJid(phone)=="Cached exact")
 SQLiteDatabase.handler={p,_,_->if(p=="preserved")listOf(listOf("Name A"),listOf("Name B"))else emptyList()}
 ck("conflicting preserved cache",PreservedMessageStore.getInstance(ctx).getContactNameByJid(phone)==null)
 ck("cache no invented namespace",PreservedMessageStore.getInstance(ctx).getContactNameByJid("12345")==null)
 ck("databases confined to caller",SQLiteDatabase.reads.all{it.first=="preserved"||it.first.startsWith(ctx.dbRoot.absolutePath+"/")})
 println("PASS: $checks whole-resolver/policy checks (explicit Android/database/provider doubles)")
}
'''

def sql_checks():
    text=(SRC/'utils/ContactNameResolver.kt').read_text();store=(SRC/'xposed/core/db/PreservedMessageStore.kt').read_text()
    assert ' LIKE ' not in text and ' LIKE ' not in store[store.index('fun getContactNameByJid('):]
    assert '"/data/data/' not in text and '"/data/user/0/' not in text
    sql=re.search(r'"(SELECT display_name, wa_name, sort_name FROM wa_contacts[^"\n]+)"',text).group(1)
    mapping=re.search(r'val query = """(.*?)""".trimIndent\(\)',text,re.S).group(1)
    business=re.search(r'"(SELECT verified_name FROM wa_vnames[^"\n]+)"',text).group(1)
    cache=re.search(r'"(SELECT DISTINCT \$COL_NAME FROM \$TABLE_NAME[^"\n]+)"',store).group(1).replace('$COL_NAME','contact_name').replace('$TABLE_NAME','preserved_messages').replace('$COL_JID','jid')
    db=sqlite3.connect(':memory:')
    db.executescript('CREATE TABLE wa_contacts (jid TEXT, display_name TEXT, wa_name TEXT, sort_name TEXT);CREATE TABLE wa_vnames(jid TEXT,verified_name TEXT);CREATE TABLE jid(_id INTEGER,user TEXT,raw_string TEXT);CREATE TABLE jid_map(lid_row_id INT,jid_row_id INT);CREATE TABLE preserved_messages(jid TEXT,contact_name TEXT);')
    good='56912345678@s.whatsapp.net';bad='995691234567800@s.whatsapp.net'
    for jid,name in [(bad,'Wrong company'),(good,'Exact contact')]:
        db.execute('INSERT INTO wa_contacts VALUES (?,?,NULL,NULL)',(jid,name));db.execute('INSERT INTO wa_vnames VALUES (?,?)',(jid,name));db.execute('INSERT INTO preserved_messages VALUES (?,?)',(jid,name))
    for q in [sql,business,cache]:assert db.execute(q,(good,)).fetchall()[0][0]=='Exact contact';assert len(db.execute(q,(good,)).fetchall())==1;assert db.execute(q,('%',)).fetchall()==[]
    db.executemany('INSERT INTO jid VALUES (?,?,?)',[(1,'1234','1234@lid'),(2,'12345','12345@lid'),(3,'56912345678',good),(4,'995691234567800',bad)])
    db.executemany('INSERT INTO jid_map VALUES (?,?)',[(2,4),(1,3)])
    assert db.execute(mapping,('1234@lid',)).fetchall()==[(good,)]
    db.execute('INSERT INTO jid_map VALUES (1,4)');assert len(db.execute(mapping,('1234@lid',)).fetchall())==2
    db.execute('INSERT INTO preserved_messages VALUES (?,?)',(good,'Conflicting name'));assert len(db.execute(cache,(good,)).fetchall())==2
    print('PASS: 12 real SQLite predicates/results; exact keys, no suffix/prefix/namespace mixing')

def main():
    sql_checks()
    with tempfile.TemporaryDirectory(prefix='waex-names-test-') as t:
        d=Path(t);c=d/'classes'
        cursor=d/'Cursor.java'
        cursor.write_text('''package android.database;
import java.util.*; public class Cursor implements java.io.Closeable {
private final List<? extends List<?>> rows;private int index=-1;
public Cursor(List<? extends List<?>> r){rows=r;}
public boolean moveToFirst(){index=0;return !rows.isEmpty();}
public boolean moveToNext(){return ++index<rows.size();}
public String getString(int n){return (String)rows.get(index).get(n);}
public int getInt(int n){return ((Number)rows.get(index).get(n)).intValue();}
public long getLong(int n){return ((Number)rows.get(index).get(n)).longValue();}
public int getColumnCount(){return rows.isEmpty()?0:rows.get(0).size();}
public int getColumnIndex(String s){return 0;}
public void close(){}
}''')
        subprocess.run(['javac','--release','17','-d',str(c),str(SRC/'utils/ContactIdentityPolicy.java'),str(cursor)],check=True,timeout=60)
        for p,s in STUBS.items():
            if p != 'Cursor.kt': (d/p).write_text(s)
        (d/'Test.kt').write_text(TEST)
        subprocess.run(['kotlinc','-jvm-target','17','-cp',str(c),*map(str,d.glob('*.kt')),str(SRC/'utils/ContactNameResolver.kt'),str(SRC/'xposed/core/db/PreservedMessageStore.kt'),'-include-runtime','-d',str(d/'test.jar')],check=True,timeout=90)
        print('COMPILE_OK: full Kotlin resolver/store and Java identity policy', flush=True)
        subprocess.run(['java','-cp',str(d/'test.jar')+':'+str(c),'com.waenhancer.utils.TestKt'],check=True,timeout=60)
if __name__=='__main__':main()
