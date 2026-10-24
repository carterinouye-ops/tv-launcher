package com.carter.glasslauncher.data

import android.content.Context
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.util.Log

/** Physical HDMI ports, with the TV's current user-assigned labels. */
object HdmiInputs {
    data class Input(val id: String, val port: Int, val name: String, val state: Int)

    fun list(context: Context): List<Input> {
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return runCatching {
            val all = manager.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI }
            val ids = all.map { it.id }.toSet()
            // CEC children describe devices on a port, not additional physical sockets.
            val ports = all.filter { it.parentId !in ids }.sortedWith(compareBy(
                { info -> Regex("HDMI\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(info.loadLabel(context)?.toString().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("HW(\\d+)$").find(info.id)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE },
                { it.id }
            ))
            ports.mapIndexed { index, info ->
                val port = index + 1
                val custom = info.loadCustomLabel(context)?.toString()?.trim().orEmpty()
                val child = all.firstOrNull { it.parentId == info.id }
                val childLabel = child?.loadCustomLabel(context)?.toString()?.takeIf { it.isNotBlank() }
                    ?: child?.loadLabel(context)?.toString()?.takeIf { it.isNotBlank() }
                val label = info.loadLabel(context)?.toString()?.trim().orEmpty()
                val name = custom.takeIf { it.isNotBlank() } ?: childLabel
                    ?: HdmiLabelSync.name(context, port)
                    ?: label.takeIf { it.isNotBlank() } ?: "HDMI $port"
                Log.d("HdmiInputs", "port=$port id=${info.id} label=$label custom=$custom name=$name extras=${info.extras}")
                Input(info.id, port, name, manager.getInputState(info.id))
            }.take(3)
        }.onFailure { Log.w("HdmiInputs", "Cannot read HDMI inputs", it) }.getOrDefault(emptyList())
    }

    fun hdmiOneInputId(context: Context): String? = list(context).firstOrNull()?.id
}
