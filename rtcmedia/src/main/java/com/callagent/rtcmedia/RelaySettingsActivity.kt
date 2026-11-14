package com.callagent.rtcmedia

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

/** Shared settings entry point. Incoming QR links require explicit confirmation before storage or network access. */
class RelaySettingsActivity:Activity() {
    private lateinit var root:LinearLayout
    private fun button(label:String,action:()->Unit)=Button(this).apply{text=label;setOnClickListener{action()}}
    private fun message(text:String){Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);render()
        intent.data?.toString()?.let { link ->
            intent.data=null
            try {
                val item=RelayRepository.parseLink(link)
                AlertDialog.Builder(this).setTitle("添加中继来源？").setMessage(item.getString("name")+"\n确认后才会保存凭据并在通话时访问此来源。")
                    .setPositiveButton("添加"){_,_->change{it.put(item)}}.setNegativeButton("取消",null).show()
            }catch(_:Exception){message("来源链接无效")}
        }
    }
    private fun render(){
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,32,24,32)}
        setContentView(ScrollView(this).apply{addView(root)})
        root.addView(TextView(this).apply{text="中继来源";textSize=24f})
        root.addView(TextView(this).apply{text="节点下发默认可用。可添加独立来源或静态 TURN；开启、置顶或移除只影响后续通话。"})
        root.addView(button("添加配对链接"){formLink()});root.addView(button("添加静态 TURN"){formStatic()})
        val items=try{RelayRepository.load(this)}catch(_:Exception){root.addView(button("来源存储不可读：清除后重新添加"){AlertDialog.Builder(this).setMessage("清除全部已保存来源？").setPositiveButton("清除"){_,_->runCatching{RelayRepository.reset(this)};render()}.setNegativeButton("取消",null).show()});return}
        for(i in 0 until items.length()){
            val item=items.getJSONObject(i);val row=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
            row.addView(Switch(this).apply{text=item.optString("name","来源");isChecked=item.optBoolean("enabled",true);setOnCheckedChangeListener{_,checked->change{list->list.getJSONObject(i).put("enabled",checked)}}})
            row.addView(CheckBox(this).apply{text="优先使用";isChecked=item.optBoolean("pinned");setOnCheckedChangeListener{_,checked->change{list->if(checked)(0 until list.length()).forEach{list.getJSONObject(it).put("pinned",false)};list.getJSONObject(i).put("pinned",checked)}}})
            row.addView(button("移除"){change{it.remove(i)}});root.addView(row)
        }
    }
    private fun change(update:(JSONArray)->Unit){try{val items=RelayRepository.load(this);update(items);RelayRepository.save(this,items);render()}catch(_:Exception){message("保存失败，请检查来源数量或存储状态")}}
    private fun formLink(){
        val field=EditText(this).apply{hint="mahogsm://relay?...";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;maxLines=4}
        val dialog=AlertDialog.Builder(this).setTitle("添加来源链接").setView(field).setPositiveButton("添加",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{try{val item=RelayRepository.parseLink(field.text.toString());change{it.put(item)};dialog.dismiss()}catch(_:Exception){message("请输入有效的 HTTPS 来源配对链接")}}};dialog.show()
    }
    private fun formStatic(){
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,8,24,8)}
        fun input(label:String,secret:Boolean=false)=EditText(this).apply{hint=label;inputType=if(secret)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT;form.addView(this)}
        val name=input("名称");val url=input("TURN 地址，多个地址用逗号分隔");val user=input("用户名");val password=input("密码",true)
        val dialog=AlertDialog.Builder(this).setTitle("静态 TURN").setView(form).setPositiveButton("添加",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            try {
                val urls=url.text.toString().split(',').map{it.trim()};val server=RelayIceServer(urls,user.text.toString(),password.text.toString());val id=java.util.UUID.randomUUID().toString()
                RelaySources.select(emptyList(),listOf(RelaySource(id,listOf(server))))
                val item=JSONObject().put("id",id).put("type","static").put("name",name.text.toString().take(80).ifBlank{"静态 TURN"}).put("enabled",true).put("pinned",false)
                    .put("ice_servers",JSONArray().put(JSONObject().put("urls",JSONArray(urls)).put("username",server.username).put("credential",server.credential)))
                change{it.put(item)};dialog.dismiss()
            }catch(_:Exception){message("请检查 TURN 地址、用户名和密码")}
        }};dialog.show()
    }
}
