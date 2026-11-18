package com.callagent.rtcmedia

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import org.json.JSONArray
import org.json.JSONObject
import com.google.android.material.R as MaterialR

/**
 * "TURN 中继": the relay sources a call may use, shared by both apps. It draws
 * the same rounded-tile settings look as the apps' own pages from the app
 * theme. Incoming `mahogsm://relay` links require explicit confirmation before
 * storage or network access.
 */
class RelaySettingsActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            title = "TURN 中继"
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationContentDescription("返回")
            setNavigationOnClickListener { finish() }
        })
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
        intent.data?.toString()?.let { link ->
            intent.data = null
            try {
                val item = RelayRepository.parseLink(link)
                MaterialAlertDialogBuilder(this).setTitle("添加中继来源？")
                    .setMessage(item.getString("name") + "\n\n确认后才会保存凭据，并在通话时访问此来源。")
                    .setPositiveButton("添加") { _, _ -> change { it.put(item) } }
                    .setNegativeButton("取消", null).show()
            } catch (_: Exception) { message("来源链接无效") }
        }
    }

    private fun render() {
        content.removeAllViews()
        content.addView(body("通话无法直连时经 TURN 中继转发音频。节点自带的中继默认启用；还可添加独立来源或静态 TURN 服务器。" +
            "有多个来源时先用“优先使用”的来源，其余按延迟排序。更改只影响之后的通话。"))
        val items = try { RelayRepository.load(this) } catch (_: Exception) {
            content.addView(header("来源"))
            content.addView(tile(Position.SINGLE, "来源存储不可读", "点按清除全部已保存的来源，然后重新添加", R.drawable.rtc_relay_ic_delete, error = true) {
                MaterialAlertDialogBuilder(this).setTitle("清除全部来源？")
                    .setMessage("已保存的独立来源和静态 TURN 都会被删除，节点自带的中继不受影响。")
                    .setPositiveButton("清除") { _, _ -> runCatching { RelayRepository.reset(this) }; render() }
                    .setNegativeButton("取消", null).show()
            })
            return
        }
        content.addView(header("来源"))
        if (items.length() == 0) {
            content.addView(tile(Position.SINGLE, "暂无来源", "连接支持中继下发的节点后，节点中继会自动出现在这里", R.drawable.rtc_relay_ic_server))
        }
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val enabled = item.optBoolean("enabled", true)
            val switch = MaterialSwitch(this).apply {
                isChecked = enabled
                contentDescription = "启用 " + item.optString("name", "来源")
                setOnCheckedChangeListener { _, checked -> change { list -> list.getJSONObject(i).put("enabled", checked) } }
            }
            content.addView(tile(position(i, items.length()), item.optString("name", "来源"), summary(item),
                icon(item), trailing = switch) { showActions(i, item) })
        }
        content.addView(header("添加"))
        val full = items.length() >= MAX_SOURCES
        content.addView(tile(Position.FIRST, "添加来源链接",
            if (full) "已达 $MAX_SOURCES 个来源上限" else "粘贴服务端发放的 mahogsm://relay 链接", R.drawable.rtc_relay_ic_link) {
            if (full) message("已达 $MAX_SOURCES 个来源上限，请先移除一个") else formLink()
        })
        content.addView(tile(Position.LAST, "添加静态 TURN",
            if (full) "已达 $MAX_SOURCES 个来源上限" else "手动填写 TURN 地址、用户名和密码", R.drawable.rtc_relay_ic_add) {
            if (full) message("已达 $MAX_SOURCES 个来源上限，请先移除一个") else formStatic()
        })
    }

    private fun summary(item: JSONObject): String {
        val kind = when (item.optString("type")) {
            "node" -> "节点自带"
            "external" -> "独立来源 · " + runCatching { java.net.URI(item.getString("url")).host }.getOrDefault("")
            "static" -> "静态 TURN · " + runCatching { item.getJSONArray("ice_servers").getJSONObject(0).getJSONArray("urls").getString(0) }.getOrDefault("")
            else -> "来源"
        }
        val state = when {
            !item.optBoolean("enabled", true) -> "已停用"
            item.optBoolean("pinned") -> "优先使用"
            else -> null
        }
        return listOfNotNull(kind.trimEnd(' ', '·'), state).joinToString(" · ")
    }

    private fun icon(item: JSONObject): Int = when (item.optString("type")) {
        "node" -> R.drawable.rtc_relay_ic_server
        "external" -> R.drawable.rtc_relay_ic_link
        else -> R.drawable.rtc_relay_ic_relay
    }

    private fun showActions(index: Int, item: JSONObject) {
        val pinned = item.optBoolean("pinned")
        val removable = item.optString("type") != "node"
        val labels = listOfNotNull(if (pinned) "取消优先" else "优先使用", "移除".takeIf { removable })
        MaterialAlertDialogBuilder(this).setTitle(item.optString("name", "来源"))
            .setItems(labels.toTypedArray()) { _, which ->
                when (labels[which]) {
                    "移除" -> MaterialAlertDialogBuilder(this).setTitle("移除此来源？")
                        .setMessage("之后的通话不再使用“${item.optString("name", "来源")}”，已保存的凭据一并删除。")
                        .setPositiveButton("移除") { _, _ -> change { it.remove(index) } }
                        .setNegativeButton("取消", null).show()
                    else -> change { list ->
                        if (!pinned) (0 until list.length()).forEach { list.getJSONObject(it).put("pinned", false) }
                        list.getJSONObject(index).put("pinned", !pinned)
                    }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun change(update: (JSONArray) -> Unit) {
        try {
            val items = RelayRepository.load(this); update(items); RelayRepository.save(this, items)
        } catch (_: Exception) { message("保存失败，请检查来源数量或存储状态") }
        render()
    }

    private fun formLink() {
        val form = dialogForm()
        val field = input(form, "mahogsm://relay?…", secret = true)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("添加来源链接").setView(form)
            .setPositiveButton("添加", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val item = RelayRepository.parseLink(field.text.toString().trim())
                    change { it.put(item) }; dialog.dismiss()
                } catch (_: Exception) { message("请输入有效的 HTTPS 来源配对链接") }
            }
        }
        dialog.show()
    }

    private fun formStatic() {
        val form = dialogForm()
        val name = input(form, "名称（可选）")
        val url = input(form, "TURN 地址，多个用逗号分隔")
        val user = input(form, "用户名")
        val password = input(form, "密码", secret = true)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("静态 TURN").setView(form)
            .setPositiveButton("添加", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val urls = url.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val server = RelayIceServer(urls, user.text.toString(), password.text.toString())
                    val id = java.util.UUID.randomUUID().toString()
                    RelaySources.select(emptyList(), listOf(RelaySource(id, listOf(server))))
                    val item = JSONObject().put("id", id).put("type", "static")
                        .put("name", name.text.toString().trim().take(80).ifBlank { "静态 TURN" }).put("enabled", true).put("pinned", false)
                        .put("ice_servers", JSONArray().put(JSONObject().put("urls", JSONArray(urls))
                            .put("username", server.username).put("credential", server.credential)))
                    change { it.put(item) }; dialog.dismiss()
                } catch (_: Exception) { message("请检查 TURN 地址、用户名和密码") }
            }
        }
        dialog.show()
    }

    private fun dialogForm() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), 0)
    }

    private fun input(form: LinearLayout, hint: String, secret: Boolean = false): TextInputEditText {
        val edit = TextInputEditText(this).apply {
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            maxLines = 4
        }
        form.addView(TextInputLayout(this).apply {
            this.hint = hint
            if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(edit)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        return edit
    }

    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    // The apps' grouped-tile look (HostUi / GatewayUi), drawn from theme attributes.
    private enum class Position { SINGLE, FIRST, MIDDLE, LAST }

    private fun position(index: Int, count: Int) = when {
        count <= 1 -> Position.SINGLE
        index == 0 -> Position.FIRST
        index == count - 1 -> Position.LAST
        else -> Position.MIDDLE
    }

    private fun color(attr: Int): Int = MaterialColors.getColor(this, attr, 0)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun body(text: String) = MaterialTextView(this).apply {
        this.text = text
        setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyMedium)
        setTextColor(color(MaterialR.attr.colorOnSurfaceVariant))
        setPadding(dp(4), dp(8), dp(4), 0)
    }

    private fun header(text: String) = MaterialTextView(this).apply {
        this.text = text
        setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleSmall)
        setTextColor(color(androidx.appcompat.R.attr.colorPrimary))
        setPadding(dp(16), dp(20), dp(16), dp(8))
    }

    private fun tileBackground(position: Position): Drawable {
        val large = dp(24).toFloat(); val small = dp(6).toFloat()
        val top = if (position == Position.SINGLE || position == Position.FIRST) large else small
        val bottom = if (position == Position.SINGLE || position == Position.LAST) large else small
        val radii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        val shape = GradientDrawable().apply { cornerRadii = radii; setColor(color(MaterialR.attr.colorSurfaceContainer)) }
        val mask = GradientDrawable().apply { cornerRadii = radii; setColor(0xFFFFFFFF.toInt()) }
        return RippleDrawable(ColorStateList.valueOf(color(MaterialR.attr.colorOnSurface) and 0x1FFFFFFF), shape, mask)
    }

    private fun tile(position: Position, title: String, summary: String?, icon: Int, trailing: View? = null,
                     error: Boolean = false, onClick: (() -> Unit)? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = tileBackground(position)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (position == Position.MIDDLE || position == Position.LAST) topMargin = dp(2)
        }
        if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color(MaterialR.attr.colorOnSecondaryContainer))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(MaterialR.attr.colorSecondaryContainer)) }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, MaterialR.style.TextAppearance_Material3_BodyLarge,
            if (error) androidx.appcompat.R.attr.colorError else MaterialR.attr.colorOnSurface, 2))
        if (!summary.isNullOrBlank()) texts.addView(text(summary, MaterialR.style.TextAppearance_Material3_BodyMedium,
            MaterialR.attr.colorOnSurfaceVariant, 3).apply { setPadding(0, dp(2), 0, 0) })
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) }) }
    }

    private fun text(value: String, appearance: Int, colorAttr: Int, lines: Int): TextView = MaterialTextView(this).apply {
        text = value
        setTextAppearance(appearance)
        setTextColor(color(colorAttr))
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }

    companion object {
        /** RelayRepository.save's limit. */
        private const val MAX_SOURCES = 7

        /** One-line state for the apps' settings tile: how many sources are on. */
        fun summary(context: Context): String {
            val items = runCatching { RelayRepository.load(context) }.getOrElse { return "来源存储不可读，点按处理" }
            val list = (0 until items.length()).map { items.getJSONObject(it) }
            val enabled = list.count { it.optBoolean("enabled", true) }
            val custom = list.count { it.optString("type") != "node" }
            val pinned = list.firstOrNull { it.optBoolean("pinned") && it.optBoolean("enabled", true) }?.optString("name")
            return when {
                list.isEmpty() -> "使用节点自带的中继"
                else -> listOfNotNull("已启用 $enabled / ${list.size} 个来源",
                    "自定义 $custom 个".takeIf { custom > 0 }, pinned?.let { "优先：$it" }).joinToString(" · ")
            }
        }
    }
}
